package com.nonsense

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * The one place that knows there is a shop.
 *
 * [Toy] only ever reads [Toy.tier], and [NonsenseView] only ever asks for a
 * purchase and is told later what the answer was. That boundary is the point:
 * the gate is the same code whether the answer comes from Play, from an App
 * Store, or from a licence file, and none of it needs a store to be tested.
 *
 * The product is a one-time, non-consumable purchase. That means it is
 * acknowledged and never consumed: consuming it would let it be bought again,
 * which is not what "bought once" means.
 *
 * A locally cached tier is deliberately trusted at launch, so a paying
 * customer does not stare at the free tier while the store connects. It is
 * also trivially forgeable by anyone who wants to root their phone to unlock a
 * fidget toy, which is a trade worth making — the alternative is a server, an
 * account, and a receipt check, for a one-time purchase of a few dollars.
 * Play's own answer arrives moments later and overrides the cache in both
 * directions, so a refund takes the unlock away again.
 */
class Billing(
    context: Context,
    /** Called on the billing thread with the tier and the localised price. */
    private val onTier: (Tier, String?) -> Unit,
) {

    companion object {
        /**
         * Must match the product ID created in the Play Console, exactly.
         * A mismatch is silent: queryProductDetails simply returns nothing
         * and the unlock button sits there doing nothing at all.
         */
        const val PRODUCT_ID = "nonsense_full"
    }

    private var details: ProductDetails? = null
    private var offerToken: String? = null

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (p in purchases) grant(p)
        }
        // Anything else — cancelled, already owned, no network — leaves the
        // tier exactly as it was. There is nothing useful to say about it.
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdated)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                queryProduct()
                restore()
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection handles it.
            }
        })
    }

    fun stop() {
        runCatching { client.endConnection() }
    }

    /** The price, so the button can say what it costs before it is pressed. */
    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, productDetailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val found = productDetailsResult.productDetailsList.firstOrNull() ?: return@queryProductDetailsAsync
            details = found
            val offers = found.oneTimePurchaseOfferDetails
            offerToken = offers?.firstOrNull()?.offerToken
            onTier(Tier.FREE, offers?.firstOrNull()?.formattedPrice)
        }
    }

    /**
     * Play is the source of truth on every launch, and the whole of "restore
     * purchases" as well — there is nothing to restore that a query does not
     * already answer, which is why the button just runs this again.
     */
    fun restore() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val owned = purchases.any {
                it.products.contains(PRODUCT_ID) &&
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            for (p in purchases) if (p.purchaseState == Purchase.PurchaseState.PURCHASED) acknowledge(p)
            onTier(if (owned) Tier.FULL else Tier.FREE, priceText())
        }
    }

    fun buy(activity: Activity) {
        val product = details ?: return
        val token = offerToken ?: return
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(token)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, flow)
    }

    private fun priceText(): String? =
        details?.oneTimePurchaseOfferDetails?.firstOrNull()?.formattedPrice

    private fun grant(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.products.contains(PRODUCT_ID)) return
        acknowledge(purchase)
        onTier(Tier.FULL, priceText())
    }

    /**
     * Play refunds an unacknowledged purchase automatically after three days,
     * so this is not optional bookkeeping — it is the difference between
     * being paid and not.
     */
    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { }
    }
}
