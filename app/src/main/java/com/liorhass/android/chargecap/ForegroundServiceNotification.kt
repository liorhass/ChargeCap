package com.liorhass.android.chargecap

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import timber.log.Timber


class ForegroundServiceNotification(context: Context, workerId: java.util.UUID? = null) {
    companion object {
        const val NOTIFICATION_ID = 191  // notification unique ID
    }
    private val mainActivityPendingIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
    private val workCancellingPendingIntent = if (workerId != null) WorkManager.getInstance(context).createCancelPendingIntent(workerId) else null // This PendingIntent can be used to cancel the worker

    fun getNotification(context: Context, contentText: String?): Notification {
        Timber.d("getNotification(): contentText=\"$contentText\"")

        val notificationBuilder = NotificationCompat.Builder(context, MainActivity.NOTIFICATIONS_CHANNEL_ID)
        if (workCancellingPendingIntent != null) {
            notificationBuilder.addAction(R.drawable.ic_baseline_cancel_24, "Stop battery monitor", workCancellingPendingIntent) //todo str
        }
        return notificationBuilder
            .setContentTitle("Monitoring battery") //todo string
            .setContentText(contentText ?: "Monitoring started") //todo string
            .setSmallIcon(R.drawable.ic_baseline_battery_limit_24)
            .setOngoing(true)
            .setNotificationSilent()
            .setContentIntent(mainActivityPendingIntent)
            .build()
    }

//    fun updateNotificationContentText(text: String): Notification {
//        notificationBuilder.setContentText(text)
//        return notificationBuilder.build()
//    }
//    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//    fun isNotificationActive(): Boolean {
//        for (notification in notificationManager.activeNotifications) {
//            if (notification.id == NOTIFICATION_ID) {
//                return true
//            }
//        }
//        return false
//    }
}