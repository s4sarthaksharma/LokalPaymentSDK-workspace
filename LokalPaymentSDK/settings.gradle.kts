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

include(":shared")
include(":razorpay-checkout")
include(":razorpay-upi-intent")
include(":juspay")
