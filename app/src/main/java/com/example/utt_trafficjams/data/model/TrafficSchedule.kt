package com.example.utt_trafficjams.data.model

import java.util.Calendar

enum class RoutePlaceType {
    HOME,
    WORK,
    OTHER
}

data class TrafficSchedule(
    val id: String,
    val actionName: String,
    val placeType: RoutePlaceType = RoutePlaceType.OTHER,
    val destinationAddress: String = "",
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<Int> = setOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY
    ),
    val enabled: Boolean = true,
    val originLat: Double = 21.0282,
    val originLng: Double = 105.8040,
    val destinationLat: Double = 21.0124,
    val destinationLng: Double = 105.8342
)
