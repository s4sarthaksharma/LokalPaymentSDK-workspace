package com.getlokalapp.paymentsdk.juspay.host

import `in`.juspay.hypersdk.HyperSdkPluginExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property

interface JuspayHostExtension {
    val clientId: Property<String>
}

class JuspayHostPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("lokalJuspayHost", JuspayHostExtension::class.java)

        project.pluginManager.apply("hypersdk.plugin")

        // hypersdk.plugin reads its own extension in an afterEvaluate registered
        // during the apply() call above, which runs before any afterEvaluate
        // registered here — so the forwarding must be wired eagerly. Property.set
        // takes a lazy Provider, so this is safe even though `extension.clientId`
        // isn't set by the host's build script until later.
        val hyperSdkExtension = project.extensions.getByType(HyperSdkPluginExtension::class.java)
        hyperSdkExtension.clientId.set(extension.clientId)
        // Always tracks :juspay's compile-time `juspay-hypersdk` version (see
        // LokalPaymentSDK/gradle/libs.versions.toml) — not host-configurable, so
        // the runtime SDK this plugin fetches can never drift from what :juspay
        // compiled against.
        hyperSdkExtension.sdkVersion.set(DEFAULT_SDK_VERSION)
    }

    private companion object {
        const val DEFAULT_SDK_VERSION = "2.2.8-rc.01"
    }
}
