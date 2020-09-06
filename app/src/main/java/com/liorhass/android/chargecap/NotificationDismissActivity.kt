package com.liorhass.android.chargecap

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import timber.log.Timber

// https://www.semicolonworld.com/question/48769/how-to-dismiss-notification-after-action-has-been-clicked
class NotificationDismissActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = intent.getIntExtra(NOTIFICATION_ID, -1)
        Timber.d("onCreate(): Canceling notification ID=$notificationId")
        notificationManager.cancel(notificationId)
        finish() // since finish() is called in onCreate(), onDestroy() will be called immediately
    }

    companion object {
        const val NOTIFICATION_ID = "NOTIFICATION_ID"

        fun createDismissIntent(notificationId: Int, context: Context?): PendingIntent {
            val intent = Intent(context, NotificationDismissActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.putExtra(NOTIFICATION_ID, notificationId)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT)
        }
    }
}