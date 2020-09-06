package com.liorhass.android.chargecap

import android.content.Context
import androidx.work.*
import timber.log.Timber

class PeriodicWorker(private val context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Timber.d("========= PeriodicWorker#doWork()")

        // Start our battery monitoring worker. We use require-charging
        // constraint in order for the worker to be automatically canceled if
        // charging stops (i.e. the device is unplugged before charge limit
        // is reached).
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .build()
        val workerParams: Data = workDataOf("WORKER_TYPE" to 1) //todo
        val oneTimeWorkRequest =
            OneTimeWorkRequestBuilder<BatteryMonitoringWorker>()
                .setInputData(workerParams)
                .setConstraints(constraints)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MainActivity.BATTERY_MONITORING_WORKER_UNIQUE_NAME, ExistingWorkPolicy.KEEP, oneTimeWorkRequest)

        return Result.success()
    }
}
