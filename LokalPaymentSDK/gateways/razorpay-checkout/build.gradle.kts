import com.getlokalapp.paymentsdk.buildsrc.registerModuleVersionTask
import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask
import com.getlokalapp.paymentsdk.buildsrc.installTarGzDirectory
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

// Single source for the iOS Razorpay SDK version — feeds fetchRazorpayXcFramework
// (the xcframework the cinterops compile against) and generateIosVendorVersion below,
// and (via the same catalog entry) the razorpay host-contributor's
// `.package(url:, exact:)` pin, so none can drift.
val iosVendorSdkVersion = libs.versions.razorpay.spm.ios.get()

// Bakes this module's own version (root gradle.properties) into commonMain,
// same pattern as :shared's generatePaymentSdkVersion — so GatewayMetadata's
// moduleVersion can never drift from the published artifact version.
val generateModuleVersion = registerModuleVersionTask(
    taskName = "generateModuleVersion",
    packageName = "com.getlokalapp.paymentsdk.razorpay",
)

// Bakes gradle/libs.versions.toml's razorpay-checkout entry into androidMain
// as GatewayMetadata's Android vendorSdkVersion — the same version this
// module compiles Android against, so it can't drift.
val generateAndroidVendorVersion = registerVendorVersionTask(
    taskName = "generateAndroidVendorVersion",
    packageName = "com.getlokalapp.paymentsdk.razorpay",
    vendorSdkVersion = libs.versions.razorpay.checkout.get(),
)

// Bakes this build script's iosVendorSdkVersion (the razorpay-pod version
// below) into iosMain as GatewayMetadata's iOS vendorSdkVersion.
val generateIosVendorVersion = registerVendorVersionTask(
    taskName = "generateIosVendorVersion",
    packageName = "com.getlokalapp.paymentsdk.razorpay",
    vendorSdkVersion = iosVendorSdkVersion,
)

// Fetches razorpay-pod's vendored Razorpay.xcframework straight from its GitHub tag
// (no CocoaPods) so the cinterops below have real headers/module maps to compile
// against — the SPM-era replacement for CocoaPods resolving `pod("razorpay-pod")`.
// Cacheable via Gradle's normal input/output tracking (keyed on version);
// re-fetches only when the version changes. This archive is used only by the SDK producer
// build to compile the published Kotlin/Native bindings; the framework itself is not
// shipped in the gateway klib. A host resolves the same pinned package independently
// through SwiftPM. Requires network access at producer-build time.
val fetchRazorpayXcFramework = tasks.register("fetchRazorpayXcFramework") {
    inputs.property("version", iosVendorSdkVersion)
    val outputDir = layout.buildDirectory.dir("vendorXcFrameworks/Razorpay.xcframework")
    outputs.dir(outputDir)
    doLast {
        val version = iosVendorSdkVersion
        val out = outputDir.get().asFile
        val work = temporaryDir
        val tarball = work.resolve("razorpay-pod-$version.tar.gz")
        installTarGzDirectory(
            url = "https://codeload.github.com/razorpay/razorpay-pod/tar.gz/refs/tags/$version",
            archiveFile = tarball,
            extractionRoot = work,
            archiveDirectory = "razorpay-pod-$version/Pod/Razorpay.xcframework",
            destination = out,
        )
    }
}
val razorpayXcFrameworkDir = fetchRazorpayXcFramework.map {
    layout.buildDirectory.dir("vendorXcFrameworks/Razorpay.xcframework").get()
}
// Every generated cinterop-processing task must run fetchRazorpayXcFramework first —
// cinterops are configured below via plain compilerOpts strings, so Gradle can't infer
// this dependency on its own.
tasks.matching { it.name.startsWith("cinteropRazorpay") }.configureEach {
    // dependsOn only orders these two; without a declared input the bindings stay UP-TO-DATE
    // across a vendor bump — exactly when the ObjC surface changes — and iosMain compiles against
    // the previous version's interface. The pinned version fully determines the fetched headers,
    // so tracking it is as precise as snapshotting the xcframework and far cheaper than hashing
    // a tree that size on every build.
    inputs.property("razorpayPodVersion", iosVendorSdkVersion)
    dependsOn(fetchRazorpayXcFramework)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.getlokalapp.paymentsdk.checkout"
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

    // Direct Kotlin/Native cinterops against the fetched Razorpay.xcframework — no
    // CocoaPods (see docs/cocoapods-to-spm-migration-plan.md, R1). Razorpay.def's
    // `package = cocoapods.razorpay_pod` reproduces the cocoapods plugin's generated
    // package exactly, so iosMain's existing imports need no changes. No `framework {}`
    // block here: per the plan doc's umbrella-framework insight, only the CONSUMER
    // (e.g. composeApp) needs to assemble an XCFramework — this module just needs to
    // compile, and ships as a plain klib on Maven like every other target.
    iosArm64 {
        compilations.getByName("main").cinterops.create("Razorpay") {
            defFile(project.file("src/nativeInterop/cinterop/Razorpay.def"))
            compilerOpts("-fmodules", "-F${razorpayXcFrameworkDir.get().asFile}/ios-arm64")
        }
    }
    iosX64 {
        compilations.getByName("main").cinterops.create("Razorpay") {
            defFile(project.file("src/nativeInterop/cinterop/Razorpay.def"))
            compilerOpts("-fmodules", "-F${razorpayXcFrameworkDir.get().asFile}/ios-arm64_x86_64-simulator")
        }
    }
    iosSimulatorArm64 {
        compilations.getByName("main").cinterops.create("Razorpay") {
            defFile(project.file("src/nativeInterop/cinterop/Razorpay.def"))
            compilerOpts("-fmodules", "-F${razorpayXcFrameworkDir.get().asFile}/ios-arm64_x86_64-simulator")
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
        androidMain {
            kotlin.srcDir(generateAndroidVendorVersion)
            dependencies {
                // implementation, not api: Razorpay is fully encapsulated behind
                // the internal RazorpayCheckoutActivity proxy — no public SDK type
                // exposes a Razorpay class, so consumers don't need it on their
                // compile classpath (it's still there transitively at runtime).
                implementation(libs.razorpay.checkout)
            }
        }
        iosMain {
            kotlin.srcDir(generateIosVendorVersion)
        }
    }
}
