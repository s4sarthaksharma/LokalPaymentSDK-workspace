package com.getlokalapp.paymentsdk.hostcontext

import android.app.Application
import com.getlokalapp.paymentsdk.SdkInitProvider

/**
 * Startup hook (see [SdkInitProvider]) that registers [ActivityTracker]
 * before any host code runs. Declared in `:shared`'s own AndroidManifest.xml,
 * merged into the host automatically — every gateway module that depends on
 * `:shared` gets the current-Activity tracker for free.
 */
internal class ActivityTrackerInitProvider : SdkInitProvider() {

    override fun onAppStart() {
        val application = context?.applicationContext as? Application ?: return
        ActivityTracker.install(application)
    }
}
