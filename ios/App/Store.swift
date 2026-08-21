import Foundation
import StoreKit
import NonsenseCore

/// The one place that knows there is a shop.
///
/// The Android twin of this file is `Billing.kt`, and it exists for the same
/// reason: `Toy` reads a tier and nothing else, so the gate is testable
/// without a store and the store is replaceable without touching the gate.
///
/// StoreKit 2 does the work that Play Billing needs code for. There is no
/// acknowledge step — `finish()` is the whole of it — and `currentEntitlements`
/// answers "do they own this" directly, which makes "restore purchases" a
/// re-query rather than a separate flow. A refund shows up as the entitlement
/// disappearing, so the same refresh handles it.
@MainActor
final class Store: ObservableObject {

    /// Must match the product ID in App Store Connect, exactly. A mismatch is
    /// silent: `Product.products` returns nothing and the unlock button sits
    /// there doing nothing at all.
    static let productID = "com.nonsense.full"

    @Published private(set) var tier: Tier = .free
    @Published private(set) var price: String?

    private var product: Product?
    private var updates: Task<Void, Never>?

    init() {
        // A purchase can land while the app is running — bought on another
        // device, or a deferred "ask to buy" being approved.
        updates = Task { [weak self] in
            for await update in Transaction.updates {
                if case .verified(let t) = update { await t.finish() }
                await self?.refresh()
            }
        }
    }

    deinit { updates?.cancel() }

    func start() async {
        await loadProduct()
        await refresh()
    }

    private func loadProduct() async {
        product = try? await Product.products(for: [Self.productID]).first
        price = product?.displayPrice
    }

    /// The source of truth, and the whole of "restore purchases": there is
    /// nothing to restore that asking again does not already answer.
    func refresh() async {
        var owned = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let t) = result else { continue }
            if t.productID == Self.productID && t.revocationDate == nil { owned = true }
        }
        tier = owned ? .full : .free
        if price == nil { await loadProduct() }
    }

    func buy() async {
        guard let product else { return }
        guard let result = try? await product.purchase() else { return }
        switch result {
        case .success(let verification):
            if case .verified(let t) = verification {
                await t.finish()
                await refresh()
            }
        case .pending, .userCancelled:
            break            // nothing useful to say about either
        @unknown default:
            break
        }
    }
}
