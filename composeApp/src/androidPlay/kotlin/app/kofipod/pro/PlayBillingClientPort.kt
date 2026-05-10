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
 * Google Play Billing v8 wrapper for Kofipod Pro.
 *
 * Single one-time SKU: [ProProducts.INDIVIDUAL] = `kofipod_pro`. The Family tier was
 * dropped before any release: Play's documented Family Library mechanism explicitly
 * does not share one-time IAPs across accounts (see Play Help article 7007852), and
 * Play Billing has no API for family-shared one-time products. If a "Family" tier is
 * ever revived it will need to be enforced via app-side seat licensing, not Play.
 *
 * ## Connection lifecycle
 *
 * BillingClient is single-instance, scoped to the [Application] context. [connect] starts
 * the connection (idempotent); the listener is wired so `onPurchasesUpdated` callbacks
 * complete the suspending [launchPurchase] coroutine.
 *
 * ## v8 API notes
 *
 * - `queryProductDetailsAsync` callback in v8 receives a [QueryProductDetailsResult]
 *   wrapper exposing `productDetailsList` + `unfetchedProductList`. The unfetched list
 *   is logged for diagnostics; we still surface "no product details" as a failure.
 * - `enablePendingPurchases(PendingPurchasesParams)` (with
 *   `enableOneTimeProducts()`) remains the required form.
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
            client.queryProductDetailsAsync(params) { result, productDetailsResult ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(
                        Result.failure(BillingException(result.responseCode, result.debugMessage)),
                    )
                    return@queryProductDetailsAsync
                }
                val unfetched = productDetailsResult.unfetchedProductList
                if (unfetched.isNotEmpty()) {
                    println("$LOG_TAG: unfetched products: ${unfetched.map { it.productId }}")
                }
                val first = productDetailsResult.productDetailsList.firstOrNull()
                if (first == null) {
                    cont.resume(Result.failure(IllegalStateException("no product details for $productId")))
                } else {
                    cont.resume(Result.success(first))
                }
            }
        }
    }

    /**
     * Maps a list of [Purchase] tokens to a [ProEntitlement]. Pending purchases are
     * ignored; only [Purchase.PurchaseState.PURCHASED] counts.
     */
    private fun classifyPurchases(purchases: List<Purchase>): ProEntitlement {
        val hasIndividual =
            purchases.any {
                ProProducts.INDIVIDUAL in it.products &&
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
        return if (hasIndividual) ProEntitlement.Pro(ProSource.Individual) else ProEntitlement.Free
    }
}

class BillingException(val responseCode: Int, val debugMessage: String?) :
    RuntimeException("billing error $responseCode: ${debugMessage ?: "no message"}")

private const val LOG_TAG = "Kofipod-Pro-Play"
