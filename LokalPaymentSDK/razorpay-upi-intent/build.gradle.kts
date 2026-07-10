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
// There is no iosMain source at all — the targets compile an empty klib, and
// the gateway simply never registers on iOS.
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
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // Not razorpay-checkout: the `Razorpay` class (submit()/setWebView(),
            // driving UPI Intent) lives in `com.razorpay:customui`, a distinct
            // Maven artifact from the one :razorpay-checkout depends on.
            implementation(libs.razorpay.customui)
        }
    }
}
