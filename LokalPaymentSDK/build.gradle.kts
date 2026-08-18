import com.getlokalapp.paymentsdk.buildsrc.registerTestSuiteGuard

plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

// Fails the build when any runtime module has no executing tests, so a `NO-SOURCE`
// test task can never masquerade as a passing suite (docs/SDK_REVIEW.md). Enumerates
// KMP subprojects dynamically — `include(...)` alone brings a new gateway under it.
// Each runtime module's `check` depends on this via paymentSdkTestConventions().
registerTestSuiteGuard()
