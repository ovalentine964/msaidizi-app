package com.msaidizi.app.ui

import android.app.Application
import com.msaidizi.app.data.database.AppDatabase
import dagger.hilt.android.HiltAndroidApp

/**
 * MsaidiziApp — Application class for Hilt DI.
 * 
 * Database is provided via Hilt DI (AppModule).
 * WorkerIdProvider generates and persists unique worker ID on first install.
 */
@HiltAndroidApp
class MsaidiziApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Database and dependencies are initialized via Hilt DI
    }
}
