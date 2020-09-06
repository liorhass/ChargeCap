// https://developer.android.com/jetpack/androidx/releases/work#2.4.0
// https://www.reddit.com/r/androiddev/comments/hvxp00/workmanager_240_goes_stable/
// https://issuetracker.google.com/issues?q=componentid:409906%20status:open
// How to cancel worker from the notification: https://stackoverflow.com/questions/62050518/coroutineworker-interrupt-dowork-on-cancelpendingintent-action
//
// dump jobScheduler database into a text file, then search for chargecap:
//  adb shell dumpsys jobscheduler > jobs001.txt
// output goes to Logcat:
//  adb shell am broadcast -a "androidx.work.diagnostics.REQUEST_DIAGNOSTICS" -p com.liorhass.android.chargecap
//
package com.liorhass.android.chargecap

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import timber.log.Timber

class BatteryMonitoringWorker (context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    private val foregroundServiceNotification = ForegroundServiceNotification(context, id)

    override suspend fun doWork(): Result {
        val workerType = inputData.getInt("WORKER_TYPE", 0)
        Timber.d("========= BatteryMonitoringWorker#doWork(): workerType=$workerType")

        // Make this worker run as a foreground service
        setForeground(createForegroundInfo())

        monitorBattery()

        Timber.d("doWork(): Done.")
        return Result.success()
    }

    private suspend fun monitorBattery() {
        Timber.d("monitorBattery(): start")
        val batteryMonitor = BatteryMonitor(applicationContext)
        Timber.d("monitorBattery(): got batteryMonitor")

        var c = 0
        while(true) {
            val batteryPollResult = batteryMonitor.pollBattery()
            Timber.d("monitoring battery ${c++} percent=${batteryPollResult.percent} change=${batteryPollResult.changedFromPreviousPoll} delay=${batteryPollResult.delay}")
            if (batteryPollResult.limitReached) {
                alertUser(applicationContext)
                break
            } else {
                if (batteryPollResult.changedFromPreviousPoll) {
                    Timber.d("before setNotificationContentText()")
                    // Update the text of our notification (it displays the current charge and limit)
                    setForeground(createForegroundInfo("Current charge: ${batteryPollResult.percent}%,  Limit: ${batteryPollResult.limit}%")) //todo: string
                }
                Timber.d("before delay()")
                try {
                    delay(batteryPollResult.delay)
                } catch (e: Exception) {
                    // If our job is canceled (e.g. charging stopped) delay()
                    // will throw a jobCanceled exception. This is not an error.
                    Timber.i(e,"monitorBattery(): delay() Exception")
                    break
                }
                Timber.d("back from delay()")
            }
        }
    }

    // Creates an instance of ForegroundInfo which can be used to update the ongoing notification.
    private fun createForegroundInfo(contentText: String? = null): ForegroundInfo {
        val notification = foregroundServiceNotification.getNotification(applicationContext, contentText)
        return ForegroundInfo(ForegroundServiceNotification.NOTIFICATION_ID, notification)
    }
}