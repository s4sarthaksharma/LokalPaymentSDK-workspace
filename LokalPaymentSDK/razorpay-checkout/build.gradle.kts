import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.kotlin.native.cocoapods")
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"
version = "0.0.1"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidLibrary {
        namespace = "com.getlokalapp.paymentsdk.checkout"
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

    cocoapods {
        version = "0.0.1"
        summary = "Lokal Payment SDK - Razorpay hosted Checkout"
        homepage = "https://github.com/getlokalapp/LokalPaymentSDK"
        ios.deploymentTarget = "16.0"
        name = "RazorpayCheckout"

        framework {
            baseName = "RazorpayCheckout"
            isStatic = true
        }

        pod("razorpay-pod") {
            version = "1.4.3"
            moduleName = "Razorpay"
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
    }

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
            // implementation, not api: Razorpay is fully encapsulated behind
            // the internal RazorpayCheckoutActivity proxy — no public SDK type
            // exposes a Razorpay class, so consumers don't need it on their
            // compile classpath (it's still there transitively at runtime).
            implementation(libs.razorpay.checkout)
        }
    }
}
