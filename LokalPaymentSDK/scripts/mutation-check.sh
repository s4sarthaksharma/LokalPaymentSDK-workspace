#!/usr/bin/env bash
# Sanity-checks that the test suite actually catches bugs, rather than merely passing.
#
# Each entry breaks one thing in production code, runs the test that should catch it, and
# reverts. A "NOT CAUGHT" row means that test is vacuous — it passes whether the code is right
# or wrong. Two findings in this repo (docs/TESTING_01 F4, docs/TESTING_02 F6) were exactly
# that failure mode, so this is worth re-running whenever a test's meaningfulness is in doubt.
#
# Usage: bash scripts/mutation-check.sh          (from LokalPaymentSDK/)
set -u

fail=0

mutate() {
  desc="$1"; file="$2"; from="$3"; to="$4"; task="$5"; filter="$6"
  if ! grep -q "$from" "$file"; then
    printf "%-46s SKIP (anchor moved: %s)\n" "$desc" "$from"
    fail=1
    return
  fi
  cp "$file" /tmp/mutation-orig.bak
  python3 - "$file" "$from" "$to" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(path).read()
open(path, "w").write(src.replace(old, new, 1))
PY
  if ./gradlew "$task" --tests "$filter" --rerun -q >/tmp/mutation-out.txt 2>&1; then
    printf "%-46s NOT CAUGHT  <-- vacuous test\n" "$desc"
    fail=1
  else
    printf "%-46s caught\n" "$desc"
  fi
  cp /tmp/mutation-orig.bak "$file"
}

mutate "http accepted as a trusted scheme" \
  webview/src/commonMain/kotlin/com/getlokalapp/paymentsdk/webview/WebViewConfig.kt \
  'if (scheme != HTTPS_SCHEME) return null' \
  'if (scheme != HTTPS_SCHEME && scheme != "http") return null' \
  :webview:testAndroidHostTest '*BridgeHostPolicyTest'

mutate "customui cancel code copied from checkout" \
  gateways/razorpay-customui/src/commonMain/kotlin/com/getlokalapp/paymentsdk/razorpay/RazorpayCustomUiResult.kt \
  'const val PAYMENT_CANCELLED = 5' \
  'const val PAYMENT_CANCELLED = 0' \
  :gateways:razorpay-customui:testAndroidHostTest '*RazorpayCustomUiResultTest'

mutate "juspay charged demoted to pending" \
  gateways/juspay/src/commonMain/kotlin/com/getlokalapp/paymentsdk/juspay/JuspayMappings.kt \
  'PaymentResult.Success(data)' \
  'PaymentResult.Pending(data)' \
  :gateways:juspay:testAndroidHostTest '*JuspayMappingsTest'

mutate "upiParam accepts an empty key" \
  gateways/upi-intent/src/commonMain/kotlin/com/getlokalapp/paymentsdk/upiintent/UpiIntentResultMapper.kt \
  'if (eq > 0 &&' \
  'if (eq >= 0 &&' \
  :gateways:upi-intent:testAndroidHostTest '*UpiIntentResultMapperTest'

mutate "razorpay signature wire key renamed" \
  gateways/razorpay-checkout/src/commonMain/kotlin/com/getlokalapp/paymentsdk/razorpay/RazorpayResultMapper.kt \
  '@SerialName("razorpay_signature")' \
  '@SerialName("signature")' \
  :gateways:razorpay-checkout:testAndroidHostTest '*RazorpayResultMapperTest'

echo
if [ "$fail" -eq 0 ]; then
  echo "All mutations were caught."
else
  echo "Some mutations were not caught, or an anchor moved. See rows above."
fi
git status --short -- '*/src/commonMain' | grep -v WebViewCallbackSafety || true
exit "$fail"
