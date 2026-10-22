package com.zeoffice.einkreader

import android.app.Application

class EinkReaderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: EinkReaderApp
            private set
    }
}
