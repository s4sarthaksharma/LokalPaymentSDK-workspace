import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlinMultiplatformLibrary)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    id("org.jetbrains.kotlin.native.cocoapods")
    alias(libs.plugins.lokalpaymentsdk.lokal.payment)
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

    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.lokalpaymentsdk.shared)
            implementation(libs.lokalpaymentsdk.razorpay.checkout)
            implementation(libs.lokalpaymentsdk.razorpay.customui)
            implementation(libs.lokalpaymentsdk.upi.intent)
            implementation(libs.lokalpaymentsdk.juspay)
            implementation(libs.lokalpaymentsdk.native.iap)
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
