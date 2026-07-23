package com.getlokalapp.paymentsdk.host

import org.gradle.api.initialization.Settings

/**
 * A gateway's build-time contribution to a host's `settings.gradle.kts`. Each SDK
 * gateway that needs settings-phase setup — e.g. adding its vendor Maven repo, or
 * pinning a default version for a sibling project plugin — ships one implementation
 * and registers it in
 * `META-INF/services/com.getlokalapp.paymentsdk.host.LokalGatewaySettingsContributor`.
 *
 * The umbrella `com.getlokalapp.paymentsdk.lokal-payment-settings` plugin discovers
 * every implementation on the host's settings buildscript classpath with
 * [java.util.ServiceLoader] and calls [contribute] once during settings evaluation.
 * It is the settings-phase twin of `LokalGatewayHostContributor` (the project-phase
 * SPI dispatched by `com.getlokalapp.paymentsdk.lokal-payment`).
 *
 * **These run unconditionally, and — unlike the project-phase contributors — cannot be
 * gated on the owning gateway.** The project-phase SPIs are gated by their umbrella plugin
 * (it scans the host's declared dependencies and calls only the contributor whose module the
 * host imports); this one can't be, for two independent reasons:
 *  1. **Lifecycle** — the module dependency graph doesn't exist yet during settings
 *     evaluation, so nothing here can tell whether a gateway is in use, and settings-phase
 *     repositories (`pluginManagement` / `dependencyResolutionManagement`) must be declared
 *     *now*, before that graph is ever known.
 *  2. **The contribution is often load-bearing for the bundled classpath** — e.g. the android
 *     umbrella unconditionally bundles the Juspay host contributor, which transitively pulls
 *     `in.juspay:hypersdk.plugin`, so `in.juspay` must be in `pluginManagement` for *any* host
 *     that applies that umbrella, just to resolve the plugin classpath. `JuspaySettingsContributor`
 *     adding that repo is precisely what makes the bundled plugin resolvable; gating it away would
 *     break the build rather than merely skip a no-op.
 *
 * Design rule that follows: **keep a settings contributor to the minimum that must happen
 * before the graph exists** (a repo needed to resolve the unconditionally-bundled classpath),
 * and put anything gateable in the gateway's project-phase contributor
 * ([LokalGatewayHostContributor] / `LokalGatewayHostAndroidContributor`), which *is* gated.
 * Adding an unused Maven repo here is otherwise a harmless no-op (ideally scoped with repository
 * content filtering so a host that doesn't use the gateway never consults it).
 */
interface LokalGatewaySettingsContributor {
    fun contribute(settings: Settings)
}
