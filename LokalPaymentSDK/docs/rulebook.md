# LokalPaymentSDK — Rulebook

Hard constraints for this project. Unlike `architecture-reference.md`
(historical planning) or `gateway-modularization-plan.md` (a specific
change's plan), this doc holds rules that apply to *all* future work here,
regardless of which feature or module it touches.

> **Adding a new payment gateway?** Read
> [`adding-a-new-gateway.md`](adding-a-new-gateway.md) — the current-state
> playbook + rulebook for that specific task (step-by-step recipe, the core
> contract, both module templates, and gateway-specific hard rules). This
> file's "No Compose" rule applies there too.

## No Compose (CMP) in the SDK — KMP/KMM only

`LokalPaymentSDK` and every module inside it (`:shared`, `:gateways:razorpay-checkout`,
`:gateways:razorpay-customui`, and any gateway module added later) **must stay pure
Kotlin Multiplatform** — no dependency on Jetpack Compose or Compose
Multiplatform (`org.jetbrains.compose`, `androidx.compose.*`), directly or
transitively, in any of these modules' own `build.gradle.kts`.

**Why:** This SDK will be consumed at a different level later — by host apps
that may not use Compose at all (native Android Views, SwiftUI, or anything
else). Pulling Compose Multiplatform into a gateway module forces that UI
framework onto every consumer just to call `pay()`, which has nothing to do
with UI. Established while building the UPI Intent flow's lifecycle
management (2026-07-07): a `rememberRazorpayCheckoutSdk()`-style composable
was built and then explicitly rejected for exactly this reason.

**Where Compose *is* fine:** `LokalPaymentSDKDemo` (and any other consumer
app) can and should use Compose freely — the constraint is scoped to
`LokalPaymentSDK`'s own modules, not its consumers.

**Practical consequence:** lifecycle-bound SDK features (e.g.
`PaymentGatewayHandler` registration with `LokalPaymentSdk`) expose plain,
framework-agnostic hooks instead of Compose composables:
- Registration happens in the gateway SDK class's own constructor (e.g.
  `RazorpayCheckoutSdk(presenter)` registers itself with `LokalPaymentSdk` in
  `init {}`) — construction *is* "when the required params are supplied,"
  with no separate registration step needed.
- Cleanup is an explicit `dispose()` call (default implementation on
  `PaymentGatewayHandler`, overridable per handler) — the host calls it
  whenever it already knows the underlying platform context (Activity,
  `PaymentPresenter`) is going away. On Android this is naturally wrapped in
  a Compose `DisposableEffect` *by the host app*, but that wrapping is the
  host's business, not the SDK's.

Before adding any dependency to a module under `LokalPaymentSDK/`, check
whether it pulls in Compose Multiplatform transitively — it shouldn't.
