// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.pro.ProProducts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PaywallUiState(
    val mode: PaywallMode = PaywallMode.Idle,
    val entitlement: ProEntitlement = ProEntitlement.Unknown,
    val errorMessage: String? = null,
    /**
     * Platform-formatted display price for the individual SKU (e.g. `"$12.99"`,
     * `"€10.99"`). `null` means "the billing layer didn't return a price" — UI
     * substitutes neutral fallback copy. Never carries a hard-coded amount.
     */
    val displayPrice: String? = null,
)

enum class PaywallMode {
    Idle,
    Launching,
    Restoring,
}

class PaywallViewModel(
    private val repo: ProEntitlementRepository,
    private val router: PaywallRouter,
) : ViewModel() {
    private val mode = MutableStateFlow(PaywallMode.Idle)
    private val error = MutableStateFlow<String?>(null)
    private val displayPrice = MutableStateFlow<String?>(null)

    val state: StateFlow<PaywallUiState> =
        combine(mode, error, displayPrice, repo.state) { m, err, price, entitlement ->
            PaywallUiState(mode = m, entitlement = entitlement, errorMessage = err, displayPrice = price)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaywallUiState())

    init {
        // Fetch the formatted price once at construction. Failures are swallowed —
        // a missing price is not an error state for the paywall; we just fall back
        // to neutral copy. The real price is also shown inside Play's purchase
        // sheet, which opens on tap.
        viewModelScope.launch {
            displayPrice.value = repo.fetchDisplayPrice(ProProducts.INDIVIDUAL)
        }
    }

    fun purchaseIndividual() = launchPurchase(ProProducts.INDIVIDUAL)

    fun restore() {
        viewModelScope.launch {
            mode.value = PaywallMode.Restoring
            error.value = null
            val result = repo.restorePurchases()
            mode.value = PaywallMode.Idle
            result.onSuccess { ent ->
                if (ent is ProEntitlement.Pro) router.dismiss()
            }.onFailure { error.value = it.message ?: "Restore failed" }
        }
    }

    fun dismiss() = router.dismiss()

    private fun launchPurchase(productId: String) {
        viewModelScope.launch {
            mode.value = PaywallMode.Launching
            error.value = null
            val result = repo.launchPurchase(productId)
            mode.value = PaywallMode.Idle
            result.onSuccess { ent ->
                if (ent is ProEntitlement.Pro) router.dismiss()
            }.onFailure { error.value = it.message ?: "Purchase failed" }
        }
    }
}
