import SwiftUI
import UIKit
import Shared

// A real create-order response captured from the backend, used here to
// exercise the SDK's Razorpay Checkout path without an orchestrator yet.
private let sampleCreateOrderResponseJSON = """
{
  "gateway": 1,
  "gateway_config": {
    "razorpay_key": "rzp_test_RRHhT2F4OwJ6hF",
    "data": {
      "name": "Lokal Matrimony",
      "order_id": "order_TADbMIhkW1BiUx",
      "currency": "INR",
      "amount": 19900,
      "readonly": { "contact": true },
      "prefill": { "contact": "1111111111" },
      "method": { "card": false, "upi": true, "netbanking": true, "wallet": true, "emi": false, "paylater": false },
      "theme": { "color": "#D32F2F" },
      "KEY_ID": "rzp_test_RRHhT2F4OwJ6hF"
    },
    "order_row_id": 183452
  }
}
"""

private class PaymentResultHandler: RazorpayPaymentResultListener {
    func onPaymentSuccess(paymentId: String, orderId: String?, signature: String) {
        print("Razorpay success: paymentId=\(paymentId) orderId=\(orderId ?? "nil")")
    }

    func onPaymentError(code: Int32, description: String?) {
        print("Razorpay error [\(code)]: \(description ?? "")")
    }
}

private func currentViewController() -> UIViewController? {
    UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .flatMap { $0.windows }
        .first { $0.isKeyWindow }?
        .rootViewController
}

struct ContentView: View {
    let sdk = LokalPaymentSdk()
    let razorpayClient = IOSRazorpayCheckoutClient()
    private let resultHandler = PaymentResultHandler()

    var body: some View {
        VStack(spacing: 16) {
            Text("LokalPayment SDK")
            Button("Pay with Razorpay") {
                payWithRazorpay()
            }
        }
        .padding()
        .onAppear {
            razorpayClient.setPaymentResultListener(listener: resultHandler)
        }
    }

    private func payWithRazorpay() {
        guard let viewController = currentViewController() else { return }
        let response = CreateOrderResponseJsonKt.parseCreateOrderResponse(json: sampleCreateOrderResponseJSON)
        let config = response.toRazorpayCheckoutConfig()
        razorpayClient.openCheckout(config: config, presenter: PaymentPresenter(viewController: viewController))
    }
}

#Preview {
    ContentView()
}
