package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.BundledResource
import com.getlokalapp.paymentsdk.host.HostContribution
import com.getlokalapp.paymentsdk.host.InfoPlistEntries
import com.getlokalapp.paymentsdk.host.PrebuildStep
import com.getlokalapp.paymentsdk.host.SourceTarget
import com.getlokalapp.paymentsdk.host.VendorPackage

/**
 * Every active gateway's [HostContribution]s, sorted by kind — the one place the flat list the
 * contributors returned is taken apart, so each writer receives exactly the kinds it consumes
 * instead of the whole list plus the knowledge of how to filter it.
 *
 * Lives in the plugin rather than the SPI on purpose: gateways compile against host-spi, so a
 * type there is part of the binary-compatibility surface (adding a slot to [HostContribution]
 * itself already forces every contributor to be republished in lockstep). Nothing outside the
 * plugin needs this, so nothing outside the plugin sees it.
 */
internal data class Contributions(
    val vendorPackages: List<VendorPackage>,
    val sourceTargets: List<SourceTarget>,
    val plistEntries: List<InfoPlistEntries>,
    val prebuildSteps: List<PrebuildStep>,
    val bundledResources: List<BundledResource>,
)

/**
 * Sorts a flat contribution list into [Contributions].
 *
 * ⚠️ The `when` below deliberately has **no `else` branch**, and that is the entire point of this
 * function existing rather than each writer calling `filterIsInstance` for the kinds it wants.
 * Since Kotlin 1.7 a `when` over a sealed hierarchy must be exhaustive even in statement position,
 * so the `->` arms here are the compiler's proof that every [HostContribution] kind is bucketed:
 * add a kind and this stops compiling until it is. Adding an `else` (or going back to per-writer
 * filters) silently restores the old failure mode, where a new kind type-checks everywhere and is
 * quietly dropped at build time. Verified by adding a throwaway subtype and watching the build
 * fail — worth redoing if this ever gets "tidied".
 *
 * Bucketing a new kind is still only half the change — wire its consumer in the same commit, since
 * a bucket nothing reads is just as inert as no bucket at all.
 */
internal fun List<HostContribution>.bucketed(): Contributions {
    val vendorPackages = mutableListOf<VendorPackage>()
    val sourceTargets = mutableListOf<SourceTarget>()
    val plistEntries = mutableListOf<InfoPlistEntries>()
    val prebuildSteps = mutableListOf<PrebuildStep>()
    val bundledResources = mutableListOf<BundledResource>()

    forEach { contribution ->
        when (contribution) {
            is VendorPackage -> vendorPackages += contribution
            is SourceTarget -> sourceTargets += contribution
            is InfoPlistEntries -> plistEntries += contribution
            is PrebuildStep -> prebuildSteps += contribution
            is BundledResource -> bundledResources += contribution
        }
    }

    // Exposed as read-only Lists so a writer can't mutate what the other writers see.
    return Contributions(
        vendorPackages = vendorPackages,
        sourceTargets = sourceTargets,
        plistEntries = plistEntries,
        prebuildSteps = prebuildSteps,
        bundledResources = bundledResources,
    )
}
