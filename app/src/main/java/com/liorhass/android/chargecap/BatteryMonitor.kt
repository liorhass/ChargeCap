package com.liorhass.android.chargecap

import android.annotation.SuppressLint
import android.content.Context
import androidx.preference.PreferenceManager
import timber.log.Timber

class BatteryMonitor(private val context: Context) {
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var prevBatteryChargePercent = 0
    private var prevBatteryChargeLimit = 0

    /**
     * @param percent Current battery charge
     * @param delay Time in mSec to wait before next poll
     * @param limitReached true when battery charge has reached the designated limit
     */
    class BatteryPollResult(val percent: Int,
                            val limit: Int,
                            val changedFromPreviousPoll: Boolean,
                            val delay: Long,
                            val limitReached: Boolean)

    /**
     * Check the battery state, and see if its charge has reached the designated limit.
     * @return Number of mSec to wait before next poll, 0L if charge-limit reached.
     */
    @SuppressLint("TimberArgCount")
    fun pollBattery(): BatteryPollResult {
        // We get the limit from SharedPreferences every time because the user can modify the limit while we're running
        val limit = sharedPreferences.getInt(context.getString(R.string.pref_limit), 80)

        val batteryStatus = getBatteryStatus(context)
        Timber.d("pollBattery(): percent:${batteryStatus.percent}%   prev:$prevBatteryChargePercent%  limit:$limit  isCharging:${batteryStatus.isCharging}")

        if (prevBatteryChargePercent == 0) {
            // It's the 1st poll.
            prevBatteryChargePercent = batteryStatus.percent
        }

        // batteryStatus.isCharging is not always reliable so we assume charging is on also if batteryStatus.percent increases.
        return if (batteryStatus.percent >= limit && (batteryStatus.percent > prevBatteryChargePercent || limit < prevBatteryChargeLimit || batteryStatus.isCharging)) {
            BatteryPollResult(batteryStatus.percent, limit, true, 0L, true)
        } else {
            val delay = kotlin.math.max(4000L, 2000L * (limit - batteryStatus.percent))

            var somethingChanged = false

            if (batteryStatus.percent != prevBatteryChargePercent  ||  limit != prevBatteryChargeLimit) {
                prevBatteryChargePercent = batteryStatus.percent
                prevBatteryChargeLimit = limit
                somethingChanged = true
            }
            BatteryPollResult(batteryStatus.percent, limit, somethingChanged, delay, false)
        }
    }
}