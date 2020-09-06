package com.liorhass.android.chargecap

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class BatteryStatus(val percent: Int, val isCharging: Boolean)
/**
 * Return the current battery status (charge percent and whether charging or not)
 */
fun getBatteryStatus(context: Context): BatteryStatus {
    // https://developer.android.com/training/monitoring-device-state/battery-monitoring#DetermineChargeState
    val batteryStatusIntent: Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    val scale: Int = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val level: Int = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val percent = 100 * level / scale
//    Timber.d("Level=$level Scale=$scale  Percent=$percent")

    val status: Int =
        batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
            || status == BatteryManager.BATTERY_STATUS_FULL
//
//    // How are we charging?
//    val chargePlug: Int = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
//    val usbCharge: Boolean = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
//    val acCharge: Boolean = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
    return BatteryStatus(percent, isCharging)
}
