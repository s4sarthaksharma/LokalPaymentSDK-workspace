rootProject.name = "LokalPaymentSDK"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // Hosts the plain in.juspay:hypersdk/hyperinteg artifacts :juspay compiles
        // against (compileOnly, D4) — confirmed public, no plugin required (R2).
        maven("https://maven.juspay.in/jp-build-packages/hyper-sdk/")
    }
}

// Gateway-agnostic build plumbing (SPI jars + umbrella plugins). Gateway-specific
// contributors live under their own gateway module (e.g. :gateways:juspay:host-contributor).
// :gradle-plugins:cocoapods-host-contributor-support is not a project — it's shared
// source srcDir'd into each host contributor, so it is intentionally not included.
include(":gradle-plugins:cocoapods-host-spi")
include(":gradle-plugins:cocoapods-host-plugin")
include(":gradle-plugins:settings-spi")
include(":gradle-plugins:settings-plugin")
// SPM-flavored siblings of the cocoapods-host pair (see
// docs/cocoapods-to-spm-migration-plan.md, D5) — a host applies one or the other.
include(":gradle-plugins:spm-host-spi")
include(":gradle-plugins:spm-host-plugin")

// Core runtime + building blocks shared by the gateways.
include(":shared")
include(":webview")

// Payment gateways / methods. Each gateway's own build-time contributors nest under it.
include(":gateways:razorpay-checkout")
include(":gateways:razorpay-checkout:host-contributor")
include(":gateways:razorpay-checkout:spm-host-contributor")
include(":gateways:razorpay-customui")
include(":gateways:native-iap")
include(":gateways:upi-intent")
include(":gateways:web-checkout")
include(":gateways:juspay")
include(":gateways:juspay:android-host-plugin")
include(":gateways:juspay:settings-contributor")
include(":gateways:juspay:host-contributor")
