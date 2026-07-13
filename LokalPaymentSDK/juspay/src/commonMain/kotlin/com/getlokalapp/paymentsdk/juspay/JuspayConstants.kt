package com.getlokalapp.paymentsdk.juspay

/** Event names on HyperSDK's onEvent/callback stream. Verified against matrimony. */
internal object JuspayEvents {
    const val INITIATE_RESULT = "initiate_result"
    const val HIDE_LOADER = "hide_loader"
    const val PROCESS_RESULT = "process_result"
}

/** Payment statuses inside a process_result event. Verified against matrimony (D6). */
internal enum class JuspayStatus(val wire: String) {
    CHARGED("charged"),
    AUTHORIZING("authorizing"),
    PENDING_VBV("pending_vbv"),
    BACKPRESSED("backpressed"),
    USER_ABORTED("user_aborted");

    companion object {
        /** Case-insensitive lookup; null = status we don't recognize. */
        fun fromWire(raw: String): JuspayStatus? =
            entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
    }
}
