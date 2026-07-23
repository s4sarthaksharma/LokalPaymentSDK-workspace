package com.getlokalapp.paymentsdk.juspay.host

import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import com.getlokalapp.paymentsdk.host.HostContribution
import com.getlokalapp.paymentsdk.host.ConsumerSetupNote
import com.getlokalapp.paymentsdk.host.PrebuildStep
import com.getlokalapp.paymentsdk.host.VendorPackage
import org.gradle.api.Project

/**
 * Juspay's build-time contribution to an iOS host under SPM. The SPM-flavored sibling of
 * `JuspayHostContributor` (:host-contributor), which injects `spec.dependency 'HyperSDK'`
 * into the CocoaPods podspec. Discovered by the umbrella
 * `com.getlokalapp.paymentsdk.lokal-payment` plugin via ServiceLoader; self-gates on
 * the host depending on :juspay (this jar is always on the buildscript classpath).
 *
 * Two responsibilities, both mirroring `JuspayHostContributor`'s CocoaPods equivalents:
 *
 *  1. **Link HyperSDK** — contributes the `juspay/hypersdk-ios` SPM package (product
 *     `HyperSDK`). That one package transitively pulls HyperCore, JuspaySafeBrowser and
 *     Airborne on the consumer side, so — unlike the SDK-side cinterop, which fetches all
 *     three xcframeworks by hand (see :juspay build.gradle.kts) — nothing else is declared
 *     here. `packageName` is the URL slug `hypersdk-ios`, NOT the `HyperSDK` product name
 *     (SPM resolves `.product(name:, package:)` by URL-derived identity — see
 *     [VendorPackage]).
 *
 *  2. **Emit `MerchantConfig.json`** ([writeMerchantConfig]) — HyperSDK's asset pipeline
 *     reads it to know which merchant's assets to download. Replaces the CocoaPods-era
 *     `MerchantConfig.txt`; same `juspayClientId` gradle property the Android host plugin
 *     forwards, so a host declares its Juspay clientId exactly once.
 *
 *  3. **Contribute the `Fuse.rb` pre-build step** ([HostContribution.prebuildStep]) —
 *     HyperSDK's merchant-asset download (its "Validate Mandatory Files" build phase fails
 *     without it) needs Xcode's build environment to locate HyperSDK's resolved SPM checkout
 *     under DerivedData, so it can't run at Gradle-sync time. Under CocoaPods this rode in a
 *     managed `post_install`; here it rides in the umbrella plugin's generated pre-build
 *     dispatcher, which the app registers as a single scheme pre-build action (see
 *     [PrebuildStep]). The step also runs `ValidateHyperSDK.rb` when the resolved HyperSDK
 *     ships it — that's what writes Juspay's URL/query schemes into `Info.plist`, so this
 *     contributor patches no plist itself.
 *
 * What this deliberately does NOT do (and why it can't):
 *  - **Register the scheme pre-build action** — a Gradle plugin can't edit the consumer's
 *    scheme without touching their Xcode project, which this SDK avoids (same reason adding
 *    the SPM package is a one-time manual step). Adding the *one* dispatcher pre-action is a
 *    documented host step surfaced in the generated `INTEGRATION.md`; every gateway's step
 *    (including this one) then runs through it with no further scheme edits.
 *  - **Add a cinterop** — the Kotlin bindings already ride in via the published :juspay klib
 *    (Maven), compiled against the direct HyperSDK.xcframework cinterop (R1/S2).
 */
class JuspayHostContributor : LokalGatewayHostContributor {

    override fun contribute(target: Project, config: LokalPaymentSdkExtension): HostContribution? {
        val importsJuspay = target.configurations
            .flatMap { it.dependencies }
            .any { it.group == SDK_GROUP && it.name == JUSPAY_MODULE }
        if (!importsJuspay) return null

        writeMerchantConfig(target)

        return HostContribution(
            vendorPackage = VendorPackage(
                url = "https://github.com/juspay/hypersdk-ios",
                exactVersion = VENDOR_SDK_VERSION,
                packageName = "hypersdk-ios",
                productName = "HyperSDK",
            ),
            prebuildStep = PrebuildStep(name = "juspay", script = FUSE_PREBUILD_SCRIPT),
            consumerNotes = listOf(
                ConsumerSetupNote(
                    heading = "Juspay (HyperSDK)",
                    steps = listOf(
                        "Juspay's `Fuse.rb` asset download runs via the SDK's pre-build action " +
                            "(§5) — you only need to register that one action. Its " +
                            "`ValidateHyperSDK.rb` also writes Juspay's URL/query schemes into " +
                            "your `Info.plist`, so you do NOT add an `LSApplicationQueriesSchemes` " +
                            "entry for Juspay by hand.",
                        "A `MerchantConfig.json` is generated for you at `iosApp/MerchantConfig.json` " +
                            "(beside your `.xcodeproj`) from the `juspayClientId` Gradle property; " +
                            "make sure it is a member of your app target so HyperSDK's asset " +
                            "pipeline can read it.",
                    ),
                ),
            ),
        )
    }

