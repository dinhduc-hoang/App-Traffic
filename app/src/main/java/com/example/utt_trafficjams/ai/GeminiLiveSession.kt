package com.example.utt_trafficjams.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Quản lý phiên WebSocket với Gemini 2.0 Multimodal Live API.
 *
 * Giao thức:
 *  - Input : audio/pcm;rate=16000 hoặc text
 *  - Output: audio/pcm;rate=24000 + text transcript
 */
class GeminiLiveSession(
    private val apiKey         : String,
    private val onAudioChunk   : (ByteArray) -> Unit,  // PCM 24kHz từ Gemini
    private val onTextReceived : (String) -> Unit,     // Text transcript
    private val onConnected    : (String) -> Unit,
    private val onDisconnected : (String) -> Unit,
    private val onError        : (String) -> Unit,
    private val liveAddressContextProvider: () -> LiveAddressContext = { LiveAddressContext() },
    private val trafficToolService: TrafficToolService = MockTrafficToolService(),
    private val onOpenGoogleMapsRequested: (origin: String?, destination: String, travelMode: String) -> Boolean = { _, _, _ -> false }
) {
    companion object {
        private const val TAG   = "GeminiLiveSession"
        private const val MODEL = "models/gemini-3.1-flash-live-preview"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Vô hiệu hóa ping nội bộ của OkHttp vì server Gemini không hỗ trợ chuẩn Ping/Pong
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingTexts = ArrayDeque<String>()
    private val responseTextBuffer = StringBuilder()
    private var activityStarted = false
    private var pendingActivityStart = false

    // ── Connect ────────────────────────────────────────────────
    fun connect() {
        if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) return

        if (apiKey.isBlank()) {
            _state.value = State.ERROR
            onError("GEMINI_API_KEY đang rỗng. Hãy kiểm tra local.properties.")
            return
        }

        _state.value = State.CONNECTING
        Log.d(TAG, "Connecting to Gemini Live API…")

        val request = Request.Builder()
            .url("$WS_URL?key=$apiKey")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                onConnected("Đã khởi tạo WebSocket, đang gửi gói cấu hình...")
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Gemini API đôi khi trả về dữ liệu dưới dạng Binary Frames thay vì Text Frames.
                // Hàm này sẽ đón bắt, giải mã ra chuỗi JSON (UTF-8) và truyền vào parse.
                parseServerMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                val code = response?.code ?: "No Code"
                val body = response?.peekBody(2048)?.string() ?: "No Body"
                
                _state.value = State.ERROR
                onError("Kết nối thất bại: ${t.message}\nHTTP Code: $code\nBody: $body")
                onDisconnected("Đã ngắt kết nối do lỗi.")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $reason")
                _state.value = State.DISCONNECTED
                responseTextBuffer.clear()
                activityStarted = false
                pendingActivityStart = false
                if (code == 1007 || reason.contains("invalid argument", ignoreCase = true)) {
                    onError(
                        "Live API từ chối payload (1007 Invalid Argument). " +
                            "Đã chuyển sang format tương thích; hãy thử gửi lại text trước, rồi thử mic."
                    )
                }
                onDisconnected("Mạng WebSocket đã đóng: $code - $reason")
            }
        })
    }

    // ── Setup message ──────────────────────────────────────────
    private fun sendSetup(webSocket: WebSocket) {
        val systemInstructionText = buildSystemInstructionText()

        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", MODEL)
                // Giữ output là AUDIO để tương thích tốt với Live API.
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                    })
                })
                // Yêu cầu transcription để vẫn hiển thị text trong UI chat.
                put("outputAudioTranscription", JSONObject())
                // Tắt tự động activity detection để tránh tiếng ồn tự mở turn.
                // Client sẽ tự gửi activityStart/activityEnd theo thao tác nút mic.
                put("realtimeInputConfig", JSONObject().apply {
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", true)
                    })
                })
                    put("tools", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionDeclarations", JSONArray().apply {
                                put(buildCheckTrafficFunctionDeclaration())
                                put(buildOpenGoogleMapsFunctionDeclaration())
                            })
                        })
                    })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                                put("text", systemInstructionText)
                        })
                    })
                })
            })
        }
        webSocket.send(setup.toString())
        Log.d(TAG, "Setup sent")
        onConnected("Đã gửi cấu hình tới Gemini, đang chờ xác nhận...")
    }

    // ── Send audio (PCM 16-bit 16kHz) ─────────────────────────
    fun sendAudioChunk(pcm: ByteArray) {
        if (_state.value != State.CONNECTED) return
        if (!activityStarted) {
            sendActivityStartNow()
        }
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", b64)
                    put("mimeType", "audio/pcm;rate=16000")
                })
            })
        }
        ws?.send(msg.toString())
    }

    fun sendAudioStreamEnd() {
        if (_state.value != State.CONNECTED) return
        sendActivityEndNow()
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audioStreamEnd", true)
            })
        }
        ws?.send(msg.toString())
    }

    fun sendActivityStart() {
        if (_state.value != State.CONNECTED) {
            pendingActivityStart = true
            if (_state.value == State.DISCONNECTED || _state.value == State.ERROR) {
                connect()
            }
            return
        }
        sendActivityStartNow()
    }

    fun sendActivityEnd() {
        pendingActivityStart = false
        if (_state.value != State.CONNECTED) {
            activityStarted = false
            return
        }
        sendActivityEndNow()
    }

    private fun sendActivityStartNow() {
        if (activityStarted) return
        val sent = ws?.send(JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("activityStart", JSONObject())
            })
        }.toString()) == true
        if (sent) {
            activityStarted = true
        }
    }

    private fun sendActivityEndNow() {
        if (!activityStarted) return
        ws?.send(JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("activityEnd", JSONObject())
            })
        }.toString())
        activityStarted = false
    }

    // ── Send text ──────────────────────────────────────────────
    fun sendText(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return

        if (_state.value != State.CONNECTED) {
            pendingTexts.addLast(normalized)
            Log.d(TAG, "Text queued while waiting setupComplete: $normalized")
            if (_state.value == State.DISCONNECTED || _state.value == State.ERROR) {
                connect()
            }
            return
        }

        sendTextNow(normalized)
    }

    private fun sendTextNow(text: String) {
        val sentRealtime = ws?.send(JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("text", text)
            })
        }.toString()) == true

        if (!sentRealtime) {
            pendingTexts.addFirst(text)
            _state.value = State.ERROR
            onError("Gửi text thất bại vì WebSocket chưa sẵn sàng.")
            return
        }
        Log.d(TAG, "Text sent: $text")
    }

    private fun onSetupCompleted() {
        if (_state.value == State.CONNECTED) return
        _state.value = State.CONNECTED
        onConnected("Đã kết nối thành công tới Gemini Live API. Bạn có thể nói hoặc chat ngay bây giờ!")
        if (pendingActivityStart) {
            sendActivityStartNow()
            pendingActivityStart = false
        }
        flushPendingTexts()
    }

    private fun flushPendingTexts() {
        while (pendingTexts.isNotEmpty() && _state.value == State.CONNECTED) {
            val pending = pendingTexts.removeFirst()
            sendTextNow(pending)
        }
    }

    // ── Parse server response ──────────────────────────────────
    private fun parseServerMessage(raw: String) {
        try {
            val json = JSONObject(raw)

            if (json.has("setupComplete")) {
                onSetupCompleted()
                return
            }

            val hasToolCall = handleToolCallFromServer(json)

            val serverContent = json.optJSONObject("serverContent")

            // Nếu không phải là serverContent, kiểm tra xem có phải error hay không
            if (serverContent == null) {
                if (hasToolCall) {
                    return
                }
                if (json.has("error")) {
                    onError(formatApiError(json.optJSONObject("error"), raw))
                } else if (json.has("goAway")) {
                    onDisconnected("Server yêu cầu đóng phiên: ${json.optJSONObject("goAway")?.toString()}")
                } else {
                    // Cố tình đẩy lên UI nếu nhận được một gói siêu lạ chưa parse được
                    Log.d(TAG, "Unknown message: $raw")
                }
                return
            }

            // Setup completion check bên trong serverContent (trong một số format v1alpha)
            val setupComplete = serverContent.optJSONObject("setupComplete")
            if (setupComplete != null) {
                onSetupCompleted()
                return
            }

            var gotOutputTranscription = false
            serverContent.optJSONObject("outputTranscription")
                ?.optString("text", "")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    gotOutputTranscription = true
                    mergeResponseText(it)
                }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)

                    // Audio response
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val mime = inlineData.optString("mimeType", "")
                        if (mime.startsWith("audio/pcm")) {
                            val audioB64 = inlineData.getString("data")
                            val audioBytes = Base64.decode(audioB64, Base64.NO_WRAP)
                            onAudioChunk(audioBytes)
                        }
                    }

                    // Fallback text khi chưa có outputTranscription
                    if (!gotOutputTranscription) {
                        val textPart = part.optString("text", "")
                        if (textPart.isNotBlank()) {
                            mergeResponseText(textPart)
                        }
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                flushResponseText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            onError("Lỗi phân tích JSON từ server: ${e.message}\nRaw: $raw")
        }
    }

    private fun mergeResponseText(chunk: String) {
        val normalized = chunk.trim()
        if (normalized.isBlank()) return

        val current = responseTextBuffer.toString()
        when {
            current.isBlank() -> {
                responseTextBuffer.append(normalized)
            }
            normalized.startsWith(current) -> {
                responseTextBuffer.clear()
                responseTextBuffer.append(normalized)
            }
            current.endsWith(normalized) -> {
                // Bỏ qua chunk trùng lặp
            }
            else -> {
                val lastChar = responseTextBuffer[responseTextBuffer.length - 1]
                val firstChar = normalized.first()
                val shouldAddSpace =
                    !lastChar.isWhitespace() &&
                        !firstChar.isWhitespace() &&
                        firstChar !in listOf('.', ',', '!', '?', ';', ':')
                if (shouldAddSpace) responseTextBuffer.append(' ')
                responseTextBuffer.append(normalized)
            }
        }
    }

    private fun flushResponseText() {
        val message = responseTextBuffer.toString().trim()
        responseTextBuffer.clear()
        if (message.isNotBlank()) {
            onTextReceived(message)
        }
    }

    private fun formatApiError(errorObj: JSONObject?, raw: String): String {
        if (errorObj == null) {
            return "API trả về lỗi không rõ định dạng: $raw"
        }

        val code = errorObj.optInt("code", -1)
        val status = errorObj.optString("status", "UNKNOWN")
        val message = errorObj.optString("message", errorObj.toString())

        val hint = when {
            code == 401 || message.contains("API key", ignoreCase = true) -> {
                "Kiểm tra GEMINI_API_KEY trong local.properties và bật Gemini API cho project key này."
            }
            code == 403 || message.contains("permission", ignoreCase = true) -> {
                "API key chưa có quyền truy cập Live API hoặc bị giới hạn theo app/package."
            }
            code == 404 || message.contains("model", ignoreCase = true) -> {
                "Model Live không khả dụng với key hiện tại, hãy đổi sang model Live khác trong cấu hình."
            }
            code == 429 -> {
                "Bạn đang vượt hạn mức/rate limit của Gemini API."
            }
            else -> {
                "Kiểm tra lại cấu hình setup của Live API và thử lại sau."
            }
        }

        return "API Gemini lỗi ($code/$status): $message\nGợi ý: $hint"
    }

    // ── Disconnect ─────────────────────────────────────────────
    fun disconnect() {
        ws?.close(1000, "Session ended")
        ws = null
        pendingTexts.clear()
        responseTextBuffer.clear()
        activityStarted = false
        pendingActivityStart = false
        _state.value = State.DISCONNECTED
    }

    private fun buildSystemInstructionText(): String {
        val ctx = liveAddressContextProvider()
        val currentLocation = ctx.currentLocation?.takeIf { it.isNotBlank() } ?: "khong co"
        val homeAddress = ctx.homeAddress?.takeIf { it.isNotBlank() } ?: "khong co"
        val workAddress = ctx.workAddress?.takeIf { it.isNotBlank() } ?: "khong co"

        return """
Ban la Tro ly Traffic AI cua UTT. Luon tra loi ngan gon, de hieu bang tieng Viet.

Du lieu dia chi tinh cua nguoi dung:
- current_location: $currentLocation
- home_address: $homeAddress
- work_address: $workAddress

QUY TAC BAT BUOC:
- Neu prompt co block [DU_LIEU_TRAFFIC_DA_XU_LY] thi dung du lieu trong block de tra loi va KHONG goi tool check_traffic lan nua.
- Neu nguoi dung hoi duong di, thoi gian di chuyen, hoac co tac duong khong thi BAT BUOC goi tool check_traffic truoc khi tra loi.
- Neu nguoi dung yeu cau chi duong/mo ban do/mo Google Maps/di den mot noi cu the thi BAT BUOC goi tool open_google_maps de mo dan duong.
- Neu nguoi dung hoi luat giao thong/muc phat/vi pham thi uu tien van ban phap luat dang co hieu luc moi nhat. Neu khong chac thong tin chi tiet thi phai noi ro can doi chieu van ban moi nhat, khong duoc tu doan.
- Chi sau khi da nhan duoc ket qua tool moi duoc tong hop cau tra loi.
""".trimIndent()
    }

    private fun buildCheckTrafficFunctionDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "check_traffic")
            put("description", "Kiem tra lo trinh va trang thai tac duong giua diem di va diem den")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("origin", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Diem xuat phat")
                    })
                    put("destination", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Diem den")
                    })
                })
                put("required", JSONArray().apply {
                    put("origin")
                    put("destination")
                })
            })
        }
    }

    private fun buildOpenGoogleMapsFunctionDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "open_google_maps")
            put("description", "Mo Google Maps va hien thi lo trinh tu diem di den diem den")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("origin", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Diem xuat phat, co the bo trong de dung vi tri hien tai")
                    })
                    put("destination", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Diem den can dan duong")
                    })
                    put("travel_mode", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "driving|walking|bicycling|transit")
                    })
                })
                put("required", JSONArray().apply {
                    put("destination")
                })
            })
        }
    }

    private data class ToolCallPayload(
        val id: String,
        val name: String,
        val args: JSONObject
    )

    private fun isSupportedTool(name: String): Boolean {
        return name == "check_traffic" || name == "open_google_maps"
    }

    private fun handleToolCallFromServer(root: JSONObject): Boolean {
        val candidates = listOf(
            root.optJSONObject("toolCall"),
            root.optJSONObject("toolCallMessage"),
            root.optJSONObject("serverContent")?.optJSONObject("toolCall"),
            root.optJSONObject("serverContent")?.optJSONObject("toolCallMessage")
        )

        var handled = false
        for (candidate in candidates) {
            val functionCalls = candidate?.optJSONArray("functionCalls") ?: continue
            for (i in 0 until functionCalls.length()) {
                val payload = parseToolCall(functionCalls.optJSONObject(i)) ?: continue
                if (!isSupportedTool(payload.name)) continue

                handled = true
                sessionScope.launch {
                    processToolCall(payload)
                }
            }
        }

        // Fallback format: serverContent.modelTurn.parts[*].functionCall
        val parts = root.optJSONObject("serverContent")
            ?.optJSONObject("modelTurn")
            ?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val functionCallObj = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
                val payload = parseToolCall(functionCallObj) ?: continue
                if (!isSupportedTool(payload.name)) continue

                handled = true
                sessionScope.launch {
                    processToolCall(payload)
                }
            }
        }

        return handled
    }

    private fun parseToolCall(obj: JSONObject?): ToolCallPayload? {
        if (obj == null) return null

        val id = obj.optString("id", "")
        val name = obj.optString("name", "")
        val argsObject = when {
            obj.has("args") && obj.optJSONObject("args") != null -> obj.optJSONObject("args") ?: JSONObject()
            obj.has("arguments") && obj.optJSONObject("arguments") != null -> obj.optJSONObject("arguments") ?: JSONObject()
            obj.has("args") && obj.optString("args").isNotBlank() -> {
                runCatching { JSONObject(obj.optString("args")) }.getOrElse { JSONObject() }
            }
            obj.has("arguments") && obj.optString("arguments").isNotBlank() -> {
                runCatching { JSONObject(obj.optString("arguments")) }.getOrElse { JSONObject() }
            }
            else -> JSONObject()
        }

        if (name.isBlank()) {
            return null
        }

        return ToolCallPayload(
            id = id,
            name = name,
            args = argsObject
        )
    }

    private suspend fun processToolCall(call: ToolCallPayload) {
        when (call.name) {
            "check_traffic" -> processCheckTrafficToolCall(call)
            "open_google_maps" -> processOpenGoogleMapsToolCall(call)
            else -> Unit
        }
    }

    private suspend fun processCheckTrafficToolCall(call: ToolCallPayload) {
        val origin = call.args.optString("origin", "").trim()
        val destination = call.args.optString("destination", "").trim()

        if (origin.isBlank() || destination.isBlank()) {
            sendToolResponse(
                call,
                JSONObject().apply {
                    put("error", "missing_required_args")
                    put("required", JSONArray().apply {
                        put("origin")
                        put("destination")
                    })
                }
            )
            return
        }

        val responsePayload = try {
            val toolResponse = trafficToolService.checkTraffic(
                TrafficToolRequest(
                    origin = origin,
                    destination = destination
                )
            )

            JSONObject().apply {
                put("route_name", toolResponse.routeName)
                put("traffic_status", toolResponse.trafficStatus)
                put("duration", toolResponse.duration)
                put("is_shortest", toolResponse.isShortest)
                put("should_reroute", toolResponse.shouldReroute)
                if (!toolResponse.recommendedRouteName.isNullOrBlank()) {
                    put("recommended_route", toolResponse.recommendedRouteName)
                }
                if (!toolResponse.recommendedDuration.isNullOrBlank()) {
                    put("recommended_duration", toolResponse.recommendedDuration)
                }
                if (!toolResponse.recommendationReason.isNullOrBlank()) {
                    put("recommendation_reason", toolResponse.recommendationReason)
                }
                if (toolResponse.alternatives.isNotEmpty()) {
                    put("alternatives", JSONArray().apply {
                        toolResponse.alternatives.forEach { alt ->
                            put(JSONObject().apply {
                                put("name", alt.name)
                                put("duration", alt.duration)
                                put("is_shortest", alt.isShortest)
                                put("is_primary", alt.isPrimary)
                            })
                        }
                    })
                }
                put("data_source", toolResponse.source)
                if (!toolResponse.sourceNote.isNullOrBlank()) {
                    put("source_note", toolResponse.sourceNote)
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("route_name", "khong xac dinh")
                put("traffic_status", false)
                put("duration", "khong xac dinh")
                put("is_shortest", false)
                put("error", e.message ?: "tool_failed")
            }
        }

        sendToolResponse(call, responsePayload)
    }

    private suspend fun processOpenGoogleMapsToolCall(call: ToolCallPayload) {
        val origin = call.args.optString("origin", "").trim().ifBlank { null }
        val destination = call.args.optString("destination", "").trim()
        val travelModeRaw = call.args
            .optString("travel_mode", call.args.optString("mode", "driving"))
            .trim()
            .lowercase(Locale.US)
        val travelMode = when (travelModeRaw) {
            "walking", "walk" -> "walking"
            "bicycling", "bicycle", "bike" -> "bicycling"
            "transit", "public_transport" -> "transit"
            else -> "driving"
        }

        if (destination.isBlank()) {
            sendToolResponse(
                call,
                JSONObject().apply {
                    put("status", "failed")
                    put("error", "missing_required_args")
                    put("required", JSONArray().apply {
                        put("destination")
                    })
                }
            )
            return
        }

        val opened = runCatching {
            onOpenGoogleMapsRequested(origin, destination, travelMode)
        }.getOrElse { false }

        val responsePayload = JSONObject().apply {
            put("status", if (opened) "opened" else "failed")
            put("destination", destination)
            put("travel_mode", travelMode)
            if (!origin.isNullOrBlank()) {
                put("origin", origin)
            }
            if (!opened) {
                put("error", "cannot_open_google_maps")
            }
        }

        sendToolResponse(call, responsePayload)
    }

    private fun sendToolResponse(call: ToolCallPayload, payload: JSONObject) {
        val msg = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().apply {
                    put(JSONObject().apply {
                        if (call.id.isNotBlank()) {
                            put("id", call.id)
                        }
                        put("name", call.name)
                        put("response", payload)
                    })
                })
            })
        }

        val sent = ws?.send(msg.toString()) == true
        if (!sent) {
            onError("Khong gui duoc ToolResponse cho ${call.name} vi WebSocket khong san sang.")
        }
    }
}
