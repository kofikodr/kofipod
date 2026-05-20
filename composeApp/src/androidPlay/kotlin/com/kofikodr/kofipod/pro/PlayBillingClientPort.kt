// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

import android.app.Application
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
import com.kofikodr.kofipod.ui.ActivityHolder
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
                            // Acknowledge any unacked PURCHASED token before resuming.
                            // Without this, Play auto-refunds the purchase ~3 days
                            // later and revokes Pro silently. classifyAndAcknowledge
                            // handles the callback chain.
                            classifyAndAcknowledge(purchases, cont)
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

    override suspend fun queryDisplayPrice(productId: String): Result<String?> =
        queryProductDetails(productId).map { details ->
            // One-time IAPs expose `oneTimePurchaseOfferDetails.formattedPrice`. Subscriptions
            // use a different field, but Kofipod Pro is a one-time SKU (see class kdoc), so
            // the subscription path is intentionally not handled here. Coerce blank to null
            // so a defensive empty string from Play falls back to the neutral UI copy
            // instead of rendering an empty price label.
            details.oneTimePurchaseOfferDetails?.formattedPrice?.takeIf { it.isNotBlank() }
        }

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
                // Same ack discipline as new purchases. A user who restores on
                // a new device must not have their entitlement silently
                // revoked because a previous install never acknowledged.
                classifyAndAcknowledge(purchases, cont)
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

    /**
     * Acknowledges every unacked PURCHASED [ProProducts.INDIVIDUAL] token, then
     * resumes [cont] with the classified entitlement. If any ack call fails we
     * surface the failure instead of caching Pro silently — Play allows a
     * ~3-day window so the next refresh will retry. The recursion is callback-
     * driven (Play Billing's `acknowledgePurchase` is callback-only) and runs
     * one ack at a time to keep the failure mode obvious.
     */
    private fun classifyAndAcknowledge(
        purchases: List<Purchase>,
        cont: Continuation<Result<ProEntitlement>>,
    ) {
        val candidates =
            purchases.map { p ->
                AckCandidate(
                    productIds = p.products.toList(),
                    isPurchased = p.purchaseState == Purchase.PurchaseState.PURCHASED,
                    isAcknowledged = p.isAcknowledged,
                    purchaseToken = p.purchaseToken,
                )
            }
        val pending = unacknowledgedIndividualTokens(candidates)
        if (pending.isEmpty()) {
            cont.resume(Result.success(classifyPurchases(purchases)))
            return
        }
        acknowledgeNext(pending, 0, purchases, cont)
    }

    // Callback-driven recursion: each acknowledgePurchase callback re-enters
    // this function on the Play Billing thread. We have a single one-time
    // SKU (ProProducts.INDIVIDUAL), so the queue length is realistically 0
    // or 1 — recursion depth never exceeds 1. If a second SKU is ever added,
    // depth grows linearly; convert to an iterator-based callback chain if
    // many tokens are expected.
    private fun acknowledgeNext(
        queue: List<String>,
        index: Int,
        purchases: List<Purchase>,
        cont: Continuation<Result<ProEntitlement>>,
    ) {
        if (index >= queue.size) {
            cont.resume(Result.success(classifyPurchases(purchases)))
            return
        }
        val params =
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(queue[index])
                .build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                cont.resume(
                    Result.failure(BillingException(result.responseCode, result.debugMessage)),
                )
                return@acknowledgePurchase
            }
            acknowledgeNext(queue, index + 1, purchases, cont)
        }
    }
}

class BillingException(val responseCode: Int, val debugMessage: String?) :
    RuntimeException("billing error $responseCode: ${debugMessage ?: "no message"}")

private const val LOG_TAG = "Kofipod-Pro-Play"
