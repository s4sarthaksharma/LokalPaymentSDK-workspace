package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.BundledResource
import com.getlokalapp.paymentsdk.host.InfoPlistEntries
import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.ServiceLoader

/**
 * The sole iOS umbrella plugin (`com.getlokalapp.paymentsdk.lokal-payment`); it took
 * over the plain `lokal-payment` id when the CocoaPods umbrella was removed in Phase 3
 * — see docs/cocoapods-to-spm-migration-plan.md (D5). Applied to the module that
 * declares the host's `XCFramework("<name>")` and produces the umbrella framework.
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

        // afterEvaluate so contributors see the host's fully-declared dependency set
        // (their import self-gate), same timing as LokalPaymentPlugin.
        project.afterEvaluate {
            val xcFrameworkName = requireNotNull(config.xcFrameworkName) {
                "Host applies 'com.getlokalapp.paymentsdk.lokal-payment' but has " +
                    "not set 'lokalPaymentSdk { xcFrameworkName = \"...\" }' — " +
                    "required to locate the assembled .xcframework this plugin wraps."
            }
            // Gate once, here — not once per contributor. Discover every contributor keyed by
            // the module it owns, then scan the host's declared dependencies a single time:
            // group `com.getlokalapp.paymentsdk` narrows the host's whole dependency set down to
            // our gateway modules, keyed by module name. Call only the contributor whose module
            // the host actually imports, handing it the resolved Dependency (native-iap reads its
            // version/coordinate off it). A contributor may still return an empty list for a
            // present-but-inapplicable case (e.g. a missing artifact). Every gateway's
            // contributions land in one flat list, which each writer below filters by the kinds
            // it owns — see HostContribution.
            val contributors = ServiceLoader.load(
                LokalGatewayHostContributor::class.java,
                LokalGatewayHostContributor::class.java.classLoader,
            ).associateBy { it.module }
            val sdkDependencies = project.configurations.asSequence()
                .flatMap { it.dependencies.asSequence() }
                .filter { it.group == SDK_GROUP }
                .associateBy { it.name }
            val contributions = sdkDependencies.flatMap { (name, dep) ->
                contributors[name]?.contribute(project, config, dep).orEmpty()
            }

            // Sort the flat list into its kinds exactly once, here, so each writer below is handed
            // the kinds it consumes instead of the whole list plus the job of filtering it. The
            // `when` inside bucketed() is exhaustive, which is what makes a newly added
            // HostContribution kind a compile error rather than a silently ignored one.
            val contributed = contributions.bucketed()

            val umbrellaTargetName = "${xcFrameworkName}Umbrella"

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

    private companion object {
        // The Maven group shared by every Lokal Payment SDK gateway module. Gating the host's
        // dependency scan on it is what isolates "which of our gateways did the host import".
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"

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
