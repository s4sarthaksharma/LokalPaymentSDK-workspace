package com.getlokalapp.paymentsdk

import android.app.Application
import android.content.Context
import androidx.startup.Initializer
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker

/**
 * AndroidX App Startup entry point for `:shared` — installs [ActivityTracker]
 * at process start with zero host code. App Startup's single
 * `InitializationProvider` (a ContentProvider) runs every declared
 * [Initializer] synchronously, before `Application.onCreate()`, so the tracker
 * is live before any host code and before any gateway registers.
 *
 * This is the SDK's *only* startup provider: each gateway module contributes
 * its own [Initializer] via a manifest `<meta-data>` merged into the same
 * provider, keyed by class name — so no gateway module needs a ContentProvider
 * (or a unique `android:authorities`) of its own. Gateway initializers list
 * this class in their `dependencies()`, guaranteeing the tracker is installed
 * before they run.
 *
 * Keep [create] a bare in-memory setup — it runs during process creation.
 */
class PaymentSdkInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        (context.applicationContext as? Application)?.let(ActivityTracker::install)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
