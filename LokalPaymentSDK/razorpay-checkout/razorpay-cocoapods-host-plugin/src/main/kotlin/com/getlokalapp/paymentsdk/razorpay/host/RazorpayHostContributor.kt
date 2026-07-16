package com.getlokalapp.paymentsdk.razorpay.host

import com.getlokalapp.paymentsdk.cocoapods.PodspecEditor
import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import org.gradle.api.Project

/**
 * Razorpay's build-time contribution to an iOS host: appends
 * `spec.dependency 'razorpay-pod'` to the host module's generated podspec so the
 * vendor pod is pulled transitively from the CocoaPods trunk — the host never names
 * razorpay-pod in its Podfile. Discovered by the umbrella
 * `com.getlokalapp.paymentsdk.lokal-payment` plugin via ServiceLoader.
 *
 * Self-gates on the host actually depending on :razorpay-checkout. This jar is
 * always on the buildscript classpath (the umbrella depends on it), so "razorpay
 * not used" is an early return: nothing is added to the podspec and no razorpay-pod
 * is linked unless razorpay-checkout is imported.
 *
 * Deliberately does NOT add a `pod(...)` cinterop to the host module: the Kotlin
 * bindings already ride in via the published :razorpay-checkout klib (Maven), so
 * all the host needs is the pod linked at the app target.
 */
class RazorpayHostContributor : LokalGatewayHostContributor {

    override fun contribute(target: Project, config: LokalPaymentSdkExtension) {
        val importsRazorpay = target.configurations
            .flatMap { it.dependencies }
            .any { it.group == SDK_GROUP && it.name == RAZORPAY_CHECKOUT_MODULE }
        if (!importsRazorpay) return

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
                        pod = "razorpay-pod",
                        // VENDOR_SDK_VERSION is generated from libs.versions.toml's
                        // razorpay-pod-ios entry (see build.gradle.kts).
                        version = VENDOR_SDK_VERSION,
                    )
                }
            }
        }
    }

    private companion object {
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"
        const val RAZORPAY_CHECKOUT_MODULE = "razorpay-checkout"
    }
}
