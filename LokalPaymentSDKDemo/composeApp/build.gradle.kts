import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlinMultiplatformLibrary)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    id("org.jetbrains.kotlin.native.cocoapods")
    // D4/D9/R4: hypersdk.plugin does NOT apply cleanly here — it injects its
    // dependencies via a plain `implementation` configuration, which a
    // com.android.kotlin.multiplatform.library module doesn't expose (KMP
    // uses per-source-set configs like androidMainImplementation instead).
    // Confirmed: applying it here fails with "Configuration with name
    // 'implementation' not found." Applied to :androidApp instead, which has
    // the conventional AGP application configurations the plugin expects.
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

        // Intentionally NO pod("razorpay-pod") here. The Maven
        // `razorpay-checkout` iOS klibs already bundle the Razorpay cinterop;
        // re-declaring the pod would generate a second cinterop for the same
        // `cocoapods.razorpay_pod` package and clash. The actual
        // Razorpay.framework binary is supplied to the app target via
        // iosApp/Podfile instead.
    }

    sourceSets {
        commonMain.dependencies {
            // KMP root coordinates: shared resolves to the .aar on Android and
            // the iOS klibs on iOS; razorpay-checkout additionally bundles the
            // Razorpay cinterop on iOS. razorpay-upi-intent now also publishes
            // an iOS variant (a stub — see its build.gradle.kts/iosMain), just
            // so it resolves here; RazorpayUpiIntentSdk itself is still only
            // usable from androidMain.
            implementation(libs.lokalpaymentsdk.shared)
            implementation(libs.lokalpaymentsdk.razorpay.checkout)
            implementation(libs.lokalpaymentsdk.razorpay.upi.intent)
            implementation(libs.lokalpaymentsdk.juspay)
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
