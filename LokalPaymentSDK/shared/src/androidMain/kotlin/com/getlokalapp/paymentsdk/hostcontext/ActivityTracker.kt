package com.getlokalapp.paymentsdk.hostcontext

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks the current foreground Activity via Application-level lifecycle
 * callbacks so no gateway module's host ever has to supply one explicitly —
 * installed once by `PaymentSdkInitializer` at process start. A
 * [WeakReference] avoids pinning a destroyed Activity in memory. Lives in
 * `:shared` so every Android gateway module (Juspay, Razorpay Checkout,
 * Razorpay UPI Intent) can read the current Activity the same way.
 */
object ActivityTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var currentRef: WeakReference<Activity>? = null

    @Volatile
    private var onAvailable: (() -> Unit)? = null

    private val onDestroyedListeners = CopyOnWriteArrayList<(Activity) -> Unit>()

    val current: Activity? get() = currentRef?.get()

    /**
     * Notifies [listener] whenever any Activity is destroyed, so gateway
     * clients holding an Activity-bound resource (e.g. Juspay's
     * HyperServiceHolder) can release it instead of leaking the Activity.
     */
    fun addOnDestroyedListener(listener: (Activity) -> Unit) {
        onDestroyedListeners += listener
    }

    fun removeOnDestroyedListener(listener: (Activity) -> Unit) {
        onDestroyedListeners -= listener
    }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    /**
     * Runs [action] now if an Activity is already tracked, else defers it
     * to the moment one becomes available (e.g. a bootstrap-time gateway
     * init call made before any Activity has been created yet). Only one
     * deferred action is kept at a time.
     */
    fun runWhenAvailable(action: () -> Unit) {
        if (current != null) {
            action()
        } else {
            onAvailable = action
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        track(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        track(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        track(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (currentRef?.get() === activity) currentRef = null
        onDestroyedListeners.forEach { it(activity) }
    }

    private fun track(activity: Activity) {
        currentRef = WeakReference(activity)
        val pending = onAvailable ?: return
        onAvailable = null
        pending()
    }
}
