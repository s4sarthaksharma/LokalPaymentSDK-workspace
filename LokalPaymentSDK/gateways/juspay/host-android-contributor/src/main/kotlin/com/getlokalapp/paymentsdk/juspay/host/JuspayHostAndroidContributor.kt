package com.getlokalapp.paymentsdk.juspay.host

import `in`.juspay.hypersdk.HyperSdkPluginExtension
import com.getlokalapp.paymentsdk.host.LokalGatewayHostAndroidContributor
import com.getlokalapp.paymentsdk.host.transitivelyDependsOn
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
 * **Self-gates on the host actually shipping Juspay**, in the same spirit as the iOS
 * `JuspayHostContributor`: it does nothing unless the app *transitively* depends on the
 * `:juspay` gateway ([SDK_GROUP]:[JUSPAY_MODULE]) — usually via a shared KMP module (e.g.
 * `composeApp`), not a direct app-module declaration. The reusable
 * [transitivelyDependsOn] helper does that graph walk; see its docs for why it runs in
 * `afterEvaluate` and avoids classpath resolution. So a host can apply the vendor-agnostic
 * `lokal-payment-android` umbrella without pulling in HyperSDK — only actually using the
 * Juspay gateway turns it on. Applying `hypersdk.plugin` from that same `afterEvaluate`
 * still wires the client id before HyperSDK's own `afterEvaluate` reads it (the forwarding
 * is set synchronously, right after the apply, so it precedes the `afterEvaluate` HyperSDK
 * registers).
 *
 * Once the gate passes, a missing [CLIENT_ID_PROPERTY] is a hard error (never a silent
 * skip): a host that depends on Juspay intends to use it. The client id is sourced from
 * the [CLIENT_ID_PROPERTY] gradle property (set once in the host's `gradle.properties`,
 * or via `-P`/`ORG_GRADLE_PROJECT_…`), so it's shared across the host's modules with no
 * per-module DSL.
 */
class JuspayHostAndroidContributor : LokalGatewayHostAndroidContributor {

    override fun contribute(target: Project) {
        target.plugins.withId("com.android.application") {
            target.afterEvaluate { project ->
                // Self-gate: only wire HyperSDK when the app transitively ships the Juspay
                // gateway (usually via a shared KMP module, not a direct app-module dep).
                if (!project.transitivelyDependsOn(SDK_GROUP, JUSPAY_MODULE)) return@afterEvaluate

                // Juspay IS in use, so fail loud (rather than skip) if the merchant client
                // id is missing, with a clear message instead of hypersdk's own late error.
                val clientId = project.providers.gradleProperty(CLIENT_ID_PROPERTY).orNull
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException(
                        "Juspay requires the '$CLIENT_ID_PROPERTY' Gradle property to wire " +
                            "HyperSDK into your Android app. Set it in the host's " +
                            "gradle.properties (or pass -P$CLIENT_ID_PROPERTY=…). Remove the " +
                            "'$JUSPAY_MODULE' dependency if you don't use Juspay on Android.",
                    )

                project.pluginManager.apply("hypersdk.plugin")

                // hypersdk.plugin reads its own extension in an afterEvaluate it registers
                // during the apply() above; setting the values synchronously here keeps the
                // forwarding ahead of that read.
                val hyperSdkExtension = project.extensions.getByType(HyperSdkPluginExtension::class.java)
                hyperSdkExtension.clientId.set(clientId)
                // Always tracks :juspay's compile-time `juspay-hypersdk` version (see
                // LokalPaymentSDK/gradle/libs.versions.toml) — not host-configurable, so the
                // runtime SDK this plugin fetches can never drift from what :juspay compiled
                // against.
                hyperSdkExtension.sdkVersion.set(DEFAULT_SDK_VERSION)
            }
        }
    }

    private companion object {
        const val DEFAULT_SDK_VERSION = "2.2.8-rc.01"

        // Host-set gradle property carrying the Juspay merchant client id (host's
        // gradle.properties, or -P/ORG_GRADLE_PROJECT_…).
        const val CLIENT_ID_PROPERTY = "juspayClientId"

        // The :juspay gateway's published coordinate — the self-gate signal. Same
        // group/name the iOS JuspayHostContributor checks for.
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"
        const val JUSPAY_MODULE = "juspay"
    }
}
