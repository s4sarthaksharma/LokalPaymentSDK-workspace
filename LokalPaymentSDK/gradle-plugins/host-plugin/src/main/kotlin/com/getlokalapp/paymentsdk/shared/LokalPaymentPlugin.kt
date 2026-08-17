package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.BundledResource
import com.getlokalapp.paymentsdk.host.InfoPlistEntries
import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.ServiceLoader

/**
 * The shared-module umbrella plugin (`com.getlokalapp.paymentsdk.lokal-payment`); it took
 * over the plain `lokal-payment` id when the CocoaPods umbrella was removed in Phase 3
 * — see docs/cocoapods-to-spm-migration-plan.md (D5). Applied to the host's shared KMP
 * module: the one that declares `XCFramework("<name>")` and produces the umbrella framework,
 * and whose `commonMain` consumes the gateways.
 *
 * It has two halves:
 *
 * 1. **Gateway selection (every platform).** The host lists what it ships as
 *    `lokalPaymentSdk { gateways = listOf(JUSPAY, …) }` and this plugin adds the matching
 *    Maven coordinates to `commonMainImplementation` itself, at its own version — so gateway
 *    versions equal the plugin's by construction, and a host never writes an SDK coordinate.
 *    Modeled on Juspay's `hypersdk.plugin`, which likewise resolves `in.juspay:*` from a
 *    `clientId` + `sdkVersion` rather than from host-declared `implementation` lines; unlike
 *    it, this needs no network, because the gateway list is a host decision rather than a
 *    server-side one.
 * 2. **The iOS half**, below — everything from `xcFrameworkName` onward. Skipped entirely on a
 *    module with no Apple targets, so an Android-only host can apply this plugin for (1) alone.
 *
 * Generates `build/lokal/spmPackage/Package.swift`: a `binaryTarget` wrapping the
 * host's own XCFramework (named via [LokalPaymentSdkExtension.xcFrameworkName]),
 * plus, per [LokalGatewayHostContributor] contribution, either/both of: a
 * `.package(url:, exact:)` dependency + product reference (vendor packages, e.g.
 * razorpay-pod), and a first-party Swift `.target` whose `.swift` files are copied into
 * `Sources/<name>/` (SDK-owned source, e.g. native-iap's NativeIapBridge). A thin
 * wrapper source target ties the binary target, every vendor product, and every
 * first-party source target together into the one product the app depends on — a
 * `binaryTarget` can't declare dependencies of its own, so without this wrapper a
 * contribution would be listed but never actually linked.
 *
 * This class is pure orchestration — discover the gateway contributors, gate on the host's
 * declared dependencies, and dispatch to the iOS artifact generators. The generation itself
 * lives in sibling files: [writePackageSwift] (the SPM manifest),
 * [writePrebuildDispatcher] (the Xcode pre-build
 * dispatcher), [patchInfoPlistIfConfigured] (the opt-in `Info.plist` merge), and
 * [patchXcodeProjectIfConfigured] (the opt-in `project.pbxproj` local-package wiring).
 *
 * Regenerated on every Gradle sync (configuration phase, mirroring how
 * `SharedCocoapodsPlugin` regenerates the Podfile's managed regions). The app wires the
 * generated folder in as a **local Swift package once** — declaratively in an XcodeGen
 * `project.yml` / Tuist `Project.swift` (how Lokal's apps manage their iOS projects), or by
 * hand via Xcode's "Add Local…" for a hand-managed `.xcodeproj` like the demo's. For that
 * hand-managed case the host can instead set `lokalPaymentSdk { iosXcodeProject = … }` and
 * the plugin performs that "Add Local…" for it, idempotently, on every sync (see
 * [patchXcodeProjectIfConfigured]). This is the one opt-in exception to the D2 rule that the
 * SDK doesn't edit `project.pbxproj`: off by default, and never for XcodeGen/Tuist hosts,
 * which own their generated project and leave the property unset — see the plan doc's D2
 * rationale. Every regeneration after that one-time wiring is picked up automatically — SPM
 * re-resolves a local package's manifest on every build. Alongside `Package.swift` the plugin
 * Every edit it makes is announced through `logger.lifecycle`, so the sync log is the record
 * of what was wired — see docs/integrating-the-sdk.md for the host-side steps.
 */
class LokalPaymentPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val config = project.extensions.create(
            "lokalPaymentSdk",
            LokalPaymentSdkExtension::class.java,
        )

        // The host's gateway selection, turned into real dependencies on the applying module's
        // commonMain. Registered eagerly so the listener is in place before the KMP plugin
        // applies, but every coordinate is built inside a provider — so `gateways = …` can still
        // be assigned after this apply() returns, and `addAllLater` merely *stores* the provider:
        // no coordinate is built, and nothing is resolved, until something queries the dependency
        // set. That is what keeps this free at configuration time.
        val gatewayDependencies = project.provider { gatewayDependencies(project, config) }
        project.plugins.withId(KOTLIN_MULTIPLATFORM_PLUGIN) {
            // matching{}.configureEach{} rather than named(): a live, lazy view, so this works
            // whether or not KMP has created commonMain's configurations by the time it runs.
            project.configurations
                .matching { it.name == COMMON_MAIN_IMPLEMENTATION }
                .configureEach { it.dependencies.addAllLater(gatewayDependencies) }
        }

        // afterEvaluate so contributors see the host's fully-declared dependency set
        // (their import self-gate), same timing as LokalPaymentPlugin.
        project.afterEvaluate {
            failIfGatewaysDeclaredByHand(project, config)

            // Everything below is the iOS half, and it is skipped wholesale for a module that
            // has no Apple targets — an Android-only host applies this plugin purely to select
            // gateways (`lokalPaymentSdk { gateways = … }`) and has no .xcframework to wrap. The
            // hasPlugin check comes first so KotlinMultiplatformExtension is only ever loaded on
            // a project that actually has the KMP plugin on its buildscript classpath.
            if (!project.plugins.hasPlugin(KOTLIN_MULTIPLATFORM_PLUGIN)) return@afterEvaluate
            if (!project.hasAppleTargets()) return@afterEvaluate

            // With Apple targets present, a missing xcFrameworkName stays a hard error — exactly
            // as before; only the no-Apple-targets case is newly tolerated.
            val xcFrameworkName = requireNotNull(config.xcFrameworkName) {
                "Host applies 'com.getlokalapp.paymentsdk.lokal-payment' but has " +
                    "not set 'lokalPaymentSdk { xcFrameworkName = \"...\" }' — " +
                    "required to locate the assembled .xcframework this plugin wraps."
            }
            // Gate once, here — not once per contributor. Discover every contributor keyed by
            // the module it owns, then match it against the host's `gateways` selection. The
            // selection is authoritative (see LokalPaymentSdkExtension.gateways): a host cannot
            // declare a gateway coordinate by hand, so there is nothing to scan for. Each
            // contributor is handed the same Dependency this plugin adds to commonMain —
            // native-iap reads group/name/version off it to build its `:iossrc@jar` coordinate,
            // so it stays a real Dependency rather than a hand-assembled string. A contributor
            // may still return an empty list for a present-but-inapplicable case (e.g. a missing
            // artifact). Every gateway's contributions land in one flat list, which each writer
            // below filters by the kinds it owns — see HostContribution.
            val contributors = ServiceLoader.load(
                LokalGatewayHostContributor::class.java,
                LokalGatewayHostContributor::class.java.classLoader,
            ).associateBy { it.module }
            val contributions = gatewayDependencies(project, config).flatMap { dep ->
                contributors[dep.name]?.contribute(project, config, dep).orEmpty()
            }

            // Sort the flat list into its kinds exactly once, here, so each writer below is handed
            // the kinds it consumes instead of the whole list plus the job of filtering it. The
            // `when` inside bucketed() is exhaustive, which is what makes a newly added
            // HostContribution kind a compile error rather than a silently ignored one.
            val contributed = contributions.bucketed()

            // Host name first so the package is unique per host and stays visibly related to the
            // framework the app imports; "Payments" so a developer meeting this package in Xcode's
            // package list can tell what generated it without opening Package.swift. Derived, not
            // configurable: the SDK owns this name, and a host toggle would only let the two halves
            // (link `<name>PaymentsUmbrella`, import `<name>`) drift apart.
            val umbrellaTargetName = "${xcFrameworkName}PaymentsUmbrella"

            // The generated binaryTarget points at a fixed XCFrameworks/current/ that gets
            // restaged per Xcode configuration, from tasks the pre-build step drives (see
            // KotlinXCFrameworkStaging).
            configureXCFrameworkStaging(project, xcFrameworkName)

            writePackageSwift(
                project, xcFrameworkName, umbrellaTargetName,
                contributed.vendorPackages, contributed.sourceTargets,
            )

            // Info.plist: the baseline UPI query schemes (an ungated `:shared` concern —
            // UPI app detection is available to every host regardless of gateway, matching
            // the CocoaPods-era `shared-query-schemes.rb`) plus whatever active gateways add.
            val queriesSchemes = (BASELINE_UPI_QUERY_SCHEMES +
                contributed.plistEntries.flatMap { it.queriesSchemes })
                .distinct()
            patchInfoPlistIfConfigured(project, config, queriesSchemes)

            // project.pbxproj: wire the generated local package into a hand-managed .xcodeproj
            // when the host opted in via `lokalPaymentSdk { iosXcodeProject = … }` — the exact
            // sibling of the Info.plist merge above (see patchXcodeProjectIfConfigured). Gateway
            // files that must ship inside the .app ride the same opt-in: generating them isn't
            // enough, since a non-member fails at runtime on bundle lookup, not at build time.
            val bundledResources = contributed.bundledResources.map { it.path }.distinct()
            patchXcodeProjectIfConfigured(project, config, umbrellaTargetName, bundledResources)

            // Build-time steps: one generated dispatcher the app registers as a single Xcode
            // scheme pre-build action (see writePrebuildDispatcher / PrebuildStep). The SDK's
            // own Kotlin-variant restage rides the same dispatcher as the gateway steps, so it
            // needs no additional host wiring.
            val prebuildScript = writePrebuildDispatcher(
                project,
                listOf(kotlinXCFrameworkPrebuildStep(project, xcFrameworkName)),
                contributed.prebuildSteps,
            )

            // .xcscheme: register that dispatcher as a build pre-action in the schemes the host
            // listed via `lokalPaymentSdk { iosXcodeSchemes = … }`, or — with that unset and
            // `iosXcodeProject` set — in every shared scheme of that project that builds the app
            // target. The third sibling of the plist and pbxproj opt-ins, but the only one that
            // defaults to wiring rather than to doing nothing: it is load-bearing rather than a
            // convenience, since the dispatcher is what restages the Kotlin binary, so a host
            // that forgets it builds against a stale framework with nothing to indicate why.
            patchXcodeSchemesIfConfigured(project, config, prebuildScript)
        }
    }

    /**
     * The SDK coordinates the host's [LokalPaymentSdkExtension.gateways] selection resolves to:
     * `:shared` unconditionally plus one per selected gateway, all at this plugin's own
     * [SDK_VERSION] (generated — see host-plugin/build.gradle.kts). `:shared` is added even for
     * an empty selection, since a host with no gateways still needs `LokalPaymentSdk`; every
     * gateway also `api`s it, so that entry is belt-and-braces rather than load-bearing.
     *
     * Pure object construction — `dependencies.create` neither resolves nor touches the network,
     * so calling this from both the lazy provider and the iOS gate below costs nothing and avoids
     * having to memoize across the two.
     */
    private fun gatewayDependencies(
        project: Project,
        config: LokalPaymentSdkExtension,
    ): List<Dependency> =
        (listOf(SHARED_ARTIFACT) + config.gateways.distinct().map { it.artifactId })
            .map { project.dependencies.create("$SDK_GROUP:$it:$SDK_VERSION") }

    /**
     * Fails a host that still declares gateway coordinates by hand instead of selecting them
     * through [LokalPaymentSdkExtension.gateways], which is the only supported path.
     *
     * Checked only when the selection is *empty* — i.e. the "never migrated" case, which is the
     * one that matters: without this the build succeeds and the failure surfaces at runtime as
     * "no handler registered" for a gateway the developer can plainly see in their build file.
     * An empty selection also means this plugin has contributed nothing but `:shared` (filtered
     * below), so the scan cannot mistake our own additions for the host's.
     */
    private fun failIfGatewaysDeclaredByHand(project: Project, config: LokalPaymentSdkExtension) {
        if (config.gateways.isNotEmpty()) return
        val declared = project.configurations.asSequence()
            .flatMap { it.dependencies.asSequence() }
            .filter { it.group == SDK_GROUP && it.name != SHARED_ARTIFACT }
            .map { it.name }
            .distinct()
            .sorted()
            .toList()
        if (declared.isEmpty()) return
        throw GradleException(
            "Gateway modules are selected through 'lokalPaymentSdk { gateways = listOf(…) }', " +
                "not declared as dependencies. Remove these from ${project.path}'s " +
                "dependencies and list them in the extension instead: " +
                declared.joinToString(", ") { "com.getlokalapp.paymentsdk:$it" },
        )
    }

    /**
     * Whether the applying module builds for an Apple platform, and so has an `.xcframework` for
     * the iOS half of this plugin to wrap. Read-only use of the KMP extension (compileOnly — see
     * host-plugin/build.gradle.kts); only ever called behind a
     * `plugins.hasPlugin(`[KOTLIN_MULTIPLATFORM_PLUGIN]`)` guard, so the Kotlin Gradle plugin
     * classes it references are guaranteed loadable.
     */
    private fun Project.hasAppleTargets(): Boolean {
        val kotlin = extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return false
        return kotlin.targets.any {
            it is KotlinNativeTarget && it.konanTarget.family.isAppleFamily
        }
    }

    private companion object {
        // The Maven group shared by every Lokal Payment SDK gateway module. Gating the host's
        // dependency scan on it is what isolates "which of our gateways did the host import".
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"

        // The core runtime module, added for every host regardless of gateway selection.
        const val SHARED_ARTIFACT = "shared"

        // The KMP plugin id: this plugin adds the selected gateways to commonMain, and reads the
        // KMP extension to decide whether the iOS half applies at all.
        const val KOTLIN_MULTIPLATFORM_PLUGIN = "org.jetbrains.kotlin.multiplatform"

        // The configuration behind KMP's `commonMain.dependencies { implementation(…) }`, which
        // is where the host would have declared these gateways by hand.
        const val COMMON_MAIN_IMPLEMENTATION = "commonMainImplementation"

        /**
         * The UPI apps the SDK checks for with `canOpenURL` before offering a UPI-intent
         * redirect — every host gets these regardless of gateway (UPI detection is a `:shared`
         * concern). Kept verbatim from the CocoaPods-era `shared-query-schemes.rb`.
         */
        val BASELINE_UPI_QUERY_SCHEMES = listOf(
            "credpay", "phonepe", "paytmmp", "tez", "bhim", "myairtel", "slice-upi",
            "ppe", "amazonpay", "kiwi", "navipay", "mobikwik", "popclubapp", "super",
            "postpe", "jupiter", "hdfcbanknb", "aunb", "imobileappnb", "simplypayupi",
            "tnupi", "magnetapp", "lxme", "indmoney", "whatsapp", "canaraaipe", "fpupi",
            "scapia", "salaryse", "bajajpayupi", "curieapp", "aufmobile", "devtools",
            "cugext",
        )
    }
}
