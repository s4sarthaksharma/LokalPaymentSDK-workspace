package com.getlokalapp.paymentsdk.buildsrc

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * Publishes a first-party iOS module's Swift source as an `iossrc`-classifier artifact on
 * this module's existing Maven coordinate — the same idiom as the auto-generated
 * `-sources.jar`, so it shares one artifactId and one version knob with the Kotlin klib
 * rather than needing a separate coordinate.
 *
 * First-party Swift (like NativeIapBridge) that a gateway owns is only resolvable inside the
 * monorepo; shipping its source this way lets an external consumer resolve
 * `:<module>:<version>:iossrc@jar`, unzip it, and compile it. Under SPM the gateway's
 * `host-contributor` does exactly that — resolves this artifact and hands the `.swift`
 * to the umbrella plugin as a source target (see `NativeIapHostContributor`). The `iossrc`
 * name predates the SPM migration (it once fed a CocoaPods `:path` pod — Phase 3 removed the
 * podspec, but the source-shipping mechanism stayed). See docs/maven-publishing-explained.md.
 *
 * [podDir] is relative to the module (e.g. "ios/NativeIapBridge"). Requires the
 * `maven-publish` + Kotlin multiplatform plugins (the `kotlinMultiplatform` publication).
 */
fun Project.registerIosPodSourcePublication(podDir: String) {
    val packTask = tasks.register<Jar>("packIosPodSource") {
        archiveClassifier.set("iossrc") // published as <module>-<version>-iossrc.jar
        from(layout.projectDirectory.dir(podDir)) {
            include("*.swift")
        }
    }
    // `named` is lazy: the KMP plugin creates `kotlinMultiplatform` in afterEvaluate, so we
    // must not resolve it eagerly here.
    configure<PublishingExtension> {
        publications.named<MavenPublication>("kotlinMultiplatform") {
            artifact(packTask)
        }
    }
}
