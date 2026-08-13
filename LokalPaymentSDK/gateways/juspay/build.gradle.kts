import com.getlokalapp.paymentsdk.buildsrc.registerModuleVersionTask
import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask
import com.getlokalapp.paymentsdk.buildsrc.installVerifiedZipDirectory
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

// Single source for the iOS HyperSDK version under SPM — drives the
// fetchHyperSdkXcFramework task below (the xcframework the cinterops compile against)
// and generateIosVendorVersion, and (via the same catalog entry) the juspay
// host-contributor's `.package(url:, exact:)` pin, so none can drift.
val iosVendorSdkVersion = libs.versions.juspay.spm.ios.get()
val hyperSdkIosSha256 = "a4629ff33d97ed63c7ae54085bf0bf9752b72aec9bef580996483ac8eca98c1d"

// Bakes this module's own version (root gradle.properties) into commonMain,
// same pattern as :shared's generatePaymentSdkVersion — so GatewayMetadata's
// moduleVersion can never drift from the published artifact version.
val generateModuleVersion = registerModuleVersionTask(
    taskName = "generateModuleVersion",
    packageName = "com.getlokalapp.paymentsdk.juspay",
)

// Bakes gradle/libs.versions.toml's juspay-hypersdk entry into androidMain as
// GatewayMetadata's Android vendorSdkVersion — the same version this module
// compiles Android against (and JuspayHostAndroidContributor.DEFAULT_SDK_VERSION pins the
// host's runtime HyperSDK to), so it can't drift.
val generateAndroidVendorVersion = registerVendorVersionTask(
    taskName = "generateAndroidVendorVersion",
    packageName = "com.getlokalapp.paymentsdk.juspay",
    vendorSdkVersion = libs.versions.juspay.hypersdk.get(),
)

// Bakes this build script's iosVendorSdkVersion (the HyperSDK pod version
// below) into iosMain as GatewayMetadata's iOS vendorSdkVersion.
val generateIosVendorVersion = registerVendorVersionTask(
    taskName = "generateIosVendorVersion",
    packageName = "com.getlokalapp.paymentsdk.juspay",
    vendorSdkVersion = iosVendorSdkVersion,
)

// HyperCore and Airborne are HyperSDK's transitive iOS dependencies — HyperSDK.framework's
// headers `#import <HyperCore/…>` and `@import Airborne`, so all three xcframeworks must be
// on the cinterop framework search path. These versions are exactly what hypersdk-ios
// $iosVendorSdkVersion's Package.swift pins; kept here rather than in the catalog because
// only this SDK-side cinterop needs them — the CONSUMER resolves the whole graph
// automatically through the single hypersdk-ios SPM package (juspay host-contributor).
val hyperCoreVersion = "1.0.4"
val airborneVersion = "0.37.0"
val hyperCoreIosSha256 = "934c762fd76ad8c4e38397c74d8e704dabfce14a60d8f2d4c909878f29dc115f"
val airborneIosSha256 = "c46f082129688da7a1b8a37a5867b29f6b2e1104ab210d1f1904a94e9794b97a"

