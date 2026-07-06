import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlinMultiplatformLibrary)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    id("org.jetbrains.kotlin.native.cocoapods")
}

kotlin {
    // AGP 9 disallows com.android.application alongside the KMP plugin, so the
    // shared UI lives in this KMP *library* module and the thin :androidApp
    // application module hosts it. Mirrors the SDK's :shared setup.
    androidLibrary {
        namespace = "com.getlokalapp.paymentsdk.demo"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // No iosX64(): Compose Multiplatform 1.11.x does not publish an Intel
    // simulator (ios_x64) variant. Device + Apple-silicon simulator only.
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        version = "1.0"
        summary = "Compose Multiplatform demo for the Lokal Payment SDK"
        homepage = "https://github.com/getlokalapp/LokalPaymentSDK"
        ios.deploymentTarget = "16.0"
        name = "composeApp"

        framework {
            baseName = "ComposeApp"
            isStatic = true
        }

        // Intentionally NO pod("razorpay-pod") here. The Maven `shared` iOS
        // klibs already bundle the Razorpay cinterop; re-declaring the pod would
        // generate a second cinterop for the same `cocoapods.razorpay_pod`
        // package and clash. The actual Razorpay.framework binary is supplied to
        // the app target via iosApp/Podfile instead.
    }

    sourceSets {
        commonMain.dependencies {
            // KMP root coordinate: resolves to the .aar on Android and the iOS
            // klibs (+ Razorpay cinterop) on iOS via Gradle Module Metadata.
            implementation(libs.lokalpaymentsdk.shared)
            implementation(libs.kotlinx.coroutines.core)

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
