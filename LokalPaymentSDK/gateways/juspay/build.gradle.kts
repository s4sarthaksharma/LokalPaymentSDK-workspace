import com.getlokalapp.paymentsdk.buildsrc.registerModuleVersionTask
import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask
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
// host-contributor's `.package(url:, exact:)` pin, so none can drift. This is the
// SPM train (2.2.8), separate from the legacy CocoaPods `juspay-pod-ios` (2.2.8.1)
// the CocoaPods host-contributor still pins until Phase 3 retires it.
val iosVendorSdkVersion = libs.versions.juspay.spm.ios.get()

// Bakes this module's own version (root gradle.properties) into commonMain,
// same pattern as :shared's generatePaymentSdkVersion — so GatewayMetadata's
// moduleVersion can never drift from the published artifact version.
val generateModuleVersion = registerModuleVersionTask(
    taskName = "generateModuleVersion",
    packageName = "com.getlokalapp.paymentsdk.juspay",
)

// Bakes gradle/libs.versions.toml's juspay-hypersdk entry into androidMain as
// GatewayMetadata's Android vendorSdkVersion — the same version this module
// compiles Android against (and JuspayAndroidHostPlugin.DEFAULT_SDK_VERSION pins the
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

// Fetches HyperSDK.xcframework (+ HyperCore, Airborne) straight from Juspay's public release
// CDN — the same zips github.com/juspay/hypersdk-ios's binary targets point at — so the
// cinterops below have real headers/module maps to compile against. The SPM-era replacement
// for CocoaPods resolving `pod("HyperSDK")`. No CocoaPods synthetic build, so the pod's
// "[CP-User] Validate Mandatory Files" gate (and the old SKIP_HYPERSDK_VALIDATION workaround)
// is gone entirely; the merchant-asset pipeline is a consumer-side concern now (juspay
// host-contributor). Cacheable via input/output tracking; re-fetches only on version
// change. Requires network at build time (an iOS-only, macOS-only build).
val fetchHyperSdkXcFramework = tasks.register("fetchHyperSdkXcFramework") {
    inputs.property("hypersdk", iosVendorSdkVersion)
    inputs.property("hypercore", hyperCoreVersion)
    inputs.property("airborne", airborneVersion)
    val outputDir = layout.buildDirectory.dir("vendorXcFrameworks")
    outputs.dir(outputDir)
    doLast {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val work = temporaryDir
        // Plain ProcessBuilder, not Project.exec (unavailable on a lazily-registered task's
        // doLast in this Gradle version) — mirrors razorpay-checkout's fetch task. Each zip
        // has its <Name>.xcframework at the root, so unzip and copy it straight out.
        fun run(vararg command: String) {
            val process = ProcessBuilder(*command).inheritIO().start()
            check(process.waitFor() == 0) { "Command failed: ${command.joinToString(" ")}" }
        }
        fun fetch(name: String, version: String, cdnPath: String) {
            val zip = work.resolve("$name-$version.zip")
            run(
                "curl", "-sL", "-o", zip.absolutePath,
                "https://public.releases.juspay.in/release/ios/$cdnPath/$version/$name.zip",
            )
            run("unzip", "-q", "-o", zip.absolutePath, "-d", work.absolutePath)
            work.resolve("$name.xcframework").copyRecursively(out.resolve("$name.xcframework"), overwrite = true)
        }
        fetch("HyperSDK", iosVendorSdkVersion, "hyper-sdk")
        fetch("HyperCore", hyperCoreVersion, "hyper-core")
        fetch("Airborne", airborneVersion, "airborne")
    }
}
val vendorXcFrameworksDir = fetchHyperSdkXcFramework.map {
    layout.buildDirectory.dir("vendorXcFrameworks").get()
}
// cinterops are configured below with plain compilerOpts strings, so Gradle can't infer this
// dependency on its own — same wiring as razorpay-checkout's fetch task.
tasks.matching { it.name.startsWith("cinteropHyperSDK") }.configureEach {
    dependsOn(fetchHyperSdkXcFramework)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidLibrary {
        namespace = "com.getlokalapp.paymentsdk.juspay"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

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
