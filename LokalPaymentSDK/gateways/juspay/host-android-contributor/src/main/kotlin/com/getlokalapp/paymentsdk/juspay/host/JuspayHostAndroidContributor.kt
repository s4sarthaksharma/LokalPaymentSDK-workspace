package com.getlokalapp.paymentsdk.juspay.host

import `in`.juspay.hypersdk.HyperSdkPluginExtension
import com.getlokalapp.paymentsdk.host.LokalGatewayHostAndroidContributor
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Juspay's **Android** host contribution: on the host's `com.android.application`
 * module it applies `hypersdk.plugin` (which injects the HyperSDK runtime jars +
 * merchant assets into the APK), forwards the host's Juspay client id into it, and pins
 * the runtime SDK version to :juspay's compile-time `juspay-hypersdk` (not
 * host-configurable, so the fetched runtime SDK can't drift from what :juspay compiled
 * against).
 *
 * Discovered by the umbrella `com.getlokalapp.paymentsdk.lokal-payment-android`
 * plugin via ServiceLoader (registered in
 * `META-INF/services/com.getlokalapp.paymentsdk.host.LokalGatewayHostAndroidContributor`);
 * the Android sibling of the iOS `JuspayHostContributor` (dispatched by `lokal-payment`)
 * and the settings-phase `JuspaySettingsContributor` (dispatched by
 * `lokal-payment-settings`).
 *
 * **Gated by the umbrella plugin on [module] (`juspay`)**, in the same spirit as the iOS
 * `JuspayHostContributor`: the umbrella scans the app's transitive dependency graph once and
 * only calls this when the app ships the `:juspay` gateway — usually via a shared KMP module
 * (e.g. `composeApp`), not a direct app-module declaration. So a host can apply the
 * vendor-agnostic `lokal-payment-android` umbrella without pulling in HyperSDK; only actually
 * using the Juspay gateway turns it on. [contribute] runs already on the
 * `com.android.application` module inside the umbrella's `afterEvaluate`, so applying
 * `hypersdk.plugin` here still wires the client id before HyperSDK's own `afterEvaluate` reads
 * it (the forwarding is set synchronously, right after the apply, so it precedes the
 * `afterEvaluate` HyperSDK registers).
 *
 * Because it's only called when Juspay is shipped, a missing [CLIENT_ID_PROPERTY] is a hard
 * error (never a silent skip): a host that depends on Juspay intends to use it. The client id
 * is sourced from the [CLIENT_ID_PROPERTY] gradle property (set once in the host's
 * `gradle.properties`, or via `-P`/`ORG_GRADLE_PROJECT_…`), so it's shared across the host's
 * modules with no per-module DSL.
 */
class JuspayHostAndroidContributor : LokalGatewayHostAndroidContributor {

    override val module = OWNED_MODULE

    override fun contribute(target: Project) {
        // The umbrella plugin already invokes this only on the `com.android.application`
        // module, inside its afterEvaluate, and only when the app transitively ships :juspay —
        // so there's no withId wrapper or self-gate here. Juspay IS in use, so fail loud
        // (rather than skip) if the merchant client id is missing, with a clear message
        // instead of hypersdk's own late error.
        val clientId = target.providers.gradleProperty(CLIENT_ID_PROPERTY).orNull
            ?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "Juspay requires the '$CLIENT_ID_PROPERTY' Gradle property to wire " +
                    "HyperSDK into your Android app. Set it in the host's " +
                    "gradle.properties (or pass -P$CLIENT_ID_PROPERTY=…). Remove the " +
                    "'$OWNED_MODULE' dependency if you don't use Juspay on Android.",
            )

        target.pluginManager.apply("hypersdk.plugin")

        // hypersdk.plugin reads its own extension in an afterEvaluate it registers during the
        // apply() above; setting the values synchronously here (still inside the umbrella's
        // app-module afterEvaluate) keeps the forwarding ahead of that read.
        val hyperSdkExtension = target.extensions.getByType(HyperSdkPluginExtension::class.java)
        hyperSdkExtension.clientId.set(clientId)
        // Always tracks :juspay's compile-time `juspay-hypersdk` version (see
        // LokalPaymentSDK/gradle/libs.versions.toml) — not host-configurable, so the
        // runtime SDK this plugin fetches can never drift from what :juspay compiled
        // against.
        hyperSdkExtension.sdkVersion.set(DEFAULT_SDK_VERSION)
    }

    private companion object {
        const val DEFAULT_SDK_VERSION = "2.2.8-rc.01"

        // Host-set gradle property carrying the Juspay merchant client id (host's
        // gradle.properties, or -P/ORG_GRADLE_PROJECT_…).
        const val CLIENT_ID_PROPERTY = "juspayClientId"
    }
}
