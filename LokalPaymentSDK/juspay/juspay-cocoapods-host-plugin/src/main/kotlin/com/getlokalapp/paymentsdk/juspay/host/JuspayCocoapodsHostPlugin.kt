package com.getlokalapp.paymentsdk.juspay.host

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class JuspayCocoapodsHostPlugin : Plugin<Project> {

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
                        pod = "HyperSDK",
                        version = HYPER_SDK_POD_VERSION,
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
        // Tracks juspay/build.gradle.kts `iosVendorSdkVersion` — the HyperSDK pod
        // version the :juspay cinterop bindings were generated against, so the
        // linked pod can't drift from the bindings the host consumes.
        const val HYPER_SDK_POD_VERSION = "2.2.8.1"
    }
}
