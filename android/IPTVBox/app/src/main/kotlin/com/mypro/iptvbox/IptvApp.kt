package com.mypro.iptvbox

import android.app.Application
import com.mypro.iptvbox.data.Repo
import com.mypro.iptvbox.data.Settings

class IptvApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        repo = Repo(this, settings)
    }

    companion object {
        lateinit var instance: IptvApp
            private set
        val repo: Repo get() = instance.repo
        val settings: Settings get() = instance.settings
    }
}
