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

// Single source for the iOS Razorpay pod version — feeds the cocoapods block
// and generateIosVendorVersion below, and (via the same catalog entry) the
// razorpay-cocoapods-host plugin's podspec pin, so none can drift.
val iosVendorSdkVersion = libs.versions.razorpay.pod.ios.get()

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
// Cacheable via Gradle's normal input/output tracking (keyed on iosVendorSdkVersion);
// re-fetches only when the pinned version changes. Requires network access at build
// time — offline/CI caching of this artifact is a known follow-up, not solved here.
val fetchRazorpayXcFramework = tasks.register("fetchRazorpayXcFramework") {
    inputs.property("version", iosVendorSdkVersion)
    val outputDir = layout.buildDirectory.dir("vendorXcFrameworks/Razorpay.xcframework")
    outputs.dir(outputDir)
    doLast {
        val version = iosVendorSdkVersion
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.parentFile.mkdirs()
        val work = temporaryDir
        val tarball = work.resolve("razorpay-pod-$version.tar.gz")
        // Plain ProcessBuilder, not Project.exec: this Gradle version doesn't expose
        // exec() on a lazily-registered task's doLast — and a portable shell-out to
        // curl/tar needs nothing more (macOS ships both; this is an iOS-only build).
        fun run(vararg command: String) {
            val process = ProcessBuilder(*command).inheritIO().start()
            check(process.waitFor() == 0) { "Command failed: ${command.joinToString(" ")}" }
        }
        run(
            "curl", "-sL", "-o", tarball.absolutePath,
            "https://codeload.github.com/razorpay/razorpay-pod/tar.gz/refs/tags/$version",
        )
        run(
            "tar", "xzf", tarball.absolutePath, "-C", work.absolutePath,
            "razorpay-pod-$version/Pod/Razorpay.xcframework",
        )
        work.resolve("razorpay-pod-$version/Pod/Razorpay.xcframework")
            .copyRecursively(out, overwrite = true)
    }
}
val razorpayXcFrameworkDir = fetchRazorpayXcFramework.map {
    layout.buildDirectory.dir("vendorXcFrameworks/Razorpay.xcframework").get()
}
// Every generated cinterop-processing task must run fetchRazorpayXcFramework first —
// cinterops are configured below via plain compilerOpts strings, so Gradle can't infer
// this dependency on its own.
tasks.matching { it.name.startsWith("cinteropRazorpay") }.configureEach {
    dependsOn(fetchRazorpayXcFramework)
}

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
