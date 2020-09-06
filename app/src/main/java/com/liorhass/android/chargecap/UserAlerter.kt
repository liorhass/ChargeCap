package com.liorhass.android.chargecap

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import timber.log.Timber

const val USER_ALERT_NOTIFICATION_ID = 292
const val ACTION_SHOULD_ALERT_THE_USER = "com.liorhass.android.chargecap.should_alert_user"

// https://stackoverflow.com/a/41418325/1071117
fun alertUser(context: Context) {
    Timber.d("alertUser()")
    val intent = Intent(context, MainActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    intent.putExtra(ACTION_SHOULD_ALERT_THE_USER, true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // On Android 8 (Oreo) and up, the alert is done with a notification
        Timber.d("alertUser(): Notifying user")
        val notificationDismissPendingIntent = NotificationDismissActivity.createDismissIntent(USER_ALERT_NOTIFICATION_ID, context)

        val mainActivityPendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(context, MainActivity.NOTIFICATIONS_CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSmallIcon(R.drawable.ic_baseline_battery_alert_24)
            .setContentTitle(context.getString(R.string.battery_charge_reached_limit))
            .setContentText(context.getString(R.string.disconnect_device_from_charger))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setOngoing(true)
            // When the screen is off, Android will run this activity (our MainActivity)
            // instead of a notification. MainActivity will alert the user and let them
            // turn off the alert. This is same behaviour as an alarm clock.
            .setFullScreenIntent(mainActivityPendingIntent, true)
            // If the user clicks the notification - dismiss it. This is done by a special
            // activity that we set here.
            .setContentIntent(notificationDismissPendingIntent) // todo: Is this needed when we have setAutoCancel(true)?
            // Some users may not know to click the notification in order to silence it.
            // For these users we provide an explicit "Dismiss" button that does the same
            // as clicking the notification body.
            .addAction(R.drawable.ic_baseline_cancel_24, "Dismiss", notificationDismissPendingIntent) // todo string
            .build()
        notification.flags.or(Notification.FLAG_INSISTENT)
        NotificationManagerCompat.from(context).notify(USER_ALERT_NOTIFICATION_ID, notification)
    } else {
        // Before Android 8 (Oreo), the alert is done by starting MainActivity
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "ChargeCap:BatteryMonitoringService"
        )
        wakeLock.acquire(5000L)
        context.startActivity(intent)
    }
}