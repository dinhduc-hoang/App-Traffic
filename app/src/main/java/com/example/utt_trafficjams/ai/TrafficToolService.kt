package com.example.utt_trafficjams.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.ceil

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
 * Context dia chi dua vao system instruction khi mo session.
 */
data class LiveAddressContext(
    val currentLocation: String? = null,
    val homeAddress: String? = null,
    val workAddress: String? = null
)

/**
 * Abstraction cho phan goi API giao thong.
 */
interface TrafficToolService {
    suspend fun checkTraffic(request: TrafficToolRequest): TrafficToolResponse
}

/**
 * Service goi Mapbox Directions API de tinh thoi gian quang duong (ETA).
 * Neu loi access token/quota/network se fallback ve mock.
 */
class MapboxDirectionsTrafficToolService(
    private val accessToken: String,
    private val fallback: TrafficToolService = MockTrafficToolService(),
    private val client: OkHttpClient = OkHttpClient()
) : TrafficToolService {

    private data class GeoPoint(val lat: Double, val lng: Double)

    private val labeledLatLngPattern = Regex(
        pattern = """lat\s*=\s*([-+]?\d+(?:\.\d+)?)\s*,\s*lng\s*=\s*([-+]?\d+(?:\.\d+)?)""",
        option = RegexOption.IGNORE_CASE
    )

    private val plainLatLngPattern = Regex(
        pattern = """^\s*([-+]?\d+(?:\.\d+)?)\s*,\s*([-+]?\d+(?:\.\d+)?)\s*$"""
    )

    override suspend fun checkTraffic(request: TrafficToolRequest): TrafficToolResponse {
        return withContext(Dispatchers.IO) {
            if (accessToken.isBlank()) {
                val mock = fallback.checkTraffic(request)
                return@withContext mock.copy(
                    routeName = "${mock.routeName} (dữ liệu mô phỏng)",
                    source = "mock_fallback",
                    sourceNote = "MAPBOX_ACCESS_TOKEN_trong"
                )
            }

            runCatching { fetchFromMapbox(request) }
                .getOrElse { ex ->
                    val mock = fallback.checkTraffic(request)
                    mock.copy(
                        routeName = "${mock.routeName} (dữ liệu mô phỏng)",
                        source = "mock_fallback",
                        sourceNote = ex.message ?: "mapbox_directions_unknown_error"
                    )
                }
        }
    }

    private fun fetchFromMapbox(request: TrafficToolRequest): TrafficToolResponse {
        val originPoint = resolveToGeoPoint(request.origin)
        val destinationPoint = resolveToGeoPoint(request.destination)
        val coordinates = "${originPoint.lng},${originPoint.lat};${destinationPoint.lng},${destinationPoint.lat}"

        val base = "https://api.mapbox.com/directions/v5/mapbox/driving/$coordinates".toHttpUrlOrNull()
            ?: error("invalid_mapbox_directions_endpoint")

        val url = base.newBuilder()
            .addQueryParameter("alternatives", "3")
            .addQueryParameter("steps", "false")
            .addQueryParameter("overview", "false")
            .addQueryParameter("language", "vi")
            .addQueryParameter("access_token", accessToken)
            .build()

        val httpRequest = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(httpRequest).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                error("mapbox_http_${resp.code}")
            }

            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val code = json.optString("code", "")
            if (code != "Ok") {
                error("mapbox_code_$code")
            }

            val routes = json.optJSONArray("routes") ?: error("mapbox_no_routes")
            if (routes.length() == 0) error("mapbox_empty_routes")

            data class RouteCandidate(
                val index: Int,
                val name: String,
                val durationSeconds: Long
            )

            val candidates = buildList {
                for (i in 0 until routes.length()) {
                    val route = routes.optJSONObject(i) ?: continue
                    val durationSeconds = route.optDouble("duration", -1.0)
                        .takeIf { it > 0.0 }
                        ?.toLong()
                        ?: continue

                    add(
                        RouteCandidate(
                            index = i,
                            name = "Tuyến Mapbox #${i + 1}",
                            durationSeconds = durationSeconds
                        )
                    )
                }
            }

            if (candidates.isEmpty()) error("mapbox_no_valid_candidates")

            val primary = candidates.first()
            val fastest = candidates.minByOrNull { it.durationSeconds } ?: primary

            val primaryIsFastest = primary.index == fastest.index
            val gainSeconds = (primary.durationSeconds - fastest.durationSeconds).coerceAtLeast(0L)
            val shouldReroute = !primaryIsFastest && gainSeconds >= 120L
            val trafficStatus = shouldReroute

            val recommendationReason = when {
                shouldReroute -> "Tuyến hiện tại có ETA chậm hơn tuyến thay thế khoảng ${gainSeconds / 60} phút."
                else -> "Tuyến hiện tại đang tối ưu theo ETA Mapbox."
            }

            val alternatives = candidates.take(3).map { candidate ->
                TrafficAlternativeRoute(
                    name = candidate.name,
                    duration = formatDuration(candidate.durationSeconds),
                    isShortest = candidate.index == fastest.index,
                    isPrimary = candidate.index == primary.index
                )
            }

            return TrafficToolResponse(
                routeName = primary.name,
                trafficStatus = trafficStatus,
                duration = formatDuration(primary.durationSeconds),
                isShortest = primaryIsFastest,
                shouldReroute = shouldReroute,
                recommendedRouteName = if (shouldReroute) fastest.name else primary.name,
                recommendedDuration = if (shouldReroute) {
                    formatDuration(fastest.durationSeconds)
                } else {
                    formatDuration(primary.durationSeconds)
                },
                recommendationReason = recommendationReason,
                alternatives = alternatives,
                source = "mapbox_directions"
            )
        }
    }

    private fun resolveToGeoPoint(rawInput: String): GeoPoint {
        val normalized = normalizeLocationInput(rawInput)
        parseLatLng(normalized)?.let { return it }
        return geocodeAddress(normalized)
    }

    private fun geocodeAddress(address: String): GeoPoint {
        if (address.isBlank()) error("mapbox_geocode_empty_query")

        val encodedAddress = URLEncoder.encode(address, Charsets.UTF_8.name())
            .replace("+", "%20")

        val base = "https://api.mapbox.com/geocoding/v5/mapbox.places/$encodedAddress.json".toHttpUrlOrNull()
            ?: error("invalid_mapbox_geocode_endpoint")

        val url = base.newBuilder()
            .addQueryParameter("limit", "1")
            .addQueryParameter("language", "vi")
            .addQueryParameter("access_token", accessToken)
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                error("mapbox_geocode_http_${resp.code}")
            }

            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val features = json.optJSONArray("features") ?: error("mapbox_geocode_no_features")
            if (features.length() == 0) error("mapbox_geocode_empty_features")

            val center = features.optJSONObject(0)?.optJSONArray("center")
                ?: error("mapbox_geocode_no_center")

            val lng = center.optDouble(0, Double.NaN)
            val lat = center.optDouble(1, Double.NaN)
            if (lat.isNaN() || lng.isNaN()) {
                error("mapbox_geocode_invalid_center")
            }

            return GeoPoint(lat = lat, lng = lng)
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

    private fun parseLatLng(input: String): GeoPoint? {
        val match = plainLatLngPattern.matchEntire(input) ?: return null
        val lat = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val lng = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat = lat, lng = lng)
    }

    private fun formatDuration(seconds: Long): String {
        val safeSeconds = seconds.coerceAtLeast(0L)
        val totalMinutes = ceil(safeSeconds / 60.0).toLong().coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours <= 0L -> "$totalMinutes phút"
            minutes == 0L -> "$hours giờ"
            else -> "$hours giờ $minutes phút"
        }
    }
}

