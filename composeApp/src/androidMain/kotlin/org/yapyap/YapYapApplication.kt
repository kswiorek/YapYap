package org.yapyap

import android.app.Application
import android.content.Context

class YapYapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        var appContext: Context? = null
            private set
    }
}
