// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.ProEntitlement
import app.kofipod.pro.ProEntitlementRepository
import app.kofipod.pro.ProProducts
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

    val state: StateFlow<PaywallUiState> =
        combine(mode, error, repo.state) { m, err, entitlement ->
            PaywallUiState(mode = m, entitlement = entitlement, errorMessage = err)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaywallUiState())

    fun purchaseIndividual() = launchPurchase(ProProducts.INDIVIDUAL)

    fun purchaseFamily() = launchPurchase(ProProducts.FAMILY)

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
