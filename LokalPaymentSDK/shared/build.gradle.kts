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
        namespace = "com.getlokalapp.paymentsdk"
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
        summary = "Lokal Payment SDK shared module"
        homepage = "https://github.com/getlokalapp/LokalPaymentSDK"
        ios.deploymentTarget = "16.0"
        name = "Shared"

        framework {
            baseName = "Shared"
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
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // api, not implementation: AndroidRazorpayCheckoutClient
            // implements Razorpay's own PaymentResultWithDataListener
            // directly, so it's part of this class's public supertype —
            // consumers need it on their compile classpath too.
            api(libs.razorpay.checkout)
        }
    }
}
