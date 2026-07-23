import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlinMultiplatformLibrary)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.lokalpaymentsdk.lokal.payment)
}

val hostXcFrameworkName = "LokalPaymentSDKDemo"

lokalPaymentSdk {
    xcFrameworkName = hostXcFrameworkName
    iosInfoPlist = "../iosApp/iosApp/Info.plist"
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

    val xcf = XCFramework(hostXcFrameworkName)
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = hostXcFrameworkName
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
            implementation(libs.lokalpaymentsdk.juspay)
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
