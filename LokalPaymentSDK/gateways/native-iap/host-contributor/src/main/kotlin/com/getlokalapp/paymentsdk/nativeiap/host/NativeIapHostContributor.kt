package com.getlokalapp.paymentsdk.nativeiap.host

import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import com.getlokalapp.paymentsdk.host.HostContribution
import com.getlokalapp.paymentsdk.host.SourceTarget
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency

/**
 * native-iap's build-time contribution to an iOS host under SPM. Unlike
 * `RazorpayHostContributor` (which links a third-party vendor *package*), native-iap owns
 * its runtime bridge — `NativeIapBridge.swift`, the Objective-C-visible StoreKit 2 driver
 * (StoreKit 2's async Swift API isn't `@objc`, so Kotlin/Native can't cinterop the system
 * framework directly). So this contributes a first-party **source target**: the Swift file
 * is compiled straight into the generated umbrella package and linked against `StoreKit`.
 * The SPM-flavored replacement for the local `:path` CocoaPod `SharedCocoapodsPlugin`
 * declares in the Podfile from the unpacked `iossrc` source.
 *
 * The Swift source comes from :native-iap's own `iossrc`-classifier Maven artifact (see
 * buildSrc `registerIosPodSourcePublication`) — resolved leniently and unzipped here,
 * exactly as `SharedCocoapodsPlugin.unpackIosPodSources` does for the CocoaPods path — so
 * an external consumer never needs the monorepo. The unzipped directory is handed to the
 * umbrella plugin, which copies the `.swift` into `Sources/NativeIapBridge/`.
 *
 * Gated by the umbrella plugin on [module] (`native-iap`): this jar is always on the
 * buildscript classpath (the umbrella depends on it), but [contribute] is only called when
 * the host actually imports :native-iap — "not used" is "never called", so no source target
 * is added and nothing is compiled. It still returns `null` when the module is imported
 * without a version (the iossrc coordinate can't be built), a present-but-inapplicable case.
 *
 * Deliberately does NOT add a cinterop to the host module: the Kotlin bindings already ride
 * in via the published :native-iap klib (Maven, compiled against a direct header cinterop
 * over the same NativeIapBridge source — see docs/cocoapods-to-spm-migration-plan.md), so
 * all the host needs is the Swift source compiled and linked at the app target.
 */
class NativeIapHostContributor : LokalGatewayHostContributor {

    override val module = OWNED_MODULE

    override fun contribute(
        target: Project,
        config: LokalPaymentSdkExtension,
        dependency: Dependency,
    ): List<HostContribution> {
        // The umbrella plugin only calls us when the host imports :native-iap, but building the
        // iossrc artifact coordinate needs a version — a versionless declaration can't be
        // fetched, so bail (present-but-inapplicable). group/name/version come straight off the
        // resolved dependency the plugin handed us, so nothing is hand-repeated here.
        val version = dependency.version ?: return emptyList()

        // `@jar` = artifact-only (skip the KMP variant/metadata + klib graph); the lenient
        // view yields nothing if the iossrc classifier isn't published — mirrors
        // SharedCocoapodsPlugin's resolution, pinned to the host's own declared version.
        val iossrc = target.configurations.detachedConfiguration(
            target.dependencies.create(
                "${dependency.group}:${dependency.name}:$version:iossrc@jar",
            ),
        ).apply { isTransitive = false }
            .incoming.artifactView { it.isLenient = true }.files
        val jar = iossrc.files.firstOrNull() ?: return emptyList()

        val outDir = target.layout.buildDirectory
            .dir("lokal/spmSources/$OWNED_MODULE").get().asFile
        outDir.deleteRecursively()
        target.copy { copy ->
            copy.from(target.zipTree(jar)) { spec ->
                spec.include("*.swift")
                spec.exclude("META-INF/**")
            }
            copy.into(outDir)
        }

        return listOf(
            SourceTarget(
                name = BRIDGE_TARGET,
                sourceDir = outDir.absolutePath,
                linkedFrameworks = listOf("StoreKit"),
            ),
        )
    }

    private companion object {
        const val BRIDGE_TARGET = "NativeIapBridge"
    }
}
