// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import android.app.Application
import app.kofipod.ui.ActivityHolder
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Google Play Billing v7+ wrapper for Kofipod Pro.
 *
 * ## Family Sharing (spike result, 2026-05-05)
 *
 * The plan's original premise was that Play Billing v7 surfaces family-shared in-app
 * products via `queryPurchasesAsync` to family members on cold start, identical to the
 * purchaser's path. **The official Play documentation does not confirm this for one-time
 * in-app products.**
 *
 * What the docs actually say:
 * - The Play Help article "What can be shared with Family Library"
 *   (support.google.com/googleplay/answer/7007852) states explicitly: *"You can't share
 *   in-app purchases and apps downloaded at no charge with your family members."* The
 *   only documented path is: a paid app shared via Family Library re-grants previously
 *   bought IAPs *to the same purchaser* on a family member's device — not a family-wide
 *   IAP grant.
 * - The Play Billing v7 reference (developer.android.com/google/play/billing) does not
 *   document a family-sharing flag, a family-shareable product type, or a separate API
 *   surface for family-shared one-time products.
 *
 * Implication for Kofipod: the `kofipod_pro_family` SKU as currently planned is **not a
 * Play-platform-supported family-sharing mechanism**. The classification logic below
 * (`productId == FAMILY` → [ProSource.Family]) is forward-looking — it lets us promote a
 * second SKU as a "Family" tier (priced higher, named differently in the paywall), but
 * the actual sharing semantics will need to be enforced via app-side bookkeeping, an
 * external entitlement server, or a different distribution model.
 *
 * **TODO(family-sharing):** before promising "Family Sharing" in user-facing copy, verify
 * actual cold-start behavior on a real Family Sharing setup with the published SKU. If
 * Play does not propagate the purchase to family members through `queryPurchasesAsync`,
 * either drop the Family tier entirely (Pro-as-one-tier) or scope it to "this Google
 * account, multiple devices" rather than "one purchase, multiple Google accounts."
 *
 * The classification preference (Family > Individual when both tokens are present) is
 * still correct as-is for the case where a single user upgrades from Individual → Family.
 *
 * ## Connection lifecycle
 *
 * BillingClient is single-instance, scoped to the [Application] context. [connect] starts
 * the connection (idempotent); the listener is wired so `onPurchasesUpdated` callbacks
 * complete the suspending [launchPurchase] coroutine.
 *
 * ## v7.1.1 API notes
 *
 * - `queryProductDetailsAsync` callback in v7.1.1 returns `List<ProductDetails>` directly
 *   (same shape as v6). The newer `QueryProductDetailsResult` wrapper (with
 *   `productDetailsList` + `unfetchedProductList`) only landed in v8+ — on a major-version
 *   bump, swap the callback signature and use the unfetched list for diagnostics.
 * - `enablePendingPurchases(PendingPurchasesParams)` is the v7+ form; the older
 *   `setEnablePendingPurchases()` no-arg builder method is removed.
 */
class PlayBillingClientPort(
    private val app: Application,
    private val activityHolder: ActivityHolder,
) : BillingClientPort {
    // Single-flight purchase callback. Accessed on both the calling thread (launchPurchase)
    // and the Play Billing callback thread; @Volatile + null-out-before-resume is sufficient
    // because Play Billing serialises onPurchasesUpdated callbacks per BillingClient.
    @Volatile
    private var purchaseContinuation: Continuation<Result<ProEntitlement>>? = null

    private val client: BillingClient =
        BillingClient.newBuilder(app)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .setListener(
                PurchasesUpdatedListener { result, purchases ->
                    val cont = purchaseContinuation ?: return@PurchasesUpdatedListener
                    purchaseContinuation = null
                    when {
                        result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null -> {
                            cont.resume(Result.success(classifyPurchases(purchases)))
                        }
                        result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> {
                            cont.resume(Result.success(ProEntitlement.Free))
                        }
                        else -> {
                            cont.resume(
                                Result.failure(
                                    BillingException(result.responseCode, result.debugMessage),
                                ),
                            )
                        }
                    }
                },
            )
            .build()

    override suspend fun connect(): Result<Unit> {
        if (client.isReady) return Result.success(Unit)
        return suspendCancellableCoroutine { cont ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            cont.resume(Result.success(Unit))
                        } else {
                            cont.resume(
                                Result.failure(BillingException(result.responseCode, result.debugMessage)),
                            )
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        // Service disconnects are recoverable — the next operation will reconnect via
                        // isReady-gated connect(). Logged for diagnostics; no automatic retry here.
                        println("$LOG_TAG: billing service disconnected")
                    }
                },
            )
        }
    }

    override suspend fun queryEntitlement(): Result<ProEntitlement> = restorePurchases()

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> {
        val activity =
            activityHolder.current
                ?: return Result.failure(IllegalStateException("no foreground activity"))

        val productDetails = queryProductDetails(productId).getOrElse { return Result.failure(it) }

        val flowParams =
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build(),
                    ),
                )
                .build()

        return suspendCancellableCoroutine { cont ->
            purchaseContinuation = cont
            val result = client.launchBillingFlow(activity, flowParams)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseContinuation = null
                cont.resume(
                    Result.failure(BillingException(result.responseCode, result.debugMessage)),
                )
            }
        }
    }

    override suspend fun restorePurchases(): Result<ProEntitlement> {
        val params =
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(
                        Result.failure(BillingException(result.responseCode, result.debugMessage)),
                    )
                    return@queryPurchasesAsync
                }
                cont.resume(Result.success(classifyPurchases(purchases)))
            }
        }
    }

    override fun close() {
        client.endConnection()
    }

    private suspend fun queryProductDetails(productId: String): Result<ProductDetails> {
        val params =
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build(),
                    ),
                )
                .build()
        return suspendCancellableCoroutine { cont ->
            // v7.1.1: callback receives `List<ProductDetails>` directly. Later versions
            // (v8+) return a `QueryProductDetailsResult` wrapper with `productDetailsList`
            // and `unfetchedProductList`; on upgrade, switch to that shape and inspect
            // unfetched products for diagnostics.
            client.queryProductDetailsAsync(params) { result, productDetailsList ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(
                        Result.failure(BillingException(result.responseCode, result.debugMessage)),
                    )
                    return@queryProductDetailsAsync
                }
                val first = productDetailsList.firstOrNull()
                if (first == null) {
                    cont.resume(Result.failure(IllegalStateException("no product details for $productId")))
                } else {
                    cont.resume(Result.success(first))
                }
            }
        }
    }

    /**
     * Maps a list of [Purchase] tokens to a [ProEntitlement]. Family is preferred over
     * Individual when both tokens are present (the "upgraded from Individual to Family"
     * case) — Family is always at least as permissive. Pending purchases are ignored;
     * only [Purchase.PurchaseState.PURCHASED] counts.
     */
    private fun classifyPurchases(purchases: List<Purchase>): ProEntitlement {
        if (purchases.any {
                ProProducts.FAMILY in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
        ) {
            return ProEntitlement.Pro(ProSource.Family)
        }
        if (purchases.any {
                ProProducts.INDIVIDUAL in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
        ) {
            return ProEntitlement.Pro(ProSource.Individual)
        }
        return ProEntitlement.Free
    }
}

class BillingException(val responseCode: Int, val debugMessage: String?) :
    RuntimeException("billing error $responseCode: ${debugMessage ?: "no message"}")

private const val LOG_TAG = "Kofipod-Pro-Play"
