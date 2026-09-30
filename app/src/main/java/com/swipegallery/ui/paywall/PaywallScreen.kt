package com.swipegallery.ui.paywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swipegallery.AppContainer
import com.swipegallery.BuildConfig
import com.swipegallery.R
import com.swipegallery.data.billing.ProductState
import com.swipegallery.data.billing.PurchaseEvent
import com.swipegallery.data.billing.RestoreOutcome
import com.swipegallery.domain.billing.BillingError
import com.swipegallery.ui.components.Divider
import com.swipegallery.ui.components.PrimaryButton
import com.swipegallery.ui.components.QuietButton
import com.swipegallery.ui.components.Screen
import com.swipegallery.ui.components.SwipeCard
import com.swipegallery.ui.components.bottomSafeArea
import com.swipegallery.ui.components.readableWidth
import com.swipegallery.ui.components.topSafeArea
import com.swipegallery.ui.navigation.PaywallSource
import com.swipegallery.ui.navigation.containerViewModel
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.findActivity
import com.swipegallery.util.openUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Honest, specific status line under the purchase button. */
sealed interface PaywallStatus {
    data object None : PaywallStatus
    data object Pending : PaywallStatus
    data object Cancelled : PaywallStatus
    data object Restoring : PaywallStatus
    data object Restored : PaywallStatus
    data object NothingToRestore : PaywallStatus
    data class Error(val error: BillingError) : PaywallStatus
}

data class PaywallUiState(
    val isPremium: Boolean = false,
    val product: ProductState = ProductState.Loading,
    val hasPending: Boolean = false,
    val status: PaywallStatus = PaywallStatus.None,
)

class PaywallViewModel(private val container: AppContainer) : ViewModel() {
    private val status = MutableStateFlow<PaywallStatus>(PaywallStatus.None)

