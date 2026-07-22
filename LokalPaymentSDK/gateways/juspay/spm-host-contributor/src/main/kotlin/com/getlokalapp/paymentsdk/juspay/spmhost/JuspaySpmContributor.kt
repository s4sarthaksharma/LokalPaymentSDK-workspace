package com.getlokalapp.paymentsdk.juspay.spmhost

import com.getlokalapp.paymentsdk.host.LokalGatewaySpmContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkSpmExtension
import com.getlokalapp.paymentsdk.host.SpmContribution
import com.getlokalapp.paymentsdk.host.SpmVendorPackage
import org.gradle.api.Project

/**
 * Juspay's build-time contribution to an iOS host under SPM. The SPM-flavored sibling of
 * `JuspayHostContributor` (:host-contributor), which injects `spec.dependency 'HyperSDK'`
 * into the CocoaPods podspec. Discovered by the umbrella
 * `com.getlokalapp.paymentsdk.lokal-payment-spm` plugin via ServiceLoader; self-gates on
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
 *     [SpmVendorPackage]).
 *
 *  2. **Emit `MerchantConfig.json`** ([writeMerchantConfig]) — HyperSDK's asset pipeline
 *     reads it to know which merchant's assets to download. Replaces the CocoaPods-era
 *     `MerchantConfig.txt`; same `juspayClientId` gradle property the Android host plugin
 *     forwards, so a host declares its Juspay clientId exactly once.
 *
 * What this deliberately does NOT do (and why it can't):
 *  - **Run `Fuse.rb`** — under CocoaPods this rode in a managed `post_install`; SPM has no
 *    `post_install`. HyperSDK requires it as an Xcode **scheme pre-build action** running
 *    `Fuse.rb` + `ValidateHyperSDK.rb` (see the SDK's docs for the exact snippet). A Gradle
 *    plugin can't inject a scheme pre-action without editing the consumer's project, which
 *    this SDK avoids (same reason adding the SPM package is a one-time manual step). It is a
 *    documented one-time host step.
 *  - **Patch Info.plist URL/query schemes** — `JuspayHostContributor` did this via a
 *    `post_install` Ruby snippet; under SPM, HyperSDK's own `ValidateHyperSDK.rb` (run by
 *    the pre-build action above) writes the URL and query schemes into Info.plist itself, so
 *    there's nothing for this contributor to do.
 *  - **Add a cinterop** — the Kotlin bindings already ride in via the published :juspay klib
 *    (Maven), compiled against the direct HyperSDK.xcframework cinterop (R1/S2).
 */
class JuspaySpmContributor : LokalGatewaySpmContributor {

    override fun contribute(target: Project, config: LokalPaymentSdkSpmExtension): SpmContribution? {
        val importsJuspay = target.configurations
            .flatMap { it.dependencies }
            .any { it.group == SDK_GROUP && it.name == JUSPAY_MODULE }
        if (!importsJuspay) return null

        writeMerchantConfig(target)

        return SpmContribution(
            vendorPackage = SpmVendorPackage(
                url = "https://github.com/juspay/hypersdk-ios",
                exactVersion = VENDOR_SDK_VERSION,
                packageName = "hypersdk-ios",
                productName = "HyperSDK",
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

        // Same gradle property JuspayAndroidHostPlugin and JuspayHostContributor read — one
        // host-declared value shared across both platforms and both iOS integration modes.
        const val CLIENT_ID_PROPERTY = "juspayClientId"
    }
}
