package com.getlokalapp.paymentsdk.host

/**
 * Convention shared between `SharedCocoapodsPlugin` (which owns the single managed
 * `post_install` block in the host's Podfile) and any [LokalGatewayHostContributor]
 * that needs to run logic after `pod install` (e.g. Juspay's merchant-asset
 * download). CocoaPods' `post_install` hook doesn't chain — a second
 * `post_install do |installer| ... end` in the Podfile silently replaces the first
 * — so no contributor writes its own. Instead each drops a small Ruby snippet file
 * named after its own gateway into [BUILD_RELATIVE_DIR] (resolved against the
 * cocoapods-owning module's Gradle build directory); the shared plugin's one
 * managed block globs every file found there, in sorted order, and `eval`s it with
 * `installer` in scope — mirroring how `lokal_ios_pods` already discovers pods by
 * convention (glob a directory) rather than a Kotlin-side registry.
 */
object LokalPostInstallSnippets {
    const val BUILD_RELATIVE_DIR = "lokal/postInstall"
}
