// Emulating battery: https://medium.com/@kaushiknsanji/android-battery-mocking-tutorial-988927fa4a35
//    help:         adb shell dumpsys battery -h
//    set to level: adb shell dumpsys battery set level 42
//    charge:       adb shell dumpsys battery set ac 1
//    stop charge:  adb shell dumpsys battery set ac 0
//    idle mode:    https://gist.github.com/y-polek/febff143df8dd92f4ed2ce4035c99248
//       adb shell dumpsys deviceidle force-idle   I found that "adb shell dumpsys deviceidle step deep" does not cause any real deep sleep side effects. Only if I enter: adb shell dumpsys deviceidle force-idle my app behaves as in real deep sleep - network connections get disrupted and app thinks its offline.
//       adb shell dumpsys deviceidle step deep    Enter Deep Doze mode (should be called several times to pass all phases)
//       adb shell dumpsys deviceidle step light   Enter Light Doze mode (should be called several times to pass all phases)
//       adb shell dumpsys deviceidle              Dump Doze mode info
//       adb shell dumpsys deviceidle get deep     Get status of Deep Doze mode
//       adb shell dumpsys deviceidle get light    Get status of Light Doze mode
//
// Design:
//  https://stackoverflow.com/a/53438569/1071117
//  Have a periodic job with WorkManager that runs every 15 minutes conditioned on "charging" (which
//  makes sure the job runs when the device is connected to power). This job starts a
//  long-running worker
//  The long-running worker monitors the battery charge percentage periodically.
//  https://developer.android.com/topic/libraries/architecture/workmanager/advanced/long-running
//
// Wakeup device:
// look in TimerModel.java LN#712
// and even better:  https://stackoverflow.com/a/41418325/1071117
//
//
package com.liorhass.android.chargecap

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import androidx.work.*
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.pow


class MainActivity : AppCompatActivity() {
    private var ringtoneMediaPlayer: MediaPlayer? = null
    private val handler = Handler()
    private var batteryPercentageTextView: TextView? = null
    private var limitTextView: TextView? = null
    private var limit = 0

    companion object {
        const val PERIODIC_WORKER_UNIQUE_NAME = "ChargeCapWorker_Periodic"
        const val BATTERY_MONITORING_WORKER_UNIQUE_NAME = "ChargeCapWorker_BatMon"
        const val NOTIFICATIONS_CHANNEL_ID = "CC_NC"
        const val REQUEST_CODE_SELECT_RINGTONE = 333
    }

    private val pollBatteryRunnable = object: Runnable {
        override fun run() {
//            Timber.d("pollBatteryRunnable: going to poll")
            val batteryStatus = getBatteryStatus(this@MainActivity)
            batteryPercentageTextView?.text = getString(R.string.current_percent_msg, batteryStatus.percent)
            handler.postDelayed(this, 3000) //todo: 3000?
        }
    }

    private val arrowsAnimatorRunnable = object: Runnable {
        override fun run() {
//            Timber.d("arrowsAnimatorRunnable: currentArrow=$currentArrow")
            arrowsListLeft[currentArrow].visibility = View.INVISIBLE
            arrowsListRight[currentArrow].visibility = View.INVISIBLE
            currentArrow = (++currentArrow).rem(4)
            arrowsListLeft[currentArrow].visibility = View.VISIBLE
            arrowsListRight[currentArrow].visibility = View.VISIBLE
            handler.postDelayed(this, 200)
        }
    }
    private lateinit var arrowsListLeft: List<ImageView>
    private lateinit var arrowsListRight: List<ImageView>
    private var currentArrow = 3

    private var deltaX: Float = 0.0F
    private var deltaY: Float = 0.0F
    private var actionDownX: Float = 0.0F
    private var actionDownY: Float = 0.0F

    private var stopToneImage: ImageView? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        batteryPercentageTextView = findViewById(R.id.currentCharge)

