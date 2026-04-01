package com.example.utt_trafficjams.data.model

enum class HazardType(val displayName: String) {
    FLOOD("Ngập lụt"),
    LANDSLIDE("Sạt lở"),
    SUBSIDENCE("Sụt lún"),
    OTHER("Khác")
}

data class HazardReport(
    val id: String,
    val type: HazardType,
    val customIssue: String? = null,
    val lat: Double,
    val lng: Double,
    val createdAtMs: Long
) {
    val issueLabel: String
        get() = if (type == HazardType.OTHER) {
            customIssue?.trim().orEmpty().ifBlank { type.displayName }
        } else {
            type.displayName
        }
}

data class RouteHazardMatch(
    val reportId: String,
    val issue: String,
    val distanceMeters: Int,
    val lat: Double,
    val lng: Double,
    val createdAtMs: Long
)

data class RouteHazardCheckResult(
    val hasHazardAlerts: Boolean,
    val matches: List<RouteHazardMatch> = emptyList(),
    val source: String = "none",
    val sourceNote: String? = null
)
