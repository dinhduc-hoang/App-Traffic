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
import com.example.utt_trafficjams.ai.GoogleDirectionsTrafficToolService
import com.example.utt_trafficjams.ai.LiveAddressContext
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
    private val trafficToolService = GoogleDirectionsTrafficToolService(
        apiKey = BuildConfig.MAPS_API_KEY
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

            // Lưu text user để hiện trên chat
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

            val messageForGemini = if (trafficTargets != null) {
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

    fun updateCurrentLocationFromMap(lat: Double, lng: Double) {
        latestCurrentLocationLatLng = Pair(lat, lng)
        latestCurrentLocationText = formatLatLng(lat, lng)
        latestCurrentLocationUpdatedAtMs = SystemClock.elapsedRealtime()
    }

    private fun getFreshCachedLocationOrNull(): Pair<Double, Double>? {
        val updatedAt = latestCurrentLocationUpdatedAtMs
        if (updatedAt <= 0L) return null
        val ageMs = SystemClock.elapsedRealtime() - updatedAt
        return if (ageMs <= locationFreshWindowMs) latestCurrentLocationLatLng else null
    }

    private fun detectTrafficTargets(message: String): Set<RouteTarget>? {
        val norm = normalizeForIntent(message)
        val asksTraffic = listOf(
            "tac", "ket xe", "un tac", "dong xe", "traffic", "co tac", "tac khong",
            "duong co dong", "duong co tac", "bay gio co tac", "gio co tac", "tac ko", "co tac ko"
        ).any { norm.contains(it) }
        if (!asksTraffic) return null

        val asksHome = listOf(
            "ve nha", "duong ve nha", "nha", "home", "tro ve nha", "ve den nha", "noi o"
        ).any { norm.contains(it) }
        val asksWork = listOf(
            "di lam", "den cong ty", "den truong", "work", "di hoc", "co quan", "van phong", "noi lam"
        ).any { norm.contains(it) }

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
            "chi duong", "hoi duong", "duong di", "lo trinh", "dan duong", "route",
            "google map", "google maps", "ban do", "maps", "map", "di den",
            "tu day den", "tu day toi", "toi", "den nha", "den cong ty", "den co quan",
            "ve nha nhu nao", "di cong ty nhu nao", "di co quan nhu nao", "duong nao nhanh"
        )

        return routePhrases.any { norm.contains(it) }
    }

    private fun shouldAttachLegalContext(message: String): Boolean {
        val norm = normalizeForIntent(message)
        val legalPhrases = listOf(
            "luat", "phap luat", "nghi dinh", "thong tu", "quy dinh", "dieu khoan",
            "muc phat", "xu phat", "phat bao nhieu", "bi phat", "vi pham",
            "nop phat", "tuoc bang", "gplx", "nong do con", "den do", "vuot den do"
        )
        return legalPhrases.any { norm.contains(it) }
    }

    private fun buildLegalAwarePrompt(userText: String): String {
        return """
[NGU_CANH_PHAP_LY_CAP_NHAT]
- yeu_cau_nguoi_dung: $userText

Quy tac bat buoc khi tra loi:
1) Chi su dung thong tin phap ly giao thong theo van ban dang co hieu luc moi nhat.
2) Khong dung thong tin da het hieu luc hoac da bi sua doi neu khong doi chieu tinh trang hieu luc.
3) Neu khong chac ve dieu, khoan, muc tien phat cu the thi phai noi ro can doi chieu van ban moi nhat, khong duoc doan so lieu.
4) Tra loi ngan gon, ro rang, bang tieng Viet.
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

        val currentText = if (current != null) {
            formatLatLng(current.first, current.second)
        } else {
            "khong co (chua cap quyen vi tri hoac khong lay duoc GPS)"
        }

        val homeText = if (homeSchedule != null) {
            "${homeSchedule.actionName}: ${formatLatLng(homeSchedule.destinationLat, homeSchedule.destinationLng)}"
        } else {
            "khong co (chua cau hinh lich trinh nha)"
        }

        val workText = if (workSchedule != null) {
            "${workSchedule.actionName}: ${formatLatLng(workSchedule.destinationLat, workSchedule.destinationLng)}"
        } else {
            "khong co (chua cau hinh lich trinh co quan)"
        }

        return """
[NGU_CANH_NOI_BO_KHONG_DOC_LAI_NGUYEN_VAN]
- vi_tri_hien_tai: $currentText
- vi_tri_nha: $homeText
- vi_tri_co_quan: $workText

Huong dan bat buoc:
1) Chi su dung du lieu vi tri o tren neu cau hoi lien quan den chi duong, lo trinh, duong di, diem den nha/co quan.
2) Neu cau hoi khong can den vi tri thi bo qua ngu canh nay va tra loi nhu binh thuong.
3) Khong tu y liet ke toa do neu nguoi dung khong yeu cau ro rang.
4) Neu nguoi dung dang yeu cau chi duong/mo ban do thi BAT BUOC goi tool open_google_maps.
[/NGU_CANH_NOI_BO_KHONG_DOC_LAI_NGUYEN_VAN]