/**
 * Service goi Google Directions API de lay thoi gian di chuyen gan voi thuc te hon.
 * Neu API loi (key chua bat web service/billing/REQUEST_DENIED), se fallback ve mock.
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
                    routeName = "${mock.routeName} (dữ liệu mô phỏng)",
                    source = "mock_fallback",
                    sourceNote = "MAPS_API_KEY_trong"
                )
            }

            runCatching { fetchFromDirections(request) }
                .getOrElse { ex ->
                    val mock = fallback.checkTraffic(request)
                    mock.copy(
                        routeName = "${mock.routeName} (dữ liệu mô phỏng)",
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
                            ?: "không xác định"

                    val summary = route.optString("summary", "").trim()
                        val routeName = if (summary.isBlank()) "Tuyến #${i + 1}" else summary

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
            val extraMinutes = (primary.trafficSeconds - primary.baseSeconds) / 60.0
            val isHeavyTraffic = ratio >= 1.2 || extraMinutes >= 5.0

            val primaryIsFastest = primary.index == fastest.index
            val gainSeconds = (primary.trafficSeconds - fastest.trafficSeconds).coerceAtLeast(0L)
            val shouldReroute = isHeavyTraffic && !primaryIsFastest && gainSeconds >= 120L

            val recommendationReason = when {
                shouldReroute -> "Tuyến hiện tại đang tắc; tuyến thay thế nhanh hơn khoảng ${gainSeconds / 60} phút."
                isHeavyTraffic -> "Tuyến hiện tại đang đông nhưng chưa có tuyến thay thế nhanh hơn đáng kể."
                else -> "Tuyến hiện tại đang tối ưu."
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
 * Mock service: mo phong ket qua goi API.
 */
