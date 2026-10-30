package com.omarchyconnect.app

import android.app.Application
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.Prefs
import com.omarchyconnect.app.service.LinkService

class OmarchyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Link.init(this)
        if (Prefs.paired) {
            // Android 12+ interdit de démarrer un service de premier plan en
            // arrière-plan : on laisse le lien se relancer au prochain lancement.
            try {
                LinkService.start(this)
            } catch (t: Throwable) {
                Link.log("warn", "foreground service not startable now: ${t.javaClass.simpleName}")
            }
        }
    }
}
