package com.example.utt_trafficjams.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.utt_trafficjams.data.model.TrafficSchedule
import org.json.JSONArray
import java.util.Calendar

class TrafficAlertScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun rescheduleAll(schedules: List<TrafficSchedule>) {
        getTrackedScheduleIds().forEach { cancelSchedule(it) }

        val enabledSchedules = schedules.filter { it.enabled }
        enabledSchedules.forEach { scheduleNextForSchedule(it) }

        saveTrackedScheduleIds(enabledSchedules.map { it.id }.toSet())
    }

    fun scheduleNextForSchedule(schedule: TrafficSchedule) {
        cancelLegacyAlert(schedule.id)

        if (!schedule.enabled) {
            cancelSchedule(schedule.id)
            return
        }

        val nowMs = System.currentTimeMillis()
        val preTripTriggerAt = computeNextAlertMillis(schedule, nowMs, minutesBeforeTravel = 30)
        val onTimeTriggerAt = computeNextAlertMillis(schedule, nowMs, minutesBeforeTravel = 0)

        if (preTripTriggerAt == null && onTimeTriggerAt == null) {
            cancelSchedule(schedule.id)
            return
        }

        if (preTripTriggerAt != null) {
            scheduleAlarm(
                triggerAt = preTripTriggerAt,
                pendingIntent = buildPendingIntent(schedule.id, ALERT_TYPE_PRE_TRIP)
            )
        } else {
            cancelAlert(schedule.id, ALERT_TYPE_PRE_TRIP)
        }

        if (onTimeTriggerAt != null) {
            scheduleAlarm(
                triggerAt = onTimeTriggerAt,
                pendingIntent = buildPendingIntent(schedule.id, ALERT_TYPE_ON_TIME)
            )
        } else {
            cancelAlert(schedule.id, ALERT_TYPE_ON_TIME)
        }
    }

    fun cancelSchedule(scheduleId: String) {
        cancelAlert(scheduleId, ALERT_TYPE_PRE_TRIP)
        cancelAlert(scheduleId, ALERT_TYPE_ON_TIME)
        cancelLegacyAlert(scheduleId)
    }

    private fun cancelAlert(scheduleId: String, alertType: String) {
        alarmManager.cancel(buildPendingIntent(scheduleId, alertType))
    }

    private fun cancelLegacyAlert(scheduleId: String) {
        alarmManager.cancel(buildLegacyPendingIntent(scheduleId))
    }

    private fun buildPendingIntent(scheduleId: String, alertType: String): PendingIntent {
        val intent = Intent(appContext, TrafficAlertReceiver::class.java).apply {
            action = ACTION_CHECK_TRAFFIC_ALERT
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_ALERT_TYPE, alertType)
        }
        return PendingIntent.getBroadcast(
            appContext,
            buildRequestCode(scheduleId, alertType),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildLegacyPendingIntent(scheduleId: String): PendingIntent {
        val intent = Intent(appContext, TrafficAlertReceiver::class.java).apply {
            action = ACTION_CHECK_TRAFFIC_ALERT
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            scheduleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildRequestCode(scheduleId: String, alertType: String): Int {
        return "$scheduleId#$alertType".hashCode()
    }

    private fun scheduleAlarm(triggerAt: Long, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun computeNextAlertMillis(
        schedule: TrafficSchedule,
        nowMs: Long,
        minutesBeforeTravel: Int
    ): Long? {
        if (schedule.daysOfWeek.isEmpty()) return null

        for (offset in 0..14) {
            val travel = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, schedule.hour)
                set(Calendar.MINUTE, schedule.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (travel.get(Calendar.DAY_OF_WEEK) !in schedule.daysOfWeek) {
                continue
            }

            val alert = (travel.clone() as Calendar).apply {
                add(Calendar.MINUTE, -minutesBeforeTravel)
            }

            if (alert.timeInMillis > nowMs + 1_000L) {
                return alert.timeInMillis
            }
        }

        return null
    }

    private fun getTrackedScheduleIds(): Set<String> {
        val raw = prefs.getString(KEY_TRACKED_IDS, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i).trim()
                    if (id.isNotBlank()) add(id)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun saveTrackedScheduleIds(ids: Set<String>) {
        val arr = JSONArray()
        ids.sorted().forEach { arr.put(it) }
        prefs.edit().putString(KEY_TRACKED_IDS, arr.toString()).apply()
    }

    companion object {
        const val ACTION_CHECK_TRAFFIC_ALERT = "com.example.utt_trafficjams.action.CHECK_TRAFFIC_ALERT"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        const val EXTRA_ALERT_TYPE = "extra_alert_type"
        const val ALERT_TYPE_PRE_TRIP = "pre_trip"
        const val ALERT_TYPE_ON_TIME = "on_time"

        private const val PREFS_NAME = "traffic_alert_scheduler_prefs"
        private const val KEY_TRACKED_IDS = "tracked_schedule_ids"

        const val CHANNEL_ID = "traffic_pretrip_alerts"
    }
}
