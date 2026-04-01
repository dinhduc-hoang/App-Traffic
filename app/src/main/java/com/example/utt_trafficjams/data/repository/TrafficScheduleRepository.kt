package com.example.utt_trafficjams.data.repository

import android.content.Context
import com.example.utt_trafficjams.data.model.RoutePlaceType
import com.example.utt_trafficjams.data.model.TrafficSchedule
import com.example.utt_trafficjams.notifications.TrafficAlertScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class TrafficScheduleRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "traffic_scheduler_prefs"
        private const val KEY_SCHEDULES = "schedules_json"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSchedules(): List<TrafficSchedule> {
        val raw = prefs.getString(KEY_SCHEDULES, null)
        if (raw.isNullOrBlank()) {
            return defaultSchedules().also { saveSchedules(it) }
        }

        return try {
            parseSchedules(raw).ifEmpty {
                defaultSchedules().also { saveSchedules(it) }
            }
        } catch (_: Exception) {
            defaultSchedules().also { saveSchedules(it) }
        }
    }

    fun saveSchedules(schedules: List<TrafficSchedule>) {
        val arr = JSONArray()
        schedules.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("actionName", s.actionName)
                    put("placeType", s.placeType.name)
                    put("destinationAddress", s.destinationAddress)
                    put("hour", s.hour)
                    put("minute", s.minute)
                    put("enabled", s.enabled)
                    put("originLat", s.originLat)
                    put("originLng", s.originLng)
                    put("destinationLat", s.destinationLat)
                    put("destinationLng", s.destinationLng)
                    put("daysOfWeek", JSONArray().apply {
                        s.daysOfWeek.sorted().forEach { put(it) }
                    })
                }
            )
        }

        prefs.edit().putString(KEY_SCHEDULES, arr.toString()).apply()
        TrafficAlertScheduler(appContext).rescheduleAll(schedules)
    }

    private fun parseSchedules(raw: String): List<TrafficSchedule> {
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    TrafficSchedule(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        actionName = obj.optString("actionName", "Lịch trình"),
                        placeType = parsePlaceType(
                            obj.optString("placeType", ""),
                            obj.optString("actionName", ""),
                            obj.optString("destinationAddress", "")
                        ),
                        destinationAddress = obj.optString("destinationAddress", ""),
                        hour = obj.optInt("hour", 7).coerceIn(0, 23),
                        minute = obj.optInt("minute", 30).coerceIn(0, 59),
                        daysOfWeek = parseDays(obj.optJSONArray("daysOfWeek")),
                        enabled = obj.optBoolean("enabled", true),
                        originLat = obj.optDouble("originLat", 21.0282),
                        originLng = obj.optDouble("originLng", 105.8040),
                        destinationLat = obj.optDouble("destinationLat", 21.0124),
                        destinationLng = obj.optDouble("destinationLng", 105.8342)
                    )
                )
            }
        }
    }

    private fun parseDays(daysArr: JSONArray?): Set<Int> {
        if (daysArr == null) {
            return setOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY
            )
        }

        val days = mutableSetOf<Int>()
        for (i in 0 until daysArr.length()) {
            val d = daysArr.optInt(i, -1)
            if (d in Calendar.SUNDAY..Calendar.SATURDAY) {
                days += d
            }
        }

        return if (days.isEmpty()) {
            setOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY
            )
        } else {
            days
        }
    }

    private fun parsePlaceType(raw: String, actionName: String, destinationAddress: String): RoutePlaceType {
        val byRaw = runCatching { RoutePlaceType.valueOf(raw.uppercase(Locale.US)) }.getOrNull()
        if (byRaw == RoutePlaceType.HOME || byRaw == RoutePlaceType.WORK) return byRaw

        val normalizedContent = (actionName + " " + destinationAddress).lowercase(Locale.getDefault())
        return when {
            normalizedContent.contains("nha") ||
                normalizedContent.contains("nhà") ||
                normalizedContent.contains("tan lam") ||
                normalizedContent.contains("ve nha") ||
                normalizedContent.contains("về nhà") ||
                normalizedContent.contains("tro") ||
                normalizedContent.contains("trọ") ||
                normalizedContent.contains("phong tro") ||
                normalizedContent.contains("phòng trọ") ||
                normalizedContent.contains("ky tuc xa") ||
                normalizedContent.contains("ký túc xá") ||
                normalizedContent.contains("noi o") ||
                normalizedContent.contains("nơi ở") ||
                normalizedContent.contains("cho o") ||
                normalizedContent.contains("chỗ ở") -> RoutePlaceType.HOME
            normalizedContent.contains("di lam") ||
                normalizedContent.contains("co quan") ||
                normalizedContent.contains("cơ quan") ||
                normalizedContent.contains("cong ty") ||
                normalizedContent.contains("công ty") ||
                normalizedContent.contains("van phong") ||
                normalizedContent.contains("văn phòng") -> RoutePlaceType.WORK
            else -> RoutePlaceType.OTHER
        }
    }

    private fun defaultSchedules(): List<TrafficSchedule> {
        return listOf(
            TrafficSchedule(
                id = UUID.randomUUID().toString(),
                actionName = "Đi làm",
                placeType = RoutePlaceType.WORK,
                destinationAddress = "Cơ quan",
                hour = 7,
                minute = 30
            ),
            TrafficSchedule(
                id = UUID.randomUUID().toString(),
                actionName = "Về nhà",
                placeType = RoutePlaceType.HOME,
                destinationAddress = "Nhà",
                hour = 17,
                minute = 0
            )
        )
    }
}
