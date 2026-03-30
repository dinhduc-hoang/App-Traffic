package com.example.utt_trafficjams.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs

/**
 * Input cho tool check_traffic.
 */
data class TrafficToolRequest(
    val origin: String,
    val destination: String
)

/**
 * Output JSON cho tool check_traffic.
 */
data class TrafficToolResponse(
    val routeName: String,
    val trafficStatus: Boolean,
    val duration: String,
    val isShortest: Boolean,
    val shouldReroute: Boolean = false,
    val recommendedRouteName: String? = null,
    val recommendedDuration: String? = null,
    val recommendationReason: String? = null,
    val alternatives: List<TrafficAlternativeRoute> = emptyList(),
    val source: String = "unknown",
    val sourceNote: String? = null
)

data class TrafficAlternativeRoute(
    val name: String,
    val duration: String,
    val isShortest: Boolean = false,
    val isPrimary: Boolean = false
)

/**
 * Context địa chỉ đưa vào system instruction khi mở session.
 */
data class LiveAddressContext(
    val currentLocation: String? = null,
    val homeAddress: String? = null,
    val workAddress: String? = null
)

/**
 * Abstraction cho phần gọi API giao thông.
 */
interface TrafficToolService {
    suspend fun checkTraffic(request: TrafficToolRequest): TrafficToolResponse
}

/**
 * Service gọi Google Directions API để lấy thời gian di chuyển gần với thực tế hơn.
 * Nếu API lỗi (key chưa bật web service/billing/REQUEST_DENIED), sẽ fallback về mock.
 */
class GoogleDirectionsTrafficToolService(
    private val apiKey: String,
    private val fallback: TrafficToolService = MockTrafficToolService(),
    private val client: OkHttpClient = OkHttpClient()
) : TrafficToolService {

    private val labeledLatLngPattern = Regex(
        pattern = """lat\s*=\s*([-+]?\d+(?:\.\d+)?)\s*,\s*lng\s*=\s*([-+]?\d+(?:\.\d+)?)""",
        option = RegexOption.IGNORE_CASE
    )

    override suspend fun checkTraffic(request: TrafficToolRequest): TrafficToolResponse {
        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                val mock = fallback.checkTraffic(request)
                return@withContext mock.copy(
                    routeName = "${mock.routeName} (du lieu mo phong)",
                    source = "mock_fallback",
                    sourceNote = "MAPS_API_KEY_trong"
                )
            }

            runCatching { fetchFromDirections(request) }
                .getOrElse { ex ->
                    val mock = fallback.checkTraffic(request)
                    mock.copy(
                        routeName = "${mock.routeName} (du lieu mo phong)",
                        source = "mock_fallback",
                        sourceNote = ex.message ?: "directions_unknown_error"
                    )
                }
        }
    }

    private fun fetchFromDirections(request: TrafficToolRequest): TrafficToolResponse {
        val normalizedOrigin = normalizeLocationInput(request.origin)
        val normalizedDestination = normalizeLocationInput(request.destination)

        val base = "https://maps.googleapis.com/maps/api/directions/json".toHttpUrlOrNull()
            ?: error("invalid_directions_endpoint")

        val url = base.newBuilder()
            .addQueryParameter("origin", normalizedOrigin)
            .addQueryParameter("destination", normalizedDestination)
            .addQueryParameter("mode", "driving")
            .addQueryParameter("departure_time", "now")
            .addQueryParameter("traffic_model", "best_guess")
            .addQueryParameter("alternatives", "true")
            .addQueryParameter("language", "vi")
            .addQueryParameter("key", apiKey)
            .build()

        val httpRequest = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(httpRequest).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                error("directions_http_${resp.code}")
            }

            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val status = json.optString("status", "")
            if (status != "OK") {
                error("directions_status_$status")
            }

            val routes = json.optJSONArray("routes") ?: error("directions_no_routes")
            if (routes.length() == 0) error("directions_empty_routes")

            data class RouteCandidate(
                val index: Int,
                val name: String,
                val durationText: String,
                val baseSeconds: Long,
                val trafficSeconds: Long
            )

            val candidates = buildList {
                for (i in 0 until routes.length()) {
                    val route = routes.optJSONObject(i) ?: continue
                    val leg = route.optJSONArray("legs")?.optJSONObject(0) ?: continue
                    val baseSeconds = leg.optJSONObject("duration")
                        ?.optLong("value", -1L)
                        ?.takeIf { it > 0 }
                        ?: continue
                    val trafficSeconds = leg.optJSONObject("duration_in_traffic")
                        ?.optLong("value", baseSeconds)
                        ?.takeIf { it > 0 }
                        ?: baseSeconds
                    val durationText = leg.optJSONObject("duration_in_traffic")
                        ?.optString("text")
                        ?.takeIf { it.isNotBlank() }
                        ?: leg.optJSONObject("duration")
                            ?.optString("text")
                            ?.takeIf { it.isNotBlank() }
                        ?: "khong xac dinh"

                    val summary = route.optString("summary", "").trim()
                    val routeName = if (summary.isBlank()) "Tuyen #${i + 1}" else summary

                    add(
                        RouteCandidate(
                            index = i,
                            name = routeName,
                            durationText = durationText,
                            baseSeconds = baseSeconds,
                            trafficSeconds = trafficSeconds
                        )
                    )
                }
            }

            if (candidates.isEmpty()) error("directions_no_valid_candidates")

            val primary = candidates.first()
            val fastest = candidates.minByOrNull { it.trafficSeconds } ?: primary

            val ratio = primary.trafficSeconds.toDouble() / primary.baseSeconds.toDouble()
            val extraMinutes = ((primary.trafficSeconds - primary.baseSeconds) / 60.0)
            val isHeavyTraffic = ratio >= 1.2 || extraMinutes >= 5.0

            val primaryIsFastest = primary.index == fastest.index
            val gainSeconds = (primary.trafficSeconds - fastest.trafficSeconds).coerceAtLeast(0L)
            val shouldReroute = isHeavyTraffic && !primaryIsFastest && gainSeconds >= 120L

            val recommendationReason = when {
                shouldReroute -> "Tuyen hien tai dang tac; tuyen thay the nhanh hon khoang ${gainSeconds / 60} phut."
                isHeavyTraffic -> "Tuyen hien tai dang dong nhung chua co tuyen thay the nhanh hon dang ke."
                else -> "Tuyen hien tai dang toi uu."
            }

            val alternatives = candidates.take(3).map { candidate ->
                TrafficAlternativeRoute(
                    name = candidate.name,
                    duration = candidate.durationText,
                    isShortest = candidate.index == fastest.index,
                    isPrimary = candidate.index == primary.index
                )
            }

            return TrafficToolResponse(
                routeName = primary.name,
                trafficStatus = isHeavyTraffic,
                duration = primary.durationText,
                isShortest = primaryIsFastest,
                shouldReroute = shouldReroute,
                recommendedRouteName = if (shouldReroute) fastest.name else primary.name,
                recommendedDuration = if (shouldReroute) fastest.durationText else primary.durationText,
                recommendationReason = recommendationReason,
                alternatives = alternatives,
                source = "google_directions"
            )
        }
    }

    private fun normalizeLocationInput(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return value

        val matched = labeledLatLngPattern.find(value)
        if (matched != null) {
            val lat = matched.groupValues.getOrNull(1).orEmpty()
            val lng = matched.groupValues.getOrNull(2).orEmpty()
            if (lat.isNotBlank() && lng.isNotBlank()) {
                return "$lat,$lng"
            }
        }

        return value
    }
}