class MockTrafficToolService : TrafficToolService {
    override suspend fun checkTraffic(request: TrafficToolRequest): TrafficToolResponse {
        // Mo phong latency mang/API.
        delay(180)

        val seed = abs((request.origin + "->" + request.destination).hashCode())
        val routeIndex = seed % 4 + 1
        val durationMinutes = 12 + (seed % 35)
        val trafficStatus = seed % 3 == 0
        val isShortest = seed % 5 != 0
        val alt1Minutes = durationMinutes + (seed % 7) + 2
        val alt2Minutes = (durationMinutes - ((seed % 5) + 1)).coerceAtLeast(8)
        val shouldReroute = trafficStatus && !isShortest

        val primaryName = "Tuyến gợi ý #$routeIndex"
        val altFastName = "Tuyến thay thế #${(routeIndex + 1) % 4 + 1}"
        val altSlowName = "Tuyến thay thế #${(routeIndex + 2) % 4 + 1}"

        val alternatives = listOf(
            TrafficAlternativeRoute(
                name = primaryName,
                duration = "$durationMinutes phút",
                isShortest = !shouldReroute,
                isPrimary = true
            ),
            TrafficAlternativeRoute(
                name = altFastName,
                duration = "$alt2Minutes phút",
                isShortest = shouldReroute,
                isPrimary = false
            ),
            TrafficAlternativeRoute(
                name = altSlowName,
                duration = "$alt1Minutes phút",
                isShortest = false,
                isPrimary = false
            )
        )

        return TrafficToolResponse(
            routeName = primaryName,
            trafficStatus = trafficStatus,
            duration = "$durationMinutes phút",
            isShortest = isShortest,
            shouldReroute = shouldReroute,
            recommendedRouteName = if (shouldReroute) altFastName else primaryName,
            recommendedDuration = if (shouldReroute) "$alt2Minutes phút" else "$durationMinutes phút",
            recommendationReason = if (shouldReroute) {
                "Tuyến hiện tại tắc và có tuyến thay thế nhanh hơn."
            } else {
                "Tuyến hiện tại đang tối ưu."
            },
            alternatives = alternatives,
            source = "mock"
        )
    }
}
