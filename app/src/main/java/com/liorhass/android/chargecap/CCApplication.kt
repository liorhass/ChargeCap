package com.liorhass.android.chargecap

import android.app.Application
import timber.log.Timber

class CCApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}