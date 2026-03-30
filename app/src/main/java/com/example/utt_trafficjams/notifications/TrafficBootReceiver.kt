package com.example.utt_trafficjams.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.utt_trafficjams.data.repository.TrafficScheduleRepository

class TrafficBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val schedules = TrafficScheduleRepository(context.applicationContext).getSchedules()
                TrafficAlertScheduler(context.applicationContext).rescheduleAll(schedules)
            }
        }
    }
}
