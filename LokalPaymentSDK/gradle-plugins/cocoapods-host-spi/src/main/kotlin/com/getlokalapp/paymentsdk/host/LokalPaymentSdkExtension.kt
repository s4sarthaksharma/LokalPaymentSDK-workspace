package com.getlokalapp.paymentsdk.host

/**
 * Backs the host's `lokalPaymentSdk { }` DSL and is handed to every
 * [LokalGatewayHostContributor]. Intentionally build-time only: values here shape
 * the iOS build (podspec / Podfile / generated config files), NOT the SDK's runtime
 * behavior — runtime configuration flows through the SDK's Kotlin init API instead.
 *
 * Empty for now (razorpay needs no build-time input). Gateway-specific fields —
 * e.g. a juspay client id that feeds MerchantConfig.txt — are added here as each
 * gateway migrates onto the umbrella plugin.
 */
open class LokalPaymentSdkExtension
