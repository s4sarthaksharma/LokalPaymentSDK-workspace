package com.getlokalapp.paymentsdk.juspay.host

import `in`.juspay.hypersdk.HyperSdkPluginExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Juspay's **Android** host plugin. A host applies this on its `com.android.application`
 * module; it applies `hypersdk.plugin` (which injects the HyperSDK runtime jars +
 * merchant assets into the APK), forwards the host's Juspay client id into it, and pins
 * the runtime SDK version to :juspay's compile-time `juspay-hypersdk` (not
 * host-configurable, so the fetched runtime SDK can't drift from what :juspay compiled
 * against).
 *
 * The iOS half of Juspay's host wiring is NOT here — it's a `LokalGatewayHostContributor`
 * (`JuspayHostContributor`) dispatched by the umbrella `lokal-payment` plugin, so a host
 * applies only `lokal-payment` on its iOS module. This plugin stays Android-specific
 * because `hypersdk.plugin` must be applied *eagerly* on the *application* module, which
 * the umbrella's afterEvaluate contributor dispatch can't do.
 *
 * The client id is sourced from the [CLIENT_ID_PROPERTY] gradle property (set once in the
 * host's `gradle.properties`, or via `-P`/`ORG_GRADLE_PROJECT_…`), so it's shared across
 * the host's modules with no per-module DSL. If richer, typed build-time configuration is
 * ever needed, this is where a `lokalPaymentSdk {}`-style extension would come back.
 */
class JuspayAndroidHostPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            project.pluginManager.apply("hypersdk.plugin")

            // hypersdk.plugin reads its own extension in an afterEvaluate registered
            // during the apply() call above, which runs before any afterEvaluate
            // registered here — so the forwarding must be wired eagerly. gradleProperty
            // returns a lazy Provider, so the value is read when hypersdk queries it,
            // not now (and an unset property surfaces through hypersdk's own error).
            val hyperSdkExtension = project.extensions.getByType(HyperSdkPluginExtension::class.java)
            hyperSdkExtension.clientId.set(project.providers.gradleProperty(CLIENT_ID_PROPERTY))
            // Always tracks :juspay's compile-time `juspay-hypersdk` version (see
            // LokalPaymentSDK/gradle/libs.versions.toml) — not host-configurable, so
            // the runtime SDK this plugin fetches can never drift from what :juspay
            // compiled against.
            hyperSdkExtension.sdkVersion.set(DEFAULT_SDK_VERSION)
        }
    }

    private companion object {
        const val DEFAULT_SDK_VERSION = "2.2.8-rc.01"

        // Host-set gradle property carrying the Juspay merchant client id (host's
        // gradle.properties, or -P/ORG_GRADLE_PROJECT_…).
        const val CLIENT_ID_PROPERTY = "juspayClientId"
    }
}
