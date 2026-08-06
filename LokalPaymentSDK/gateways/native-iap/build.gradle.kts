import com.getlokalapp.paymentsdk.buildsrc.registerIosPodSourcePublication
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

// Native platform in-app purchase. iOS ships a real StoreKit 2 implementation
// today; Android is a stub (registerUnavailable) until Play Billing lands —
// see docs/adding-a-new-gateway.md §3's "iOS-only-for-now gateway" variant.
// StoreKit 2's API (Product, Transaction, VerificationResult) is pure Swift
// async/AsyncSequence, not @objc-visible, so unlike razorpay-checkout this module
// can't cinterop straight against the system StoreKit framework. Instead it owns a
// small Objective-C-visible bridge (ios/NativeIapBridge/NativeIapBridge.swift) —
// real Swift StoreKit 2 code wrapped behind a plain-Foundation-types surface
// Kotlin/Native can actually cinterop against.
//
// SPM (no CocoaPods — see docs/cocoapods-to-spm-migration-plan.md):
//   * SDK side (this file): generateNativeIapBridgeInterface runs swiftc to emit the
//     bridge's generated Objective-C header + a modulemap, and the cinterops below
//     compile the iosMain bindings against that interface. No binary is built here —
//     cinterop only needs the ObjC interface; the actual Swift is compiled and linked
//     on the consumer side. The interface is arch-independent, so all three targets
//     share one generated dir (unlike razorpay's per-slice framework dirs).
//   * Consumer side: :native-iap:host-contributor ships NativeIapBridge.swift into
//     the app's generated umbrella Package.swift as a source target linking StoreKit,
//     resolved from this module's `iossrc` Maven artifact (registerIosPodSourcePublication
//     below). That contributor replaces the local `:path` CocoaPod a host used to name in
//     its Podfile — everything else (registration, purchase orchestration, result mapping)
//     stays fully self-contained, zero host code.

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

// Emits NativeIapBridge's generated Objective-C interface (the `-Swift.h` swiftc produces
// for @objc declarations) plus a modulemap, so the cinterops below have a real ObjC module
// to compile the iosMain bindings against — the SPM-era replacement for the cocoapods plugin
// building the local NativeIapBridge pod and generating its cinterop. Only the *interface* is
// produced (no compiled binary): cinterop resolves symbols at bindings-generation time, and
// the Swift is actually compiled/linked on the consumer side via the SPM source target (see
// :native-iap:host-contributor). The generated header is arch-independent, so one dir
// serves all three iOS targets. Requires `xcrun`/`swiftc` at build time (already an iOS-only,
// macOS-only build). Cacheable via normal input/output tracking; re-runs only when the Swift
// source changes.
val nativeIapBridgeSwift = project.file("ios/NativeIapBridge/NativeIapBridge.swift")
val generateNativeIapBridgeInterface = tasks.register("generateNativeIapBridgeInterface") {
    inputs.file(nativeIapBridgeSwift)
    val outDir = layout.buildDirectory.dir("nativeInterop/nativeIapBridge")
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val header = out.resolve("NativeIapBridge-Swift.h")
        val throwawayModule = temporaryDir.resolve("NativeIapBridge.swiftmodule")
        // Plain ProcessBuilder, not Project.exec: this Gradle version doesn't expose exec()
        // on a lazily-registered task's doLast — mirrors razorpay-checkout's fetch task.
        fun run(vararg command: String) {
            val process = ProcessBuilder(*command).inheritIO().start()
            check(process.waitFor() == 0) { "Command failed: ${command.joinToString(" ")}" }
        }
        // -emit-module drives the compile that -emit-objc-header-path piggybacks on; its
        // output is discarded (we only want the header). Target is arbitrary among iOS
        // triples — the emitted ObjC interface is identical across them.
        run(
            "xcrun", "--sdk", "iphonesimulator", "swiftc",
            "-target", "arm64-apple-ios16.0-simulator",
            "-module-name", "NativeIapBridge",
            "-parse-as-library",
            "-emit-module", "-emit-module-path", throwawayModule.absolutePath,
            "-emit-objc-header-path", header.absolutePath,
            nativeIapBridgeSwift.absolutePath,
        )
        out.resolve("module.modulemap").writeText(
            """
            module NativeIapBridge {
                header "NativeIapBridge-Swift.h"
                export *
            }
            """.trimIndent() + "\n",
        )
    }
}
val nativeIapBridgeInteropDir = generateNativeIapBridgeInterface.map {
    layout.buildDirectory.dir("nativeInterop/nativeIapBridge").get()
}
// cinterops are configured below with plain compilerOpts strings, so Gradle can't infer this
// dependency on its own — same wiring as razorpay-checkout's fetch task.
tasks.matching { it.name.startsWith("cinteropNativeIapBridge") }.configureEach {
    // dependsOn only orders these two — it says nothing about whether cinterop needs to re-run,
    // and the generated header reaches it as an opaque `-I` string Gradle can't see into. Without
    // the generated dir as a declared input, an @objc signature change in NativeIapBridge.swift
    // regenerates the header but leaves the bindings UP-TO-DATE, and iosMain then fails to compile
    // against the stale interface ("No parameter with name ..."). Content-hashed, so a Swift edit
    // that doesn't alter the emitted ObjC surface still skips.
    inputs.dir(nativeIapBridgeInteropDir)
    dependsOn(generateNativeIapBridgeInterface)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.getlokalapp.paymentsdk.nativeiap"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest {}
    }

    // Direct Kotlin/Native cinterops against NativeIapBridge's generated Objective-C
    // interface (generateNativeIapBridgeInterface above) — no CocoaPods. All three targets
    // share the one arch-independent interface dir. NativeIapBridge.def's
    // `package = vendor.NativeIapBridge` is where the generated bindings land — iosMain
    // imports it as vendor.NativeIapBridge.*. No `framework {}`
    // block: per the umbrella-framework insight only the CONSUMER assembles an XCFramework;
    // this module just needs to compile, and ships as a plain klib on Maven.
    val configureNativeIapBridgeCinterop: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.() -> Unit = {
        compilations.getByName("main").cinterops.create("NativeIapBridge") {
            defFile(project.file("src/nativeInterop/cinterop/NativeIapBridge.def"))
            compilerOpts("-fmodules", "-I${nativeIapBridgeInteropDir.get().asFile}")
        }
    }
    iosX64(configureNativeIapBridgeCinterop)
    iosArm64(configureNativeIapBridgeCinterop)
    iosSimulatorArm64(configureNativeIapBridgeCinterop)

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
