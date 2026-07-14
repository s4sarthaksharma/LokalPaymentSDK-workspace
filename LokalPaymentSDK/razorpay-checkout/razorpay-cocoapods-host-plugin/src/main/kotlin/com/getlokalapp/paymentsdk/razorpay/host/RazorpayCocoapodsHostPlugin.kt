package com.getlokalapp.paymentsdk.razorpay.host

import com.getlokalapp.paymentsdk.cocoapods.PodspecEditor
import org.gradle.api.Plugin
import org.gradle.api.Project

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
                    PodspecEditor.upsertPodDependency(
                        podspec = project.file("${project.name}.podspec"),
                        pod = "razorpay-pod",
                        // VENDOR_SDK_VERSION is generated from libs.versions.toml's
                        // razorpay-pod-ios entry (see build.gradle.kts).
                        version = VENDOR_SDK_VERSION,
                    )
                }
            }
        }
    }
}