    /**
     * Writes `iosApp/MerchantConfig.json` from [CLIENT_ID_PROPERTY] in HyperSDK's SPM shape
     * (`{ "clientConfigs": { "<client-id>": {} } }`), in the app directory beside the
     * `.xcodeproj` — where HyperSDK's `Fuse.rb`/`ValidateHyperSDK.rb` pre-build action reads
     * it. Runs eagerly inside the umbrella plugin's `afterEvaluate`, so it's current on every
     * Gradle sync. Fails loudly if :juspay is imported but the clientId isn't set, mirroring
     * the CocoaPods contributor and `hypersdk.plugin`'s own hard failure.
     */
    private fun writeMerchantConfig(target: Project) {
        val clientId = target.providers.gradleProperty(CLIENT_ID_PROPERTY).orNull
        checkNotNull(clientId) {
            "Host imports :juspay but has not set the '$CLIENT_ID_PROPERTY' gradle property " +
                "(gradle.properties, or -P/ORG_GRADLE_PROJECT_…) — required to generate " +
                "iosApp/MerchantConfig.json for HyperSDK's merchant asset download."
        }
        target.file("../iosApp/MerchantConfig.json").writeText(
            """
            {
              "clientConfigs": {
                "$clientId": {}
              }
            }
            """.trimIndent() + "\n",
        )
    }

    private companion object {
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"
        const val JUSPAY_MODULE = "juspay"

        /**
         * `/bin/sh` snippet run by the umbrella plugin's pre-build dispatcher before each Xcode
         * build. HyperSDK resolves as an SPM package into Xcode's DerivedData, so its checkout
         * path isn't known until build time — this locates it from `$BUILD_DIR`, then runs
         * `Fuse.rb false` (asset download; `false` = don't force re-download, mirroring the
         * CocoaPods-era `post_install` invocation) and `ValidateHyperSDK.rb` when the resolved
         * version ships it. Fails loudly if the checkout can't be found — the most common cause
         * is the pre-build action not being set to provide build settings from the app target.
         */
        val FUSE_PREBUILD_SCRIPT = """
            set -eu
            # Locate HyperSDK's resolved SPM checkout (Xcode places SPM packages under DerivedData).
            sdk_dir=""
            for cand in \
              "${'$'}{BUILD_DIR:-}/../../SourcePackages/checkouts/hypersdk-ios" \
              "${'$'}{BUILD_DIR:-}/../../../SourcePackages/checkouts/hypersdk-ios"; do
              if [ -f "${'$'}cand/Fuse.rb" ]; then sdk_dir="${'$'}cand"; break; fi
            done
            if [ -z "${'$'}sdk_dir" ] && [ -n "${'$'}{BUILD_ROOT:-}" ]; then
              found=${'$'}(find "${'$'}{BUILD_ROOT%%/Build/*}" -path '*hypersdk-ios*/Fuse.rb' 2>/dev/null | head -n 1)
              [ -n "${'$'}found" ] && sdk_dir=${'$'}(dirname -- "${'$'}found")
            fi
            if [ -z "${'$'}sdk_dir" ]; then
              echo "error: Lokal Payment SDK could not find HyperSDK's Fuse.rb. Ensure this" >&2
              echo "       pre-build action provides build settings from your app target." >&2
              exit 1
            fi
            echo "Lokal Payment SDK: Juspay HyperSDK setup in ${'$'}sdk_dir"
            ruby "${'$'}sdk_dir/Fuse.rb" false
            if [ -f "${'$'}sdk_dir/ValidateHyperSDK.rb" ]; then ruby "${'$'}sdk_dir/ValidateHyperSDK.rb"; fi
        """.trimIndent()

        // Same gradle property JuspayAndroidHostPlugin and JuspayHostContributor read — one
        // host-declared value shared across both platforms and both iOS integration modes.
        const val CLIENT_ID_PROPERTY = "juspayClientId"
    }
}
