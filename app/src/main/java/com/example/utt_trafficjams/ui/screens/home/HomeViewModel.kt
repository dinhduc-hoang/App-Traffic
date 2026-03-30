package com.example.utt_trafficjams.ui.screens.home

import android.annotation.SuppressLint
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.utt_trafficjams.BuildConfig
import com.example.utt_trafficjams.ai.AudioPlayer
import com.example.utt_trafficjams.ai.AudioRecorder
import com.example.utt_trafficjams.ai.GeminiLiveSession
import com.example.utt_trafficjams.ai.LiveAddressContext
import com.example.utt_trafficjams.ai.MapboxDirectionsTrafficToolService
import com.example.utt_trafficjams.ai.TrafficAlternativeRoute
import com.example.utt_trafficjams.ai.TrafficToolRequest
import com.example.utt_trafficjams.data.model.ChatMessage
import com.example.utt_trafficjams.data.model.RoutePlaceType
import com.example.utt_trafficjams.data.model.TrafficSchedule
import com.example.utt_trafficjams.data.repository.ChatRepository
import com.example.utt_trafficjams.data.repository.TrafficScheduleRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import java.util.Locale

data class GoogleMapsLaunchRequest(
    val origin: String?,
    val destination: String,
    val travelMode: String
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository()
    private val scheduleRepository = TrafficScheduleRepository(getApplication())
    private val trafficToolService = MapboxDirectionsTrafficToolService(
        accessToken = BuildConfig.MAPBOX_API_KEY
    )
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(getApplication<Application>())
    }

    private val _messages     = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading    = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isListening  = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isChatReady = MutableStateFlow(true)
    val isChatReady: StateFlow<Boolean> = _isChatReady.asStateFlow()

    private val _openGoogleMapsRequests = MutableSharedFlow<GoogleMapsLaunchRequest>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val openGoogleMapsRequests: SharedFlow<GoogleMapsLaunchRequest> = _openGoogleMapsRequests

    // Gemini Live
    private var geminiSession: GeminiLiveSession? = null
    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null

    @Volatile
    private var lastAiAudioAtMs: Long = 0L
    @Volatile
    private var latestCurrentLocationText: String? = null
    @Volatile
    private var latestCurrentLocationLatLng: Pair<Double, Double>? = null
    @Volatile
    private var latestCurrentLocationUpdatedAtMs: Long = 0L
    @Volatile
    private var latestCurrentLocationWallTimeMs: Long = 0L
    private val locationFreshWindowMs = 2 * 60 * 1000L
    private val micUplinkPauseAfterAiMs = 450L

    private enum class RouteTarget { HOME, WORK, ANY }

    private data class TrafficRoutePlan(
        val label: String,
        val origin: String,
        val destination: String
    )

    private data class TrafficRouteInsight(
        val label: String,
        val origin: String,
        val destination: String,
        val routeName: String,
        val trafficStatus: Boolean,
        val duration: String,
        val isShortest: Boolean,
        val shouldReroute: Boolean,
        val recommendedRouteName: String?,
        val recommendedDuration: String?,
        val recommendationReason: String?,
        val alternatives: List<TrafficAlternativeRoute>,
        val dataSource: String,
        val sourceNote: String? = null,
        val error: String? = null
    )

    init {
        observeMessages()
        setupGemini()
    }

    private fun setupGemini() {
        audioPlayer   = AudioPlayer()
        audioRecorder = AudioRecorder { pcm ->
            // Tránh feedback loop: khi AI đang phát, mic có thể thu lại tiếng loa và gửi ngược lên model.
            val now = SystemClock.elapsedRealtime()
            if (now - lastAiAudioAtMs < micUplinkPauseAfterAiMs) {
                return@AudioRecorder
            }
            geminiSession?.sendAudioChunk(pcm)
        }

        val apiKey = BuildConfig.GEMINI_API_KEY
        geminiSession = GeminiLiveSession(
            apiKey = apiKey,
            onAudioChunk = { pcm ->
                lastAiAudioAtMs = SystemClock.elapsedRealtime()
                audioPlayer?.play()
                audioPlayer?.write(pcm) // Phát audio từ Gemini
            },
            onTextReceived = { text ->
                // Tin nhắn AI từ Gemini -> Lưu local
                viewModelScope.launch {
                    repository.sendMessage(text, "ai")
                }
            },
            onConnected = { _ ->
                // Giữ im lặng trên UI, không đẩy log kỹ thuật vào khung chat.
            },
            onDisconnected = { reason ->
                lastAiAudioAtMs = 0L
                _isListening.value = false
                audioRecorder?.stopRecording()
                viewModelScope.launch {
                    repository.sendMessage("🔌 Ngắt kết nối: $reason", "ai")
                }
            },
            onError = { errMsg ->
                lastAiAudioAtMs = 0L
                _isListening.value = false
                audioRecorder?.stopRecording()
                viewModelScope.launch {
                    repository.sendMessage("⚠️ Lỗi Hệ Thống: $errMsg", "ai")
                }
            },
            liveAddressContextProvider = { buildLiveAddressContext() },
            trafficToolService = trafficToolService,
            onOpenGoogleMapsRequested = { origin, destination, travelMode ->
                _openGoogleMapsRequests.tryEmit(
                    GoogleMapsLaunchRequest(
                        origin = origin,
                        destination = destination,
                        travelMode = travelMode
                    )
                )
            }
        )
    }

    private fun observeMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.observeMessages()
                .catch { _isLoading.value = false }
                .collect { msgs ->
                    _messages.value = msgs
                    _isLoading.value = false
                    _isChatReady.value = repository.isReady
                }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val normalized = text.trim()

            // Lưu tin nhắn người dùng để hiển thị trên chat
            repository.sendMessage(normalized, "user")

            val trafficTargets = detectTrafficTargets(normalized)
            val needsCurrentLocation = trafficTargets != null || shouldAttachRouteContextToGemini(normalized)
            val mandatoryCurrentLocation = if (needsCurrentLocation) {
                resolveMandatoryCurrentLocationOrNotify()
            } else {
                null
            }

            if (needsCurrentLocation && mandatoryCurrentLocation == null) {
                return@launch
            }

            val baseMessageForGemini = if (trafficTargets != null) {
                buildTrafficFirstPrompt(
                    userText = normalized,
                    targets = trafficTargets,
                    mandatoryCurrentLocation = mandatoryCurrentLocation
                )
            } else if (shouldAttachLegalContext(normalized)) {
                buildLegalAwarePrompt(normalized)
            } else if (shouldAttachRouteContextToGemini(normalized)) {
                buildRouteAwarePrompt(
                    userText = normalized,
                    mandatoryCurrentLocation = mandatoryCurrentLocation
                )
            } else {
                normalized
            }

            val gpsContextText = resolveCurrentLocationContextForAllRequests(
                preferred = mandatoryCurrentLocation
            )
            val promptWithGps = wrapWithGlobalGpsContext(
                basePrompt = baseMessageForGemini,
                gpsContextText = gpsContextText
            )
            val messageForGemini = wrapWithGlobalRouteSnapshotContext(
                basePrompt = promptWithGps,
                userText = normalized
            )

            // Gửi đi bằng Gemini. Nếu chưa connected, session sẽ tự queue và gửi sau setupComplete.
            if (geminiSession?.state?.value != GeminiLiveSession.State.CONNECTED) {
                geminiSession?.connect()
            }
            geminiSession?.sendText(messageForGemini)
        }
    }

    fun toggleListening() {
        if (_isListening.value) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (_isListening.value) return
        _isListening.value = true
        if (geminiSession?.state?.value != GeminiLiveSession.State.CONNECTED) {
            geminiSession?.connect()
        }
        geminiSession?.sendActivityStart()
        audioPlayer?.play()
        audioRecorder?.startRecording(viewModelScope)
    }

    private fun stopListening() {
        _isListening.value = false
        lastAiAudioAtMs = 0L
        audioRecorder?.stopRecording()
        geminiSession?.sendActivityEnd()
        geminiSession?.sendAudioStreamEnd()
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder?.stopRecording()
        geminiSession?.disconnect()
        audioPlayer?.release()
    }

    fun refreshCurrentLocationForAi() {
        viewModelScope.launch {
            if (!hasLocationPermission()) return@launch
            if (!isLocationServiceEnabled()) return@launch
            getCurrentLocationLatLng()
        }
    }

    fun updateCurrentLocationFromMap(lat: Double, lng: Double) {
        latestCurrentLocationLatLng = Pair(lat, lng)
        latestCurrentLocationText = formatLatLng(lat, lng)
        latestCurrentLocationUpdatedAtMs = SystemClock.elapsedRealtime()
        latestCurrentLocationWallTimeMs = System.currentTimeMillis()
    }

    private fun getFreshCachedLocationOrNull(): Pair<Double, Double>? {
        val updatedAt = latestCurrentLocationUpdatedAtMs
        if (updatedAt <= 0L) return null
        val ageMs = SystemClock.elapsedRealtime() - updatedAt
        return if (ageMs <= locationFreshWindowMs) latestCurrentLocationLatLng else null
    }

    private fun getAnyCachedLocationOrNull(): Pair<Double, Double>? {
        return latestCurrentLocationLatLng
    }

    private suspend fun resolveCurrentLocationContextForAllRequests(preferred: Pair<Double, Double>?): String {
        if (preferred != null) {
            return formatLatLng(preferred.first, preferred.second)
        }

        if (hasLocationPermission() && isLocationServiceEnabled()) {
            val current = getCurrentLocationLatLng()
            if (current != null) {
                return formatLatLng(current.first, current.second)
            }
        }

        val cached = getFreshCachedLocationOrNull() ?: getAnyCachedLocationOrNull()
        if (cached != null) {
            return formatLatLng(cached.first, cached.second)
        }

        return "không có (chưa lấy được GPS hiện tại)"
    }

    private fun wrapWithGlobalGpsContext(basePrompt: String, gpsContextText: String): String {
        val locationAgeNote = buildLocationAgeNote()
        return """
[GPS_CONTEXT]
- vi_tri_hien_tai: $gpsContextText
- tinh_trang_du_lieu: $locationAgeNote
[/GPS_CONTEXT]

$basePrompt
""".trimIndent()
    }

    private fun wrapWithGlobalRouteSnapshotContext(basePrompt: String, userText: String): String {
        val homeSchedule = inferScheduleForTarget(RouteTarget.HOME)
        val workSchedule = inferScheduleForTarget(RouteTarget.WORK)
        val schoolSchedule = inferSchoolScheduleForQuery(userText)

        val homeText = homeSchedule?.let { formatScheduleForPrompt(it) } ?: "không có"
        val workText = workSchedule?.let { formatScheduleForPrompt(it) } ?: "không có"
        val schoolText = schoolSchedule?.let { formatScheduleForPrompt(it) } ?: "không có"

        return """
[ROUTE_SNAPSHOT]
- du_lieu: cap_nhat_tu_manh_hinh_lo_trinh
- nha: $homeText
- co_quan: $workText
- truong: $schoolText
[/ROUTE_SNAPSHOT]

$basePrompt
""".trimIndent()
    }

    private fun buildLocationAgeNote(): String {
        val updatedAt = latestCurrentLocationUpdatedAtMs
        if (updatedAt <= 0L) return "chua_co_du_lieu"
        val ageSeconds = ((SystemClock.elapsedRealtime() - updatedAt) / 1000L).coerceAtLeast(0L)
        return if (ageSeconds <= 30L) {
            "moi_cap_nhat"
        } else {
            "du_lieu_cach_day_${ageSeconds}_giay"
        }
    }

    private fun detectTrafficTargets(message: String): Set<RouteTarget>? {
        val norm = normalizeForIntent(message)
        val asksTraffic = containsAnyNormalized(
            norm,
            listOf(
                "tắc", "kẹt xe", "ùn tắc", "đông xe", "traffic", "có tắc", "tắc không",
                "đường có đông", "đường có tắc", "bây giờ có tắc", "giờ có tắc", "tắc ko", "có tắc ko"
            )
        )
        if (!asksTraffic) return null

        val asksHome = containsAnyNormalized(
            norm,
            listOf("về nhà", "đường về nhà", "nhà", "home", "trở về nhà", "về đến nhà", "nơi ở")
        )
        val asksWork = containsAnyNormalized(
            norm,
            listOf("đi làm", "đến công ty", "đến trường", "work", "đi học", "cơ quan", "văn phòng", "nơi làm")
        )

        return when {
            asksHome && asksWork -> setOf(RouteTarget.HOME, RouteTarget.WORK)
            asksHome -> setOf(RouteTarget.HOME)
            asksWork -> setOf(RouteTarget.WORK)
            else -> setOf(RouteTarget.ANY)
        }
    }

    private fun shouldAttachRouteContextToGemini(message: String): Boolean {
        val norm = normalizeForIntent(message)

        val routePhrases = listOf(
            "chỉ đường", "hỏi đường", "duong di", "lộ trình", "dẫn đường", "route",
            "google map", "google maps", "ban do", "maps", "map", "đi đến",
            "từ đây đến", "từ đây tới", "tới", "đến nhà", "đến công ty", "đến cơ quan",
            "về nhà như nào", "đi công ty như nào", "đi cơ quan như nào", "đường nào nhanh",
            "đến trường", "đi học", "trường nào", "tới trường"
        )

        return containsAnyNormalized(norm, routePhrases)
    }

    private fun shouldAttachLegalContext(message: String): Boolean {
        val norm = normalizeForIntent(message)
        val legalPhrases = listOf(
            "luật", "pháp luật", "nghị định", "thông tư", "quy định", "điều khoản",
            "mức phạt", "xử phạt", "phạt bao nhiêu", "bị phạt", "vi phạm",
            "nộp phạt", "tước bằng", "gplx", "nồng độ cồn", "đèn đỏ", "vượt đèn đỏ"
        )
        return containsAnyNormalized(norm, legalPhrases)
    }

    private fun buildLegalAwarePrompt(userText: String): String {
        return """
[NGU_CANH_PHAP_LY_CAP_NHAT]
- yeu_cau_nguoi_dung: $userText

Quy tắc bắt buộc khi trả lời:
1) Chỉ sử dụng thông tin pháp lý giao thông theo văn bản đang có hiệu lực mới nhất.
2) Không dùng thông tin đã hết hiệu lực hoặc đã bị sửa đổi nếu không đối chiếu tình trạng hiệu lực.
3) Nếu không chắc về điều, khoản, mức tiền phạt cụ thể thì phải nói rõ cần đối chiếu văn bản mới nhất, không được đoán số liệu.
4) Trả lời ngắn gọn, rõ ràng, bằng tiếng Việt.
[/NGU_CANH_PHAP_LY_CAP_NHAT]
""".trimIndent()
    }

    private suspend fun buildRouteAwarePrompt(
        userText: String,
        mandatoryCurrentLocation: Pair<Double, Double>?
    ): String {
        val current = mandatoryCurrentLocation
        val homeSchedule = inferScheduleForTargetWithQuery(RouteTarget.HOME, userText)
        val workSchedule = inferScheduleForTargetWithQuery(RouteTarget.WORK, userText)
        val schoolSchedule = inferSchoolScheduleForQuery(userText)

        val currentText = if (current != null) {
            formatLatLng(current.first, current.second)
        } else {
            "không có (chưa cấp quyền vị trí hoặc không lấy được GPS)"
        }

        val homeText = if (homeSchedule != null) {
            formatScheduleForPrompt(homeSchedule)
        } else {
            "không có (chưa cấu hình lịch trình nhà)"
        }

        val workText = if (workSchedule != null) {
            formatScheduleForPrompt(workSchedule)
        } else {
            "không có (chưa cấu hình lịch trình cơ quan)"
        }

        val schoolText = if (schoolSchedule != null) {
            formatScheduleForPrompt(schoolSchedule)
        } else {
            "không có (chưa cấu hình trường trong lộ trình)"
        }

        return """
[NGU_CANH_NOI_BO_KHONG_DOC_LAI_NGUYEN_VAN]
- vi_tri_hien_tai: $currentText
- vi_tri_nha: $homeText
- vi_tri_co_quan: $workText
- vi_tri_truong: $schoolText

Hướng dẫn bắt buộc:
1) Chỉ sử dụng dữ liệu vị trí ở trên nếu câu hỏi liên quan đến chỉ đường, lộ trình, đường đi, điểm đến nhà/cơ quan.
2) Nếu câu hỏi không cần đến vị trí thì bỏ qua ngữ cảnh này và trả lời như bình thường.
3) Không tự ý liệt kê tọa độ nếu người dùng không yêu cầu rõ ràng.
4) Nếu câu hỏi có từ "trường" hoặc "đi học" thì ưu tiên vi_tri_truong.
5) Nếu người dùng đang yêu cầu chỉ đường/mở bản đồ thì BẮT BUỘC gọi tool open_google_maps.
[/NGU_CANH_NOI_BO_KHONG_DOC_LAI_NGUYEN_VAN]

Câu hỏi người dùng: $userText
""".trimIndent()
    }

    private fun formatLatLng(lat: Double, lng: Double): String {
        return "lat=${"%.6f".format(Locale.US, lat)}, lng=${"%.6f".format(Locale.US, lng)}"
    }

    private fun formatScheduleForPrompt(schedule: TrafficSchedule): String {
        val address = if (schedule.destinationAddress.isNotBlank()) {
            schedule.destinationAddress
        } else {
            formatLatLng(schedule.destinationLat, schedule.destinationLng)
        }
        return "${schedule.actionName}: $address"
    }

    private fun buildLiveAddressContext(): LiveAddressContext {
        val homeSchedule = inferScheduleForTarget(RouteTarget.HOME)
        val workSchedule = inferScheduleForTarget(RouteTarget.WORK)

        val homeAddress = when {
            homeSchedule == null -> null
            homeSchedule.destinationAddress.isNotBlank() -> homeSchedule.destinationAddress
            else -> formatLatLng(homeSchedule.destinationLat, homeSchedule.destinationLng)
        }

        val workAddress = when {
            workSchedule == null -> null
            workSchedule.destinationAddress.isNotBlank() -> workSchedule.destinationAddress
            else -> formatLatLng(workSchedule.destinationLat, workSchedule.destinationLng)
        }

        return LiveAddressContext(
            currentLocation = latestCurrentLocationText,
            homeAddress = homeAddress,
            workAddress = workAddress
        )
    }

    private suspend fun buildTrafficFirstPrompt(
        userText: String,
        targets: Set<RouteTarget>,
        mandatoryCurrentLocation: Pair<Double, Double>?
    ): String {
        val current = mandatoryCurrentLocation
        val currentOrigin = current?.let { formatLatLng(it.first, it.second) } ?: latestCurrentLocationText
        val plans = buildTrafficPlans(targets, currentOrigin, userText)

        if (plans.isEmpty()) {
            return """
[DU_LIEU_TRAFFIC_DA_XU_LY]
- yeu_cau_nguoi_dung: $userText
- ket_luan_he_thong: Không tìm thấy lộ trình nhà/cơ quan/trường để kiểm tra giao thông.

Hướng dẫn trả lời:
1) Báo người dùng hiện chưa có dữ liệu lộ trình nhà/cơ quan/trường.
2) Gợi ý người dùng vào màn hình Routes để nhập địa điểm cần đến.
3) Trả lời ngắn gọn, dễ hiểu.
[/DU_LIEU_TRAFFIC_DA_XU_LY]
""".trimIndent()
        }

        val insights = plans.map { plan ->
            runCatching {
                trafficToolService.checkTraffic(
                    TrafficToolRequest(
                        origin = plan.origin,
                        destination = plan.destination
                    )
                )
            }.fold(
                onSuccess = { result ->
                    TrafficRouteInsight(
                        label = plan.label,
                        origin = plan.origin,
                        destination = plan.destination,
                        routeName = result.routeName,
                        trafficStatus = result.trafficStatus,
                        duration = result.duration,
                        isShortest = result.isShortest,
                        shouldReroute = result.shouldReroute,
                        recommendedRouteName = result.recommendedRouteName,
                        recommendedDuration = result.recommendedDuration,
                        recommendationReason = result.recommendationReason,
                        alternatives = result.alternatives,
                        dataSource = result.source,
                        sourceNote = result.sourceNote
                    )
                },
                onFailure = { ex ->
                    TrafficRouteInsight(
                        label = plan.label,
                        origin = plan.origin,
                        destination = plan.destination,
                        routeName = "không xác định",
                        trafficStatus = false,
                        duration = "không xác định",
                        isShortest = false,
                        shouldReroute = false,
                        recommendedRouteName = null,
                        recommendedDuration = null,
                        recommendationReason = null,
                        alternatives = emptyList(),
                        dataSource = "error",
                        sourceNote = ex.message,
                        error = ex.message ?: "tool_failed"
                    )
                }
            )
        }

        val trafficBlock = insights.joinToString("\n") { insight ->
            val trafficText = if (insight.trafficStatus) "tắc" else "thông thoáng"
            val errorPart = insight.error?.let { " | error=$it" } ?: ""
            val sourceNotePart = insight.sourceNote?.takeIf { it.isNotBlank() }?.let { " | source_note=$it" } ?: ""
            val altPart = if (insight.alternatives.isEmpty()) {
                ""
            } else {
                val altText = insight.alternatives.joinToString(";") { alt ->
                    "${alt.name},${alt.duration},primary=${alt.isPrimary},shortest=${alt.isShortest}"
                }
                " | alternatives=$altText"
            }
            val recommendPart = if (insight.recommendedRouteName.isNullOrBlank()) {
                ""
            } else {
                " | recommended_route=${insight.recommendedRouteName} | recommended_duration=${insight.recommendedDuration ?: "không xác định"} | should_reroute=${insight.shouldReroute}"
            }
            val reasonPart = insight.recommendationReason?.let { " | recommendation_reason=$it" } ?: ""
            "- muc_tieu=${insight.label} | origin=${insight.origin} | destination=${insight.destination} | route_name=${insight.routeName} | traffic_status=$trafficText | duration=${insight.duration} | is_shortest=${insight.isShortest} | data_source=${insight.dataSource}$recommendPart$reasonPart$altPart$sourceNotePart$errorPart"
        }

        return """
[DU_LIEU_TRAFFIC_DA_XU_LY]
- yeu_cau_nguoi_dung: $userText
- ket_qua_kiem_tra:
$trafficBlock

Hướng dẫn trả lời:
1) Chỉ dựa trên ket_qua_kiem_tra ở trên để trả lời.
2) Nếu người dùng hỏi nhà/cơ quan, ưu tiên lộ trình đúng mục tiêu.
3) Nếu should_reroute=true thì ưu tiên đề xuất recommended_route thay vì route_name hiện tại.
4) Nếu có tắc đường, nêu rõ mức độ và nói rõ lý do đề xuất đổi tuyến.
5) Nếu alternatives có dữ liệu, có thể nêu tối đa 1-2 tuyến để người dùng chọn.
6) Nếu data_source=mock_fallback hoặc data_source=mock thì phải báo rõ ràng đây là dữ liệu ước tính.
7) Nếu người dùng cần ETA chính xác theo thời điểm hiện tại, gợi ý mở Google Maps để xem thời gian thực tế.
8) Trả lời ngắn gọn, dễ hiểu, bằng tiếng Việt.
9) Nếu yêu cầu người dùng có chỉ đường/mở bản đồ thì BẮT BUỘC gọi tool open_google_maps.
[/DU_LIEU_TRAFFIC_DA_XU_LY]
""".trimIndent()
    }

    private fun buildTrafficPlans(targets: Set<RouteTarget>, currentOrigin: String?, userText: String): List<TrafficRoutePlan> {
        val plans = mutableListOf<TrafficRoutePlan>()

        fun destinationOf(schedule: TrafficSchedule): String {
            return if (schedule.destinationAddress.isNotBlank()) {
                schedule.destinationAddress
            } else {
                formatLatLng(schedule.destinationLat, schedule.destinationLng)
            }
        }

        fun originOf(schedule: TrafficSchedule): String {
            return currentOrigin ?: formatLatLng(schedule.originLat, schedule.originLng)
        }

        if (RouteTarget.HOME in targets) {
            inferScheduleForTargetWithQuery(RouteTarget.HOME, userText)?.let { s ->
                plans += TrafficRoutePlan(
                    label = "ve_nha",
                    origin = originOf(s),
                    destination = destinationOf(s)
                )
            }
        }

        if (RouteTarget.WORK in targets) {
            inferScheduleForTargetWithQuery(RouteTarget.WORK, userText)?.let { s ->
                plans += TrafficRoutePlan(
                    label = "di_co_quan",
                    origin = originOf(s),
                    destination = destinationOf(s)
                )
            }
        }

        if (RouteTarget.ANY in targets) {
            inferScheduleForTarget(RouteTarget.ANY)?.let { s ->
                plans += TrafficRoutePlan(
                    label = "lo_trinh_gan_nhat",
                    origin = originOf(s),
                    destination = destinationOf(s)
                )
            }
        }

        return plans
    }

    private suspend fun resolveMandatoryCurrentLocationOrNotify(): Pair<Double, Double>? {
        val freshCachedLocation = getFreshCachedLocationOrNull()
        val anyCachedLocation = getAnyCachedLocationOrNull()

        if (!hasLocationPermission()) {
            if (freshCachedLocation != null) {
                return freshCachedLocation
            }
            if (anyCachedLocation != null) {
                return anyCachedLocation
            }
            repository.sendMessage(
                "Mình cần quyền vị trí để kiểm tra từ nơi bạn đang đứng. Hãy cấp quyền Vị trí cho app rồi hỏi lại nhé.",
                "ai"
            )
            return null
        }

        if (!isLocationServiceEnabled()) {
            if (freshCachedLocation != null) {
                return freshCachedLocation
            }
            if (anyCachedLocation != null) {
                return anyCachedLocation
            }
            repository.sendMessage(
                "Mình chưa đọc được GPS vì dịch vụ vị trí trên máy đang tắt. Bạn bật Location (Use location) rồi thử lại nhé.",
                "ai"
            )
            return null
        }

        val current = getCurrentLocationLatLng() ?: freshCachedLocation ?: anyCachedLocation
        if (current == null) {
            repository.sendMessage(
                "Mình chưa lấy được tọa độ hiện tại dù GPS đã bật. Bạn thử ra khu vực thoáng hơn hoặc bấm nút My Location trên bản đồ rồi hỏi lại.",
                "ai"
            )
            return null
        }

        return current
    }

    private fun inferScheduleForTargetWithQuery(target: RouteTarget, userText: String): TrafficSchedule? {
        val schedules = scheduleRepository.getSchedules().filter { it.enabled }
        if (schedules.isEmpty()) return null

        val normQuery = normalizeForIntent(userText)
        val asksSchool = listOf("trường", "đi học", "học").any { normQuery.contains(normalizeForIntent(it)) }

        if (target == RouteTarget.WORK && asksSchool) {
            val schoolSchedule = inferSchoolScheduleForQuery(userText)
            if (schoolSchedule != null) return schoolSchedule
        }

        val queryMatched = findBestScheduleByQuery(target, normQuery, schedules)
        if (queryMatched != null) return queryMatched

        return inferScheduleForTarget(target)
    }

    private fun inferSchoolScheduleForQuery(userText: String): TrafficSchedule? {
        val schedules = scheduleRepository.getSchedules().filter { it.enabled }
        if (schedules.isEmpty()) return null

        val normQuery = normalizeForIntent(userText)
        val asksSchool = listOf("trường", "đi học", "học").any { normQuery.contains(normalizeForIntent(it)) }
        if (!asksSchool) return null

        val schoolCandidates = schedules.filter { schedule ->
            val content = normalizeForIntent("${schedule.actionName} ${schedule.destinationAddress}")
            content.contains(normalizeForIntent("trường")) || content.contains(normalizeForIntent("học"))
        }
        if (schoolCandidates.isEmpty()) return null

        val exactMatched = schoolCandidates.firstOrNull { schedule ->
            val content = normalizeForIntent("${schedule.actionName} ${schedule.destinationAddress}")
            extractQueryTokens(normQuery).any { token -> token.length >= 4 && content.contains(token) }
        }
        return exactMatched ?: schoolCandidates.firstOrNull()
    }

    private fun findBestScheduleByQuery(
        target: RouteTarget,
        normQuery: String,
        schedules: List<TrafficSchedule>
    ): TrafficSchedule? {
        val candidates = when (target) {
            RouteTarget.HOME -> schedules.filter { it.placeType == RoutePlaceType.HOME }
            RouteTarget.WORK -> schedules.filter { it.placeType == RoutePlaceType.WORK }
            RouteTarget.ANY -> schedules
        }
        if (candidates.isEmpty()) return null

        val tokens = extractQueryTokens(normQuery)
        if (tokens.isEmpty()) return null

        return candidates
            .map { schedule ->
                val content = normalizeForIntent("${schedule.actionName} ${schedule.destinationAddress}")
                val score = tokens.count { token -> token.length >= 4 && content.contains(token) }
                schedule to score
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
            ?.first
    }

    private fun extractQueryTokens(normQuery: String): List<String> {
        val stopWords = setOf(
            "chi", "duong", "toi", "den", "di", "ve", "nha", "co", "quan", "cong", "ty",
            "o", "la", "nao", "the", "gio", "bay", "moi", "toi", "map", "maps", "google"
        )
        return normQuery
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in stopWords }
            .distinct()
    }

    private fun inferScheduleForTarget(target: RouteTarget): TrafficSchedule? {
        val schedules = scheduleRepository.getSchedules().filter { it.enabled }
        if (schedules.isEmpty()) return null

        if (target == RouteTarget.ANY) {
            return inferNearestSchedule(schedules)
        }

        val byPlaceType = when (target) {
            RouteTarget.HOME -> schedules.firstOrNull { it.placeType == RoutePlaceType.HOME }
            RouteTarget.WORK -> schedules.firstOrNull { it.placeType == RoutePlaceType.WORK }
            RouteTarget.ANY -> null
        }
        if (byPlaceType != null) return byPlaceType

        val matcher: (TrafficSchedule) -> Boolean = if (target == RouteTarget.HOME) {
            { s ->
                val name = normalizeForIntent(s.actionName)
                name.contains("nha") || name.contains("tan lam") || name.contains("ve nha") || name.contains("home")
            }
        } else {
            { s ->
                val name = normalizeForIntent(s.actionName)
                name.contains("di lam") ||
                    name.contains("cong ty") ||
                    name.contains("work") ||
                    name.contains("truong") ||
                    name.contains("co quan") ||
                    name.contains("van phong") ||
                    name.contains("noi lam")
            }
        }

        return schedules.firstOrNull(matcher)
            ?: if (target == RouteTarget.HOME) schedules.lastOrNull() else schedules.firstOrNull()
    }

    private fun inferNearestSchedule(schedules: List<TrafficSchedule>): TrafficSchedule? {
        if (schedules.isEmpty()) return null

        val now = java.util.Calendar.getInstance()
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)

        return schedules.minByOrNull { schedule ->
            val scheduleMinutes = schedule.hour * 60 + schedule.minute
            val direct = kotlin.math.abs(scheduleMinutes - currentMinutes)
            minOf(direct, 1440 - direct)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val app = getApplication<Application>()
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationServiceEnabled(): Boolean {
        val app = getApplication<Application>()
        val locationManager = app.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
            val networkEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
            gpsEnabled || networkEnabled
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocationLatLng(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null

        return try {
            val tokenHigh = CancellationTokenSource()
            val highAccuracy = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                tokenHigh.token
            ).await()

            if (highAccuracy != null) {
                latestCurrentLocationText = formatLatLng(highAccuracy.latitude, highAccuracy.longitude)
                latestCurrentLocationLatLng = Pair(highAccuracy.latitude, highAccuracy.longitude)
                latestCurrentLocationUpdatedAtMs = SystemClock.elapsedRealtime()
                latestCurrentLocationWallTimeMs = System.currentTimeMillis()
                Pair(highAccuracy.latitude, highAccuracy.longitude)
            } else {
                val last = fusedLocationClient.lastLocation.await()
                if (last != null) {
                    latestCurrentLocationText = formatLatLng(last.latitude, last.longitude)
                    latestCurrentLocationLatLng = Pair(last.latitude, last.longitude)
                    latestCurrentLocationUpdatedAtMs = SystemClock.elapsedRealtime()
                    latestCurrentLocationWallTimeMs = System.currentTimeMillis()
                    Pair(last.latitude, last.longitude)
                } else {
                    val tokenBalanced = CancellationTokenSource()
                    val balanced = fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        tokenBalanced.token
                    ).await()
                    balanced?.let {
                        latestCurrentLocationText = formatLatLng(it.latitude, it.longitude)
                        latestCurrentLocationLatLng = Pair(it.latitude, it.longitude)
                        latestCurrentLocationUpdatedAtMs = SystemClock.elapsedRealtime()
                        latestCurrentLocationWallTimeMs = System.currentTimeMillis()
                        Pair(it.latitude, it.longitude)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeForIntent(text: String): String {
        val lowered = text.lowercase()
        val normalized = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    private fun containsAnyNormalized(normText: String, phrases: List<String>): Boolean {
        return phrases.any { phrase ->
            normText.contains(normalizeForIntent(phrase))
        }
    }
}