        limitTextView = findViewById(R.id.limitText)
        val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        limit = sharedPreferences.getInt(getString(R.string.pref_limit), 80)
        limitTextView?.text = getString(R.string.limit_msg, limit)
        limitTextView?.setOnClickListener {
            selectChargeLimit()
        }
        findViewById<ImageView>(R.id.incrementLimit).setOnClickListener { setChargeLimit(limit+1) }
        findViewById<ImageView>(R.id.decrementLimit).setOnClickListener { setChargeLimit(limit-1) }

        initNotificationChannel() // On Android 8 (Oreo) and up

        schedulePeriodicWorker()

        stopToneImage = findViewById(R.id.stopToneImage)
        stopToneImage?.setOnTouchListener { view, motionEvent ->
            // from https://developer.android.com/training/gestures/scale#drag
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    actionDownX = motionEvent.rawX
                    actionDownY = motionEvent.rawY
                    deltaX = motionEvent.rawX - view.x
                    deltaY = motionEvent.rawY - view.y
                }
                MotionEvent.ACTION_MOVE -> {
                    view.x = motionEvent.rawX - deltaX
                    view.y = motionEvent.rawY - deltaY
                    val distanceSqr = (motionEvent.rawX - actionDownX).pow(2) + (motionEvent.rawY - actionDownY).pow(2)
                    Timber.d("distance = $distanceSqr")
                    if (distanceSqr > 60000.0) {
                        stopTone()
                        stopArrowsAnimation()
                        intent.removeExtra(ACTION_SHOULD_ALERT_THE_USER) // Prevent redundant alert from our onStart() in case the user gets an alert, silence it and then after some time re-opens this activity
//                        intent.removeExtra("com.liorhass.android.chargecap.should_show_stop_button") //todo str // Prevent redundant alert from our onStart() in case the user gets an alert, silence it and then after some time re-opens this activity
                    }
                    view.invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    animateStopToneButtonBackHome(view)
                }
            }
            true
        }
        arrowsListLeft = listOf(
            findViewById(R.id.left1),
            findViewById(R.id.left2),
            findViewById(R.id.left3),
            findViewById(R.id.left4)
        )
        arrowsListRight = listOf(
            findViewById(R.id.right1),
            findViewById(R.id.right2),
            findViewById(R.id.right3),
            findViewById(R.id.right4)
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Timber.d("onNewIntent()")
        setIntent(intent)
        handleIntent()
    }

    override fun onStart() {
        super.onStart()
        Timber.d("onStart()")

        handleIntent()

        // Start polling battery percentage
        handler.postDelayed(pollBatteryRunnable, 100)
    }

    override fun onResume() {
        super.onResume()
        Timber.d("onResume()")
    }

    override fun onStop() {
        super.onStop()
        Timber.d("onStop()")

        // Stop polling battery percentage
        handler.removeCallbacks(pollBatteryRunnable)

        // Stop tone and release media-player (if active)
        stopTone()
    }

    // Needed so when the screen is off/locked we can turn it on and run over the lock-screen
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Timber.d("onAttachedToWindow()")

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 -> {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.O -> {
                @Suppress("DEPRECATION")
                this.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            }
            else -> {
                @Suppress("DEPRECATION")
                this.window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Timber.d("onPause()")
//        unregisterReceiver(receiver)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.main_activity_menu, menu)
        // On Android 8 (Oreo) and up, tone selection is done in the system's notification settings screen. On older versions we let the user select the tone ini the app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.notification).isVisible = true
        } else {
            menu.findItem(R.id.selectAlertTone).isVisible = true
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.selectChargeLimit -> {
                selectChargeLimit()
                true
            }
            R.id.selectAlertTone -> {
                selectRingtone()
                true
            }
            R.id.notification -> {
                openSystemNotificationSettings()
                true
            }
            R.id.startBatteryMonitor -> {
//                startService(serviceIntent)
                startBatteryMonitoringWorker()
                true
            }
            R.id.stopBatteryMonitor -> {
                stopBatteryMonitoringWorker()
                true
            }
            R.id.about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun schedulePeriodicWorker() {
        Timber.d("schedulePeriodicWorker()")
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .build()

        val workerParams: Data = workDataOf("WORKER_TYPE" to 1) //todo
        val periodicWorkRequest = PeriodicWorkRequestBuilder<PeriodicWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workerParams)
            .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(PERIODIC_WORKER_UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, periodicWorkRequest)
    }

    private fun startBatteryMonitoringWorker() {
        Timber.d("startBatteryMonitoringWorker()")
        val workerParams: Data = workDataOf("WORKER_TYPE" to 2) //todo
        val oneTimeWorkRequest =
            OneTimeWorkRequestBuilder<BatteryMonitoringWorker>()
                .setInputData(workerParams)
                .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork(BATTERY_MONITORING_WORKER_UNIQUE_NAME, ExistingWorkPolicy.REPLACE, oneTimeWorkRequest)
    }
    private fun stopBatteryMonitoringWorker() {
        WorkManager.getInstance(this).cancelUniqueWork(BATTERY_MONITORING_WORKER_UNIQUE_NAME)
    }

    private fun selectChargeLimit() {
        val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val limit = sharedPreferences.getInt(getString(R.string.pref_limit), 80)

        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.charge_limit))

        val dialogView = layoutInflater.inflate(R.layout.dialog_set_charge_limit, null)
        val textInputLayout: TextInputLayout? = dialogView.findViewById(R.id.textInputLayout)
        val editText: TextInputEditText? = dialogView.findViewById(R.id.editText)
        editText?.setText(limit.toString())
        builder.setView(dialogView)
        builder.setPositiveButton(getString(R.string.ok)) { _, _ ->
            // Nothing here because we override it later. See below.
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }

        val dialog = builder.create()
        dialog.show()

        // We sometimes want the dialog to stay open after the OK button in order to be able to
        // show an error message. In order to achieve that we override the button's click listener.
        // See https://stackoverflow.com/a/15619098/1071117
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val userInput = editText?.text.toString()
            val errorMsg = validateChargeLimitUserInput(userInput)
            if (errorMsg == null) {
                // User input is OK
                setChargeLimit(userInput.toInt())
                dialog.dismiss()
            } else {
                textInputLayout?.error = errorMsg
            }
        }
    }

    private fun selectRingtone() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alert tone") //todo str
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)

        // Make sure that the current ringtone is selected in the dialog
        val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val toneUriStr = sharedPreferences.getString(getString(R.string.pref_tone_uri), null)
        if (toneUriStr != null) {
            val toneUri = try {
                Uri.parse(toneUriStr)
            } catch (e: Exception) {
                Timber.e(e, "Can't parse Uri: $toneUriStr")
                null
            }
            if (toneUri != null) {
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, toneUri)
            }
        }

        startActivityForResult(intent, REQUEST_CODE_SELECT_RINGTONE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SELECT_RINGTONE && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            // If the user explicitly select "None" in the ringtone list, uri is null
            val uriStr = uri?.toString() ?: "none" //todo const
            val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            sharedPreferences.edit().putString(getString(R.string.pref_tone_uri), uriStr).apply()
            Timber.d("onActivityResult(): Uri=$uriStr")
        }
    }

    private fun startTone() {
        stopTone()
        val toneUri = getToneUri() ?: return  // If no tone-uri, there's nothing for us to do

        // Instead of simply using a Ringtone like this:
        //     ringTone = RingtoneManager.getRingtone(applicationContext, toneUri)
        //     ringTone.play()
        // we have to use a MediaPlayer because Ringtone doesn't support looping.
        Timber.d("startTone(): Ringtone: $toneUri")
        ringtoneMediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, toneUri)
            isLooping = true
            // Set USAGE_ALARM so ringtone is playing as an ALARM (e.g. use the ALARM volume settings, not the MEDIA's)
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            prepare()
            start()
        }
    }

    private fun stopTone() {
        Timber.d("stopTone()")
        ringtoneMediaPlayer?.stop()
        ringtoneMediaPlayer?.release()
        ringtoneMediaPlayer = null
    }

    private fun startArrowsAnimation() {
        handler.post(arrowsAnimatorRunnable)
    }

    private fun stopArrowsAnimation() {
        handler.removeCallbacks(arrowsAnimatorRunnable)
        arrowsListLeft[currentArrow].visibility = View.INVISIBLE
        arrowsListRight[currentArrow].visibility = View.INVISIBLE
    }

    private fun getToneUri(): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(NOTIFICATIONS_CHANNEL_ID)
            if (channel != null) {
                channel.sound
            } else {
                Settings.System.DEFAULT_ALARM_ALERT_URI
            }
        } else {
            val sharedPreferences: SharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(this)
            val toneUriStr = sharedPreferences.getString(getString(R.string.pref_tone_uri), null)
            if (toneUriStr == "none") { //todo const
                // The user explicitly selected "None" in the ringtone list
                return null
            }
            var toneUri: Uri? = null
            if (toneUriStr != null) {
                toneUri = try {
                    Uri.parse(toneUriStr)
                } catch (e: Exception) {
                    Timber.e(e, "Can't parse Uri: $toneUriStr")
                    null
                }
            }
            if (toneUri == null) {
                // Use system default ringtone
                toneUri = Settings.System.DEFAULT_ALARM_ALERT_URI
            }
            toneUri
        }
    }

    private fun animateStopToneButtonBackHome(view: View) {
        Timber.d("animateBack()")
        val finalX = actionDownX - deltaX
        val finalY = actionDownY - deltaY
        val path = Path().apply {
            moveTo(view.x, view.y)
            lineTo(finalX, finalY)
        }
        ObjectAnimator.ofFloat(view, View.X, View.Y, path).apply {
            duration = 300 // in mSec
            start()
        }
    }

    private fun setChargeLimit(_limit: Int) {
        limit = _limit
        val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.edit().putInt(getString(R.string.pref_limit), limit).apply()
        limitTextView?.text = getString(R.string.limit_msg, limit)
    }

    private fun validateChargeLimitUserInput(userInput: String): String? {
        val chargeLimit: Int
        try {
            chargeLimit = userInput.toInt()
        } catch (e:  NumberFormatException) {
            Timber.e("Can't convert to Int: '$userInput'")
            return getString(R.string.illegal_number)
        }
        if (chargeLimit > 100  ||  chargeLimit < 0) {
            return getString(R.string.must_be_between_0_and_100)
        }
        return null  // Indicates that the input is valid
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
        .setTitle(getString(R.string.about_title))
        .setMessage(getString(R.string.about_msg))
        .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
            dialog.cancel()
        }
        .create()
        .show()
    }

    private fun initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = NotificationManagerCompat.from(applicationContext)
            // Check if channel already exists. In any event, Android only allow us to create it once, and we cannot modify it afterwards.
            if (notificationManager.getNotificationChannel(NOTIFICATIONS_CHANNEL_ID) == null) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM) // USAGE_NOTIFICATION_RINGTONE ?
                    .build()

                val channel = NotificationChannel(NOTIFICATIONS_CHANNEL_ID,
                    "Charge limit reached", //todo: channel name str
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Time to unplug device from charger" //todo str
                    setSound(getToneUri(), audioAttributes)
                    lockscreenVisibility = VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun handleIntent() {
        Timber.d("handleIntent(): intentToHandle=$intent")
        if (intent.getBooleanExtra(ACTION_SHOULD_ALERT_THE_USER,false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // On Android 8 (Oreo) and up we're awaken by a notification, so we cancel it here.
                NotificationManagerCompat.from(applicationContext).cancel(USER_ALERT_NOTIFICATION_ID)
            }
            stopToneImage?.visibility = View.VISIBLE
            startTone()
            startArrowsAnimation()
        }
    }

    private fun openSystemNotificationSettings() {
        val intent = Intent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            intent.action = "android.settings.APP_NOTIFICATION_SETTINGS"
            intent.putExtra("app_package", packageName)
            intent.putExtra("app_uid", applicationInfo?.uid)
        }
        startActivity(intent)
    }
}