package com.getlokalapp.paymentsdk.razorpay.host

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class RazorpayCocoapodsHostPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // The host module applies `org.jetbrains.kotlin.native.cocoapods`, which
        // registers the `podspec` task that generates `<name>.podspec`. We hook
        // its doLast so every regeneration re-adds our transitive dependency —
        // the generator rewrites the file from scratch each run, so this can't be
        // a one-time edit.
        project.plugins.withId("org.jetbrains.kotlin.native.cocoapods") {
            project.tasks.named("podspec").configure { task ->
                task.doLast {
                    appendPodDependency(
                        podspec = project.file("${project.name}.podspec"),
                        pod = "razorpay-pod",
                        version = RAZORPAY_POD_VERSION,
                    )
                }
            }
        }
    }

    private fun appendPodDependency(podspec: File, pod: String, version: String) {
        if (!podspec.exists()) return
        val text = podspec.readText()
        if (text.contains("spec.dependency '$pod'")) return
        // Insert before the podspec's closing `end`.
        val marker = "\nend"
        val at = text.lastIndexOf(marker)
        if (at < 0) return
        val line = "    spec.dependency '$pod', '$version'"
        podspec.writeText(text.substring(0, at) + "\n" + line + text.substring(at))
    }

    private companion object {
        // Tracks razorpay-checkout/build.gradle.kts `iosVendorSdkVersion` — the
        // version the :razorpay-checkout cinterop bindings were generated against,
        // so the linked pod can't drift from the bindings the host consumes.
        const val RAZORPAY_POD_VERSION = "1.4.3"
    }
}
