import com.getlokalapp.paymentsdk.buildsrc.registerIosPodSourcePublication
import com.getlokalapp.paymentsdk.buildsrc.registerModuleVersionTask
import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.kotlin.native.cocoapods")
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

// Native platform in-app purchase. iOS ships a real StoreKit 2 implementation
// today; Android is a stub (registerUnavailable) until Play Billing lands —
// see docs/adding-a-new-gateway.md §3's "iOS-only-for-now gateway" variant.
// StoreKit 2's API (Product, Transaction, VerificationResult) is pure Swift
// async/AsyncSequence, not @objc-visible, so unlike razorpay-checkout's
// pod("razorpay-pod") this module can't cinterop straight against the system
// StoreKit framework. Instead it vendors its own small Objective-C-visible
// bridge as a local CocoaPod (ios/NativeIapBridge/) — real Swift StoreKit 2
// code wrapped behind a plain-Foundation-types surface Kotlin/Native can
// actually cinterop against. That local pod is the one thing a host has to
// name once in its own Podfile (CocoaPods' spec.dependency can't carry a
// filesystem :path, only a Podfile's `pod` directive can) — everything else
// (registration, purchase orchestration, result mapping) is fully
// self-contained, zero other host code.

val generateModuleVersion = registerModuleVersionTask(
    taskName = "generateModuleVersion",
    packageName = "com.getlokalapp.paymentsdk.nativeiap",
)

// No versioned vendor artifact on iOS — StoreKit is a system framework, not a
// pinned pod, and NativeIapBridge is our own local pod, not a third-party
// dependency to track. "system" documents that there's no version to bake,
// mirroring the Android stub's literal "unsupported" vendorSdkVersion below.
val generateIosVendorVersion = registerVendorVersionTask(
    taskName = "generateIosVendorVersion",
    packageName = "com.getlokalapp.paymentsdk.nativeiap",
    vendorSdkVersion = "system",
    asActual = false,
)

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidLibrary {
        namespace = "com.getlokalapp.paymentsdk.nativeiap"
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
        version = project.version.toString()
        summary = "Lokal Payment SDK - Native in-app purchase (StoreKit 2 today, Play Billing later)"
        homepage = "https://github.com/getlokalapp/LokalPaymentSDK"
        ios.deploymentTarget = "16.0"
        name = "NativeIap"

        framework {
            baseName = "NativeIap"
            isStatic = true
        }

        // Local pod, not CocoaPods trunk: NativeIapBridge is code this module
        // owns and ships itself, not a third-party vendor SDK.
        pod("NativeIapBridge") {
            version = "1.0.0"
            source = path(project.file("ios/NativeIapBridge"))
        }
    }

    sourceSets {
        commonMain {
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
        iosMain {
            kotlin.srcDir(generateIosVendorVersion)
        }
    }
}

// Ship NativeIapBridge's pod source (Swift + podspec) as an `iossrc`-classifier artifact
// on this module's Maven coordinate, so an external consumer can resolve + compile the
// local pod without a monorepo path. See buildSrc IosPodSource.kt.
registerIosPodSourcePublication(podDir = "ios/NativeIapBridge")
