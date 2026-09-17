package com.pocketsteward.app

import android.app.Application
import com.pocketsteward.app.di.AppContainer

class PocketStewardApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
