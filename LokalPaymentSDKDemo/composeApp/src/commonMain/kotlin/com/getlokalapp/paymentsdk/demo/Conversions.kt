package com.getlokalapp.paymentsdk.demo

import com.getlokalapp.paymentsdk.model.LokalPaymentEvent
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import com.getlokalapp.paymentsdk.model.PaymentOrder
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.paymentsdk.upi.UpiApp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The SDK expects a typed PaymentOrder and no longer parses JSON itself, so
// the host does it. A real host would decode into its own backend DTOs; here
// we pull the two fields the SDK needs straight off the JSON tree.
// ignoreUnknownKeys-style leniency is implicit with this element API — the
// extra sibling fields (e.g. order_row_id) are simply left unread.
private val orderJson = Json { ignoreUnknownKeys = true }

internal fun parseOrder(orderResponseJson: String): PaymentOrder {
    val root = orderJson.parseToJsonElement(orderResponseJson).jsonObject
    val gatewayCode = root.getValue("gateway").jsonPrimitive.content
    // Mapping the backend's gateway code to the typed PaymentGateway is now
    // the host's job — the SDK takes an already-resolved enum. An unknown
    // code can't produce a PaymentOrder at all, so we surface it here.
    val gateway = PaymentGateway.fromCode(gatewayCode)
        ?: error("Unknown gateway code from backend: $gatewayCode")
    return PaymentOrder(
        gateway = gateway,
        gatewayConfig = root.getValue("gateway_config").jsonObject,
        // Host-owned passthrough: the SDK never reads this and no gateway sees
        // it — it comes straight back on LokalPaymentEvent.metadata (see
        // render()), which is how a real host correlates a result to the call
        // that started it. Optional, so an order without it still parses.
        metadata = root["metadata"]?.jsonObject,
    )
}

// Formats the UPI apps the SDK detected. Results are platform-shaped: Android
// yields real package names (dynamic PackageManager query), iOS yields URL
// schemes from a curated catalog (canOpenURL) — so we print whichever the
// UpiApp carries.
internal fun renderUpiApps(apps: List<UpiApp>): String {
    if (apps.isEmpty()) return "No UPI apps detected"
    return buildString {
        appendLine("Installed UPI apps (${apps.size}):")
        apps.forEach { app ->
            val id = app.packageName ?: app.urlScheme?.let { "$it://" } ?: "—"
            appendLine("• ${app.displayName}  [$id]")
        }
    }.trimEnd()
}

// Dumps whatever the SDK hands back on its pay() flow: a LokalPaymentEvent
// wrapping either an interim "gateway has taken over its own UI" heads-up or a
// terminal PaymentResult, plus the routing gateway and the host's metadata echo.
internal fun render(event: LokalPaymentEvent): String {
    val header = "gateway = ${event.gateway}"
    val body = when (val ev = event.event) {
        PaymentGatewayEvent.UiPresented -> "UI presented"
        is PaymentResult -> renderResult(ev)
    }
    // Echoed straight back from PaymentOrder.metadata — the host set it (see
    // parseOrder), the SDK carried it through untouched.
    val meta = event.metadata?.let { "\nmetadata = $it" } ?: ""
    return "$header\n$body$meta"
}

private fun renderResult(result: PaymentResult): String = when (result) {
    is PaymentResult.Success -> """
        Success
        gatewayData = ${result.gatewayData}
    """.trimIndent()

    is PaymentResult.Cancelled -> """
        Cancelled
        reason = ${result.reason}
    """.trimIndent()

    is PaymentResult.Failure -> """
        Failure
        code    = ${result.code ?: "—"}
        message = ${result.message}
    """.trimIndent()

    is PaymentResult.Pending -> """
        Pending (verify with backend)
        gatewayData = ${result.gatewayData}
    """.trimIndent()
}
