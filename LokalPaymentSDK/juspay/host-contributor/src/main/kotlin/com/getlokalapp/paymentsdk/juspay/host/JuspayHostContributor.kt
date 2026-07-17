package com.getlokalapp.paymentsdk.juspay.host

import com.getlokalapp.paymentsdk.cocoapods.PodspecEditor
import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import org.gradle.api.Project

/**
 * Juspay's build-time contribution to an iOS host: appends `spec.dependency 'HyperSDK'`
 * to the host module's generated podspec so the vendor pod is pulled transitively from
 * the CocoaPods trunk — the host never names HyperSDK in its Podfile. Discovered by the
 * umbrella `com.getlokalapp.paymentsdk.lokal-payment` plugin via ServiceLoader, exactly
 * like Razorpay's contributor — so a host applies only `lokal-payment` on its iOS
 * module, nothing Juspay-specific.
 *
 * Self-gates on the host actually depending on :juspay. This jar is always on the
 * buildscript classpath (the umbrella depends on it), so "Juspay not used" is an early
 * return: nothing is added to the podspec and no HyperSDK pod is linked unless :juspay
 * is imported.
 *
 * This is only the **iOS** half of Juspay's host wiring. The Android half (applying
 * `hypersdk.plugin` + forwarding the client id) lives in the separate
 * `com.getlokalapp.paymentsdk.juspay-android-host` plugin, applied on the host's
 * `com.android.application` module — it can't fold in here, because that work must run
 * eagerly on the application module, which this umbrella-dispatched, afterEvaluate
 * contributor is the wrong phase/module for.
 *
 * Deliberately does NOT add a `pod(...)` cinterop to the host module: the Kotlin
 * bindings already ride in via the published :juspay klib (Maven), and a cinterop here
 * would drag the host's iOS compile through HyperSDK's synthetic-build "Validate
 * Mandatory Files" gate. The Podfile `post_install` Fuse.rb step (merchant-asset
 * download) can't be injected from Gradle, so it stays in the host Podfile alongside
 * MerchantConfig.txt.
 */
class JuspayHostContributor : LokalGatewayHostContributor {

    override fun contribute(target: Project, config: LokalPaymentSdkExtension) {
        val importsJuspay = target.configurations
            .flatMap { it.dependencies }
            .any { it.group == SDK_GROUP && it.name == JUSPAY_MODULE }
        if (!importsJuspay) return

        // The host module applies `org.jetbrains.kotlin.native.cocoapods`, which
        // registers the `podspec` task that generates `<name>.podspec`. Hook its
        // doLast so every regeneration re-adds our transitive dependency — the
        // generator rewrites the file from scratch each run, so this can't be a
        // one-time edit.
        target.plugins.withId("org.jetbrains.kotlin.native.cocoapods") {
            target.tasks.named("podspec").configure { task ->
                task.doLast {
                    PodspecEditor.upsertPodDependency(
                        podspec = target.file("${target.name}.podspec"),
                        pod = "HyperSDK",
                        // VENDOR_SDK_VERSION is generated from libs.versions.toml's
                        // juspay-pod-ios entry (see build.gradle.kts).
                        version = VENDOR_SDK_VERSION,
                    )
                }
            }
        }
    }

    private companion object {
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"
        const val JUSPAY_MODULE = "juspay"
    }
}