/**
 * Mock service: mô phỏng kết quả gọi Maps/Goong API.
 */
class MockTrafficToolService : TrafficToolService {
    override suspend fun checkTraffic(request: TrafficToolRequest): TrafficToolResponse {
        // Mô phỏng latency mạng/API.
        delay(180)

        val seed = abs((request.origin + "->" + request.destination).hashCode())
        val routeIndex = seed % 4 + 1
        val durationMinutes = 12 + (seed % 35)
        val trafficStatus = seed % 3 == 0
        val isShortest = seed % 5 != 0
        val alt1Minutes = durationMinutes + (seed % 7) + 2
        val alt2Minutes = (durationMinutes - ((seed % 5) + 1)).coerceAtLeast(8)
        val shouldReroute = trafficStatus && !isShortest

        val primaryName = "Tuyen goi y #$routeIndex"
        val altFastName = "Tuyen thay the #${(routeIndex + 1) % 4 + 1}"
        val altSlowName = "Tuyen thay the #${(routeIndex + 2) % 4 + 1}"

        val alternatives = listOf(
            TrafficAlternativeRoute(
                name = primaryName,
                duration = "$durationMinutes phut",
                isShortest = !shouldReroute,
                isPrimary = true
            ),
            TrafficAlternativeRoute(
                name = altFastName,
                duration = "$alt2Minutes phut",
                isShortest = shouldReroute,
                isPrimary = false
            ),
            TrafficAlternativeRoute(
                name = altSlowName,
                duration = "$alt1Minutes phut",
                isShortest = false,
                isPrimary = false
            )
        )

        return TrafficToolResponse(
            routeName = primaryName,
            trafficStatus = trafficStatus,
            duration = "$durationMinutes phut",
            isShortest = isShortest,
            shouldReroute = shouldReroute,
            recommendedRouteName = if (shouldReroute) altFastName else primaryName,
            recommendedDuration = if (shouldReroute) "$alt2Minutes phut" else "$durationMinutes phut",
            recommendationReason = if (shouldReroute) {
                "Tuyen hien tai tac va co tuyen thay the nhanh hon."
            } else {
                "Tuyen hien tai dang toi uu."
            },
            alternatives = alternatives,
            source = "mock"
        )
    }
}
