package com.example.utt_trafficjams.ai

import com.example.utt_trafficjams.data.model.HazardReport
import com.example.utt_trafficjams.data.model.RouteHazardCheckResult
import com.example.utt_trafficjams.data.model.RouteHazardMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class RouteHazardInspector(
    private val accessToken: String,
    private val client: OkHttpClient = OkHttpClient()
) {

    private data class GeoPoint(val lat: Double, val lng: Double)

    private val labeledLatLngPattern = Regex(
        pattern = """lat\s*=\s*([-+]?\d+(?:\.\d+)?)\s*,\s*lng\s*=\s*([-+]?\d+(?:\.\d+)?)""",
        option = RegexOption.IGNORE_CASE
    )

    private val plainLatLngPattern = Regex(
        pattern = """^\s*([-+]?\d+(?:\.\d+)?)\s*,\s*([-+]?\d+(?:\.\d+)?)\s*$"""
    )

    suspend fun inspectRoute(
        origin: String,
        destination: String,
        reports: List<HazardReport>,
        thresholdMeters: Double = 220.0
    ): RouteHazardCheckResult {
        if (reports.isEmpty()) {
            return RouteHazardCheckResult(
                hasHazardAlerts = false,
                source = "hazard_local",
                sourceNote = "no_hazard_reports"
            )
        }

        return withContext(Dispatchers.IO) {
            if (accessToken.isBlank()) {
                return@withContext RouteHazardCheckResult(
                    hasHazardAlerts = false,
                    source = "hazard_route",
                    sourceNote = "mapbox_access_token_blank"
                )
            }

            runCatching {
                val routePoints = fetchRoutePoints(origin = origin, destination = destination)
                if (routePoints.isEmpty()) {
                    return@runCatching RouteHazardCheckResult(
                        hasHazardAlerts = false,
                        source = "hazard_route",
                        sourceNote = "route_geometry_empty"
                    )
                }

                val matches = reports.mapNotNull { report ->
                    val minDistance = routePoints.minOfOrNull { point ->
                        haversineMeters(
                            lat1 = report.lat,
                            lng1 = report.lng,
                            lat2 = point.lat,
                            lng2 = point.lng
                        )
                    } ?: return@mapNotNull null

                    if (minDistance <= thresholdMeters) {
                        RouteHazardMatch(
                            reportId = report.id,
                            issue = report.issueLabel,
                            distanceMeters = minDistance.toInt(),
                            lat = report.lat,
                            lng = report.lng,
                            createdAtMs = report.createdAtMs
                        )
                    } else {
                        null
                    }
                }.sortedBy { it.distanceMeters }

                RouteHazardCheckResult(
                    hasHazardAlerts = matches.isNotEmpty(),
                    matches = matches,
                    source = "mapbox_route_hazard"
                )
            }.getOrElse { ex ->
                RouteHazardCheckResult(
                    hasHazardAlerts = false,
                    source = "hazard_route",
                    sourceNote = ex.message ?: "hazard_route_unknown_error"
                )
            }
        }
    }

    private fun fetchRoutePoints(origin: String, destination: String): List<GeoPoint> {
        val originPoint = resolveToGeoPoint(origin)
        val destinationPoint = resolveToGeoPoint(destination)

        val coordinates = "${originPoint.lng},${originPoint.lat};${destinationPoint.lng},${destinationPoint.lat}"
        val base = "https://api.mapbox.com/directions/v5/mapbox/driving/$coordinates".toHttpUrlOrNull()
            ?: error("invalid_mapbox_directions_endpoint")

        val url = base.newBuilder()
            .addQueryParameter("alternatives", "false")
            .addQueryParameter("steps", "false")
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("language", "vi")
            .addQueryParameter("access_token", accessToken)
            .build()

        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                error("mapbox_route_http_${resp.code}")
            }

            val json = JSONObject(resp.body?.string().orEmpty())
            val code = json.optString("code")
            if (code != "Ok") error("mapbox_route_code_$code")

            val firstRoute = json.optJSONArray("routes")?.optJSONObject(0)
                ?: error("mapbox_route_missing")
            val geometry = firstRoute.optJSONObject("geometry")
                ?: error("mapbox_route_geometry_missing")
            val pointsArray = geometry.optJSONArray("coordinates")
                ?: error("mapbox_route_coordinates_missing")

            return buildList {
                for (i in 0 until pointsArray.length()) {
                    val pair = pointsArray.optJSONArray(i) ?: continue
                    val lng = pair.optDouble(0, Double.NaN)
                    val lat = pair.optDouble(1, Double.NaN)
                    if (!lat.isNaN() && !lng.isNaN()) {
                        add(GeoPoint(lat = lat, lng = lng))
                    }
                }
            }
        }
    }

    private fun resolveToGeoPoint(rawInput: String): GeoPoint {
        val normalized = rawInput.trim()
        parseLatLng(normalized)?.let { return it }

        val matched = labeledLatLngPattern.find(normalized)
        if (matched != null) {
            val lat = matched.groupValues.getOrNull(1)?.toDoubleOrNull()
            val lng = matched.groupValues.getOrNull(2)?.toDoubleOrNull()
            if (lat != null && lng != null) return GeoPoint(lat = lat, lng = lng)
        }

        return geocodeAddress(normalized)
    }

    private fun parseLatLng(input: String): GeoPoint? {
        val match = plainLatLngPattern.matchEntire(input) ?: return null
        val lat = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val lng = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat = lat, lng = lng)
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

        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                error("mapbox_geocode_http_${resp.code}")
            }

            val json = JSONObject(resp.body?.string().orEmpty())
            val center = json.optJSONArray("features")
                ?.optJSONObject(0)
                ?.optJSONArray("center")
                ?: error("mapbox_geocode_no_center")

            val lng = center.optDouble(0, Double.NaN)
            val lat = center.optDouble(1, Double.NaN)
            if (lat.isNaN() || lng.isNaN()) {
                error("mapbox_geocode_invalid_center")
            }

            return GeoPoint(lat = lat, lng = lng)
        }
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2).pow(2.0)
        val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return earthRadiusMeters * c
    }
}
