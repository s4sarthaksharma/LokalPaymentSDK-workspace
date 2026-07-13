import com.getlokalapp.paymentsdk.buildsrc.registerModuleVersionTask
import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

// Razorpay's UPI Intent flow itself (resolving installed UPI apps and
// handing off via an Android Intent) has no iOS equivalent — RazorpayUpiIntentSdk
// stays Android-only, declared only in androidMain. Razorpay's iOS UPI
// offering ("UPI Turbo") is a materially different, much larger integration
// surface (account linking, native PIN entry, token plugins) — out of scope
// here; it would land as its own module later.
//
// iOS targets exist below purely so this module publishes an iOS variant —
// without one, Gradle can't resolve it from a consumer's commonMain at all
// (verified: `implementation(libs.lokalpaymentsdk.razorpay.upi.intent)` in
// LokalPaymentSDKDemo's composeApp/commonMain failed
// :composeApp:compileKotlinIosSimulatorArm64 with "No matching variant...
// consumer needed platform.type 'native'" before these targets existed).
// iosMain's only content is RazorpayUpiIntentEagerInit.kt, which registers
// this gateway as unavailable (see LokalPaymentSdk.registerUnavailable) —
// there is no working iOS implementation.

// Bakes this module's version and the underlying razorpay-customui version it
// compiles Android against into androidMain, same pattern as :shared's
// generatePaymentSdkVersion — so GatewayMetadata can never drift from what
// this artifact was actually built as. Android-only (asActual = false): this
// gateway has no working iOS implementation, so there's no expect/actual
// split — just a bare const.
val generateModuleVersion = registerModuleVersionTask(
    taskName = "generateModuleVersion",
    packageName = "com.getlokalapp.paymentsdk.razorpay",
)
val generateVendorVersion = registerVendorVersionTask(
    taskName = "generateVendorVersion",
    packageName = "com.getlokalapp.paymentsdk.razorpay",
    vendorSdkVersion = libs.versions.razorpay.customui.get(),
    asActual = false,
)

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidLibrary {
        namespace = "com.getlokalapp.paymentsdk.upiintent"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest {}
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            // Shared with iosMain too (not just androidMain): the iOS eager-init
            // hook now reports MODULE_VERSION as part of its "unavailable"
            // registration, and a bare const has no platform dependency, so
            // there's no reason to duplicate the wiring per target.
            kotlin.srcDir(generateModuleVersion)
            dependencies {
                api(project(":shared"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain {
            kotlin.srcDir(generateVendorVersion)
            dependencies {
                // Not razorpay-checkout: the `Razorpay` class (submit()/setWebView(),
                // driving UPI Intent) lives in `com.razorpay:customui`, a distinct
                // Maven artifact from the one :razorpay-checkout depends on.
                implementation(libs.razorpay.customui)
            }
        }
    }
}