Cau hoi nguoi dung: $userText
""".trimIndent()
    }

    private fun formatLatLng(lat: Double, lng: Double): String {
        return "lat=${"%.6f".format(Locale.US, lat)}, lng=${"%.6f".format(Locale.US, lng)}"
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
- ket_luan_he_thong: Khong tim thay lo trinh nha/co quan de kiem tra giao thong.

Huong dan tra loi:
1) Bao nguoi dung hien chua co du lieu lo trinh nha/co quan.
2) Goi y nguoi dung vao man hinh Routes de nhap dia diem nha/co quan.
3) Tra loi ngan gon, de hieu.
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
                        routeName = "khong xac dinh",
                        trafficStatus = false,
                        duration = "khong xac dinh",
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
            val trafficText = if (insight.trafficStatus) "tac" else "thong thoang"
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
                " | recommended_route=${insight.recommendedRouteName} | recommended_duration=${insight.recommendedDuration ?: "khong xac dinh"} | should_reroute=${insight.shouldReroute}"
            }
            val reasonPart = insight.recommendationReason?.let { " | recommendation_reason=$it" } ?: ""
            "- muc_tieu=${insight.label} | origin=${insight.origin} | destination=${insight.destination} | route_name=${insight.routeName} | traffic_status=$trafficText | duration=${insight.duration} | is_shortest=${insight.isShortest} | data_source=${insight.dataSource}$recommendPart$reasonPart$altPart$sourceNotePart$errorPart"
        }

        return """
[DU_LIEU_TRAFFIC_DA_XU_LY]
- yeu_cau_nguoi_dung: $userText
- ket_qua_kiem_tra:
$trafficBlock

Huong dan tra loi:
1) Chi dua tren ket_qua_kiem_tra o tren de tra loi.
2) Neu nguoi dung hoi nha/co quan, uu tien lo trinh dung muc_tieu.
3) Neu should_reroute=true thi uu tien de xuat recommended_route thay vi route_name hien tai.
4) Neu co tac duong, neu ro muc do va noi ro ly do de xuat doi tuyen.
5) Neu alternatives co du lieu, co the neu toi da 1-2 tuyen de nguoi dung chon.
6) Neu data_source=mock_fallback hoac data_source=mock thi phai bao ro rang day la du lieu uoc tinh.
7) Neu nguoi dung can ETA chinh xac theo thoi diem hien tai, goi y mo Google Maps de xem thoi gian thuc te.
8) Tra loi ngan gon, de hieu, bang tieng Viet.
9) Neu yeu cau nguoi dung co chi duong/mo ban do thi BAT BUOC goi tool open_google_maps.
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
        val cachedLocation = getFreshCachedLocationOrNull()

        if (!hasLocationPermission()) {
            if (cachedLocation != null) {
                return cachedLocation
            }
            repository.sendMessage(
                "Mình cần quyền vị trí để kiểm tra từ nơi bạn đang đứng. Hãy cấp quyền Location cho app rồi hỏi lại nhé.",
                "ai"
            )
            return null
        }

        if (!isLocationServiceEnabled()) {
            if (cachedLocation != null) {
                return cachedLocation
            }
            repository.sendMessage(
                "Mình chưa đọc được GPS vì dịch vụ vị trí trên máy đang tắt. Bạn bật Location (Use location) rồi thử lại nhé.",
                "ai"
            )
            return null
        }

        val current = getCurrentLocationLatLng() ?: cachedLocation
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
        val asksSchool = listOf("truong", "di hoc", "hoc").any { normQuery.contains(it) }

        if (target == RouteTarget.WORK && asksSchool) {
            val schoolSchedule = schedules.firstOrNull { schedule ->
                val content = normalizeForIntent("${schedule.actionName} ${schedule.destinationAddress}")
                content.contains("truong") || content.contains("hoc")
            }
            if (schoolSchedule != null) return schoolSchedule
        }

        return inferScheduleForTarget(target)
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
                Pair(highAccuracy.latitude, highAccuracy.longitude)
            } else {
                val last = fusedLocationClient.lastLocation.await()
                if (last != null) {
                    latestCurrentLocationText = formatLatLng(last.latitude, last.longitude)
                    latestCurrentLocationLatLng = Pair(last.latitude, last.longitude)
                    latestCurrentLocationUpdatedAtMs = SystemClock.elapsedRealtime()
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
}
