package com.getlokalapp.paymentsdk.juspay

/** Event names on HyperSDK's onEvent/callback stream. Verified against matrimony. */
internal object JuspayEvents {
    const val INITIATE_RESULT = "initiate_result"
    const val HIDE_LOADER = "hide_loader"
    const val PROCESS_RESULT = "process_result"
}

/** Payment statuses inside a process_result event. Verified against matrimony (D6). */
internal object JuspayStatus {
    const val CHARGED = "charged"
    const val AUTHORIZING = "authorizing"
    const val PENDING_VBV = "pending_vbv"
    const val BACKPRESSED = "backpressed"
    const val USER_ABORTED = "user_aborted"
}
