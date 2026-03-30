package com.example.utt_trafficjams.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.utt_trafficjams.BuildConfig
import com.example.utt_trafficjams.MainActivity
import com.example.utt_trafficjams.R
import com.example.utt_trafficjams.ai.MapboxDirectionsTrafficToolService
import com.example.utt_trafficjams.ai.TrafficToolRequest
import com.example.utt_trafficjams.data.model.TrafficSchedule
import com.example.utt_trafficjams.data.repository.TrafficScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrafficAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TrafficAlertScheduler.ACTION_CHECK_TRAFFIC_ALERT) return

        val scheduleId = intent.getStringExtra(TrafficAlertScheduler.EXTRA_SCHEDULE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = TrafficScheduleRepository(context.applicationContext)
                val schedule = repository.getSchedules().firstOrNull { it.id == scheduleId }

                if (schedule == null || !schedule.enabled) {
                    TrafficAlertScheduler(context).cancelSchedule(scheduleId)
                    return@launch
                }

                val trafficResult = runCatching {
                    MapboxDirectionsTrafficToolService(BuildConfig.MAPBOX_API_KEY).checkTraffic(
                        TrafficToolRequest(
                            origin = "${schedule.originLat},${schedule.originLng}",
                            destination = "${schedule.destinationLat},${schedule.destinationLng}"
                        )
                    )
                }.getOrElse {
                    null
                }

                showTrafficNotification(context, schedule, trafficResult)
                TrafficAlertScheduler(context).scheduleNextForSchedule(schedule)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showTrafficNotification(
        context: Context,
        schedule: TrafficSchedule,
        result: com.example.utt_trafficjams.ai.TrafficToolResponse?
    ) {
        ensureChannel(context)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = "Nhắc trước giờ đi: ${schedule.actionName}"
        val body = if (result == null) {
            "Không lấy được dữ liệu giao thông lúc này. Mở app để xem và kiểm tra lại."
        } else if (result.trafficStatus) {
            val reason = result.recommendationReason?.let { " $it" } ?: ""
            "Đường đang đông. Thời gian di chuyển ước tính khoảng ${result.duration}.$reason"
        } else {
            "Đường thông thoáng. Thời gian di chuyển ước tính khoảng ${result.duration}."
        }

        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            schedule.id.hashCode(),
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, TrafficAlertScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(context).notify(schedule.id.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            TrafficAlertScheduler.CHANNEL_ID,
            "Cảnh báo lộ trình trước giờ đi",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Thông báo tình trạng tắc đường trước giờ đi 30 phút"
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