    val state: StateFlow<PaywallUiState> = combine(
        container.premium,
        container.billing.product,
        container.billing.entitlement,
        status,
    ) { premium, product, entitlement, status ->
        PaywallUiState(
            isPremium = premium == true,
            product = product,
            hasPending = entitlement?.hasPendingPurchase == true,
            status = status,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaywallUiState())

    init {
        viewModelScope.launch { container.billing.loadProduct() }
        viewModelScope.launch {
            container.billing.events.collect { event ->
                status.value = when (event) {
                    PurchaseEvent.Purchased -> PaywallStatus.None
                    PurchaseEvent.Pending -> PaywallStatus.Pending
                    PurchaseEvent.Cancelled -> PaywallStatus.Cancelled
                    is PurchaseEvent.Failed -> PaywallStatus.Error(event.error)
                }
            }
        }
    }

    fun purchase(activity: android.app.Activity) {
        status.update { PaywallStatus.None }
        container.billing.launchPurchase(activity)
    }

    fun retryProduct() {
        viewModelScope.launch { container.billing.loadProduct() }
    }

    fun restore() {
        if (status.value == PaywallStatus.Restoring) return
        status.value = PaywallStatus.Restoring
        viewModelScope.launch {
            status.value = when (val outcome = container.billing.restore()) {
                RestoreOutcome.Restored -> PaywallStatus.Restored
                RestoreOutcome.NothingToRestore -> PaywallStatus.NothingToRestore
                RestoreOutcome.StillPending -> PaywallStatus.Pending
                is RestoreOutcome.Failed -> PaywallStatus.Error(outcome.error)
            }
        }
    }
}

@Composable
fun PaywallScreen(container: AppContainer, source: PaywallSource, onClose: () -> Unit) {
    val vm = containerViewModel { c, _ -> PaywallViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = SwipeTheme.colors

    Screen {
        Column(Modifier.fillMaxSize().topSafeArea().bottomSafeArea().readableWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close), tint = c.textPrimary)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter),
            ) {
                Spacer(Modifier.height(Space.l))
                Text(
                    stringResource(
                        when (source) {
                            PaywallSource.LIMIT -> R.string.paywall_intro_limit
                            PaywallSource.PRO_FEATURE -> R.string.paywall_intro_pro
                            PaywallSource.UPGRADE_CARD, PaywallSource.SETTINGS -> R.string.paywall_intro_default
                        },
                    ),
                    style = SwipeTheme.type.label,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(Space.m))
                Text(stringResource(R.string.paywall_title), style = SwipeTheme.type.display, color = c.textPrimary)
                Spacer(Modifier.height(Space.xl))
                SwipeCard(Modifier.fillMaxWidth()) {
                    Benefit(stringResource(R.string.paywall_benefit_unlimited))
                    Spacer(Modifier.height(Space.m))
                    Benefit(stringResource(R.string.paywall_benefit_large))
                    Spacer(Modifier.height(Space.m))
                    Benefit(stringResource(R.string.paywall_benefit_once))
                    Spacer(Modifier.height(Space.l))
                    Divider()
                    Spacer(Modifier.height(Space.l))
                    Text(stringResource(R.string.paywall_free_note), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                }
                Spacer(Modifier.height(Space.xl))
            }
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
                val product = state.product
                when {
                    state.isPremium -> {
                        Text(stringResource(R.string.paywall_active), style = SwipeTheme.type.title, color = c.textPrimary)
                        Spacer(Modifier.height(Space.m))
                        PrimaryButton(stringResource(R.string.action_continue), onClose, Modifier.fillMaxWidth())
                    }

                    product is ProductState.Available -> {
                        Text(
                            stringResource(R.string.paywall_price_line, product.formattedPrice),
                            style = SwipeTheme.type.body,
                            color = c.textPrimary,
                        )
                        Spacer(Modifier.height(Space.m))
                        PrimaryButton(
                            stringResource(R.string.paywall_buy, product.formattedPrice),
                            onClick = { context.findActivity()?.let(vm::purchase) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.hasPending,
                        )
                    }

                    product is ProductState.Loading -> PrimaryButton(
                        stringResource(R.string.paywall_loading_price),
                        onClick = {},
                        enabled = false,
                        loading = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    product is ProductState.Unavailable -> {
                        Text(
                            stringResource(billingErrorText(product.error, forProduct = true)),
                            style = SwipeTheme.type.bodySmall,
                            color = c.textSecondary,
                        )
                        Spacer(Modifier.height(Space.m))
                        PrimaryButton(stringResource(R.string.action_try_again), vm::retryProduct, Modifier.fillMaxWidth())
                    }
                }
                val statusText = when (val s = state.status) {
                    PaywallStatus.None -> if (state.hasPending && !state.isPremium) R.string.paywall_pending else null
                    PaywallStatus.Pending -> R.string.paywall_pending
                    PaywallStatus.Cancelled -> R.string.paywall_cancelled
                    PaywallStatus.Restoring -> R.string.paywall_restoring
                    PaywallStatus.Restored -> R.string.paywall_restored
                    PaywallStatus.NothingToRestore -> R.string.paywall_nothing_to_restore
                    is PaywallStatus.Error -> billingErrorText(s.error, forProduct = false)
                }
                if (statusText != null) {
                    Spacer(Modifier.height(Space.s))
                    Text(stringResource(statusText), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                }
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (!state.isPremium) QuietButton(stringResource(R.string.paywall_not_now), onClose)
                    QuietButton(stringResource(R.string.paywall_restore), vm::restore)
                }
                val terms = BuildConfig.TERMS_URL
                val privacy = BuildConfig.PRIVACY_URL
                if (terms.isNotBlank() || privacy.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.Center) {
                        if (terms.isNotBlank()) QuietButton(stringResource(R.string.link_terms), { context.openUrl(terms) })
                        if (privacy.isNotBlank()) QuietButton(stringResource(R.string.link_privacy), { context.openUrl(privacy) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Benefit(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.m), modifier = Modifier.heightIn(min = 24.dp)) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = SwipeTheme.colors.keep, modifier = Modifier.size(22.dp))
        Text(text, style = SwipeTheme.type.body, color = SwipeTheme.colors.textPrimary)
    }
}

private fun billingErrorText(error: BillingError, forProduct: Boolean): Int = when (error) {
    BillingError.NETWORK, BillingError.SERVICE_UNAVAILABLE -> R.string.billing_offline
    BillingError.BILLING_UNAVAILABLE -> R.string.billing_unavailable
    BillingError.ITEM_UNAVAILABLE -> R.string.billing_product_unavailable
    BillingError.ITEM_ALREADY_OWNED -> R.string.billing_already_owned
    BillingError.USER_CANCELED -> R.string.paywall_cancelled
    BillingError.DEVELOPER_ERROR, BillingError.UNKNOWN ->
        if (forProduct) R.string.billing_product_unavailable else R.string.billing_error
}
