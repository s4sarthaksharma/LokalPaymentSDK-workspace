import Foundation
import StoreKit

/// Plain-Int outcome so this crosses the Objective-C bridge cleanly —
/// StoreKit 2's own `Product.PurchaseResult` / `VerificationResult` are pure
/// Swift generics and can't be exposed to Kotlin/Native directly.
@objc public enum NativeIapOutcome: Int {
    case success = 0
    case unverified = 1
    case cancelled = 2
    case pending = 3
    case failure = 4
}

/// Everything a purchase attempt or a transaction update can report, flattened
/// into plain Foundation-compatible fields (see [NativeIapOutcome]'s doc for why).
@objc public class NativeIapResult: NSObject {
    @objc public let outcome: NativeIapOutcome
    @objc public let productId: String?
    @objc public let transactionId: String?
    @objc public let appAccountToken: String?
    @objc public let errorMessage: String?

    @objc public init(
        outcome: NativeIapOutcome,
        productId: String?,
        transactionId: String?,
        appAccountToken: String?,
        errorMessage: String?
    ) {
        self.outcome = outcome
        self.productId = productId
        self.transactionId = transactionId
        self.appAccountToken = appAccountToken
        self.errorMessage = errorMessage
    }
}

/// Self-contained StoreKit 2 driver: no host-supplied provider — this module
/// owns the whole purchase flow itself, so a host integrating :native-iap
/// writes zero Swift code, same as every other gateway module.
@objc public class NativeIapBridge: NSObject {

    @objc public static let shared = NativeIapBridge()

    private var transactionUpdateHandler: ((NativeIapResult) -> Void)?
    private var transactionUpdateTask: Task<Void, Never>?

    private override init() {
        super.init()
        transactionUpdateTask = Task(priority: .background) { [weak self] in
            for await verificationResult in Transaction.updates {
                let result = await Self.handle(verificationResult)
                self?.transactionUpdateHandler?(result)
            }
        }
    }

    /// Set from Kotlin (IOSNativeIapClient) to receive deferred/restored
    /// transactions (e.g. Ask to Buy approval) arriving outside any single
    /// [purchaseProduct] call. Pass nil to detach.
    @objc public func setTransactionUpdateHandler(_ handler: ((NativeIapResult) -> Void)?) {
        transactionUpdateHandler = handler
    }

    @objc public func purchaseProduct(
        productId: String,
        appAccountToken: String?,
        completion: @escaping (NativeIapResult) -> Void
    ) {
        Task {
            do {
                guard let product = try await Product.products(for: [productId]).first else {
                    completion(
                        NativeIapResult(
                            outcome: .failure,
                            productId: productId,
                            transactionId: nil,
                            appAccountToken: appAccountToken,
                            errorMessage: "Product not found: \(productId)"
                        )
                    )
                    return
                }

                var options: Set<Product.PurchaseOption> = []
                if let appAccountToken, let token = UUID(uuidString: appAccountToken) {
                    options.insert(.appAccountToken(token))
                }

                let result = try await product.purchase(options: options)
                switch result {
                case .success(let verificationResult):
                    completion(await Self.handle(verificationResult))
                case .userCancelled:
                    completion(
                        NativeIapResult(
                            outcome: .cancelled,
                            productId: productId,
                            transactionId: nil,
                            appAccountToken: appAccountToken,
                            errorMessage: nil
                        )
                    )
                case .pending:
                    completion(
                        NativeIapResult(
                            outcome: .pending,
                            productId: productId,
                            transactionId: nil,
                            appAccountToken: appAccountToken,
                            errorMessage: nil
                        )
                    )
                @unknown default:
                    completion(
                        NativeIapResult(
                            outcome: .failure,
                            productId: productId,
                            transactionId: nil,
                            appAccountToken: appAccountToken,
                            errorMessage: "Unknown StoreKit purchase result"
                        )
                    )
                }
            } catch {
                completion(
                    NativeIapResult(
                        outcome: .failure,
                        productId: productId,
                        transactionId: nil,
                        appAccountToken: appAccountToken,
                        errorMessage: error.localizedDescription
                    )
                )
            }
        }
    }

    private static func handle(_ verificationResult: VerificationResult<Transaction>) async -> NativeIapResult {
        switch verificationResult {
        case .verified(let transaction):
            let appAccountToken = transaction.appAccountToken?.uuidString.lowercased()
            // Finish immediately, matching matrimony-kmp's StoreKitProviderImpl:
            // the backend is the source of truth for whether the purchase counts
            // (validatePayment), finishing here only tells StoreKit "delivered",
            // not "validated".
            if transaction.revocationDate == nil {
                await transaction.finish()
            }
            return NativeIapResult(
                outcome: .success,
                productId: transaction.productID,
                transactionId: String(transaction.id),
                appAccountToken: appAccountToken,
                errorMessage: nil
            )
        case .unverified(let transaction, let error):
            return NativeIapResult(
                outcome: .unverified,
                productId: transaction.productID,
                transactionId: String(transaction.id),
                appAccountToken: nil,
                errorMessage: error.localizedDescription
            )
        }
    }
}
