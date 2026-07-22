import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlinMultiplatformLibrary)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.lokalpaymentsdk.lokal.payment.spm)
}

lokalPaymentSdkSpm {
    xcFrameworkName = "ComposeApp"
}

kotlin {
    androidLibrary {
        namespace = "com.getlokalapp.paymentsdk.demo"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // SPM combined proof (docs/cocoapods-to-spm-migration-plan.md, Phase 2.1): the
    // umbrella is now an XCFramework consumed via a local Package.swift instead of a
    // cocoapods-plugin framework — see LokalPaymentSpmPlugin.
    val xcf = XCFramework("ComposeApp")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.lokalpaymentsdk.shared)
            implementation(libs.lokalpaymentsdk.razorpay.checkout)
            implementation(libs.lokalpaymentsdk.razorpay.customui)
            implementation(libs.lokalpaymentsdk.upi.intent)
            implementation(libs.lokalpaymentsdk.native.iap)
            // juspay is NOT YET migrated off CocoaPods (its build.gradle.kts still needs
            // `pod(...)` to compile, and HyperSDK has no SPM linking path here yet).
            // Excluded from this SPM build rather than left in to silently break the final
            // XCFramework link; re-add once its own Phase 2 migration step lands.
            // implementation(libs.lokalpaymentsdk.juspay)
            implementation(libs.lokalpaymentsdk.web.checkout)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