// Fetches HyperSDK.xcframework (+ HyperCore, Airborne) straight from Juspay's public release
// CDN — the same zips github.com/juspay/hypersdk-ios's binary targets point at — so the
// cinterops below have real headers/module maps to compile against. The SPM-era replacement
// for CocoaPods resolving `pod("HyperSDK")`. No CocoaPods synthetic build, so the pod's
// "[CP-User] Validate Mandatory Files" gate (and the old SKIP_HYPERSDK_VALIDATION workaround)
// is gone entirely; the merchant-asset pipeline is a consumer-side concern now (juspay
// host-contributor). These archives are used only by the SDK producer build to compile
// the published Kotlin/Native bindings; they are not embedded in the gateway klib. The
// host independently resolves the pinned hypersdk-ios package through SwiftPM. Cacheable
// via version + checksum input tracking. Requires network at producer-build time.
val fetchHyperSdkXcFramework = tasks.register("fetchHyperSdkXcFramework") {
    inputs.property("hypersdk", iosVendorSdkVersion)
    inputs.property("hypercore", hyperCoreVersion)
    inputs.property("airborne", airborneVersion)
    inputs.property("hypersdkSha256", hyperSdkIosSha256)
    inputs.property("hypercoreSha256", hyperCoreIosSha256)
    inputs.property("airborneSha256", airborneIosSha256)
    val outputDir = layout.buildDirectory.dir("vendorXcFrameworks")
    outputs.dir(outputDir)
    doLast {
        val out = outputDir.get().asFile
        val work = temporaryDir
        fun fetch(name: String, version: String, cdnPath: String, expectedSha256: String) {
            val zip = work.resolve("$name-$version.zip")
            installVerifiedZipDirectory(
                url = "https://public.releases.juspay.in/release/ios/$cdnPath/$version/$name.zip",
                expectedSha256 = expectedSha256,
                archiveFile = zip,
                extractionRoot = work,
                directoryName = "$name.xcframework",
                destination = out.resolve("$name.xcframework"),
            )
        }
        out.deleteRecursively()
        out.mkdirs()
        fetch("HyperSDK", iosVendorSdkVersion, "hyper-sdk", hyperSdkIosSha256)
        fetch("HyperCore", hyperCoreVersion, "hyper-core", hyperCoreIosSha256)
        fetch("Airborne", airborneVersion, "airborne", airborneIosSha256)
    }
}
val vendorXcFrameworksDir = fetchHyperSdkXcFramework.map {
    layout.buildDirectory.dir("vendorXcFrameworks").get()
}
// cinterops are configured below with plain compilerOpts strings, so Gradle can't infer this
// dependency on its own — same wiring as razorpay-checkout's fetch task.
tasks.matching { it.name.startsWith("cinteropHyperSDK") }.configureEach {
    // Same reasoning as razorpay-checkout's cinterop inputs: dependsOn orders, these invalidate.
    // All three versions, since all three frameworks are on the -F path and any of their headers
    // can change what the bindings resolve to.
    inputs.property("hypersdk", iosVendorSdkVersion)
    inputs.property("hypercore", hyperCoreVersion)
    inputs.property("airborne", airborneVersion)
    dependsOn(fetchHyperSdkXcFramework)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.getlokalapp.paymentsdk.juspay"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest {}
    }

    // Direct Kotlin/Native cinterops against the fetched HyperSDK.xcframework — no
    // CocoaPods (see docs/cocoapods-to-spm-migration-plan.md, R1/S2). HyperSDK.def's
    // `package = vendor.HyperSDK` is where the generated bindings land, so iosMain imports
    // `import vendor.HyperSDK.HyperServices`.
    // No `framework {}` block: per the umbrella-framework insight only the CONSUMER
    // assembles an XCFramework; this module just compiles and ships as a plain klib on Maven.
    // All three vendor frameworks go on the -F path per slice: HyperSDK's module can't be
    // resolved without HyperCore (imported by its headers) and Airborne (@import'd by its
    // Swift header) alongside it. Device targets use the ios-arm64 slice; simulator targets
    // the ios-arm64_x86_64-simulator slice.
    fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.hyperSdkCinterop(slice: String) {
        compilations.getByName("main").cinterops.create("HyperSDK") {
            defFile(project.file("src/nativeInterop/cinterop/HyperSDK.def"))
            val base = vendorXcFrameworksDir.get().asFile
            compilerOpts(
                "-fmodules",
                "-F${base}/HyperSDK.xcframework/$slice",
                "-F${base}/HyperCore.xcframework/$slice",
                "-F${base}/Airborne.xcframework/$slice",
            )
        }
    }
    iosArm64 { hyperSdkCinterop("ios-arm64") }
    iosX64 { hyperSdkCinterop("ios-arm64_x86_64-simulator") }
    iosSimulatorArm64 { hyperSdkCinterop("ios-arm64_x86_64-simulator") }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateModuleVersion)
            dependencies {
                api(project(":shared"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        androidMain {
            kotlin.srcDir(generateAndroidVendorVersion)
            dependencies {
                // compileOnly (D4): host applies the hypersdk plugin which supplies
                // these at runtime; consumers who don't use Juspay must not get them
                // on their classpath. Confirmed real, public artifacts (R2).
                compileOnly(libs.juspay.hypersdk)
                compileOnly(libs.juspay.hyperinteg)
                implementation(libs.androidx.fragment)
            }
        }
        iosMain {
            kotlin.srcDir(generateIosVendorVersion)
        }
    }
}
