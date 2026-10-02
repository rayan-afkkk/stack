package com.swipegallery.ui.paywall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
import com.swipegallery.domain.billing.PlanOption
import com.swipegallery.domain.billing.PlanPeriod
import com.swipegallery.domain.billing.PlanSelector
import com.swipegallery.domain.billing.SubscriptionPlans
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
import com.swipegallery.ui.theme.Radii
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.findActivity
import com.swipegallery.util.openUrl
import com.swipegallery.util.rememberReducedMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Period
import java.util.Currency

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

    val manageUrl: String get() = container.billing.manageSubscriptionUrl

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

    fun subscribe(activity: android.app.Activity, plan: PlanOption) {
        status.value = PaywallStatus.None
        container.billing.launchPurchase(activity, plan)
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
    val reducedMotion = rememberReducedMotion()
    val plans = (state.product as? ProductState.Available)?.plans
    var selectedPeriod by rememberSaveable { mutableStateOf<PlanPeriod?>(null) }
    val selected: PlanOption? = plans?.let { p ->
        when (selectedPeriod) {
            PlanPeriod.MONTHLY -> p.monthly
            PlanPeriod.YEARLY -> p.yearly
            null -> null
        } ?: p.preferred
    }

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
                Spacer(Modifier.height(Space.s))
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
                    Benefit(stringResource(R.string.paywall_benefit_cancel))
                    Spacer(Modifier.height(Space.l))
                    Divider()
                    Spacer(Modifier.height(Space.l))
                    Text(stringResource(R.string.paywall_free_note), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                }
                if (!state.isPremium && plans != null && selected != null) {
                    Spacer(Modifier.height(Space.xl))
                    PlanPicker(
                        plans = plans,
                        selected = selected.period,
                        reducedMotion = reducedMotion,
                        onSelect = { selectedPeriod = it },
                    )
                }
                Spacer(Modifier.height(Space.l))
            }
            Column(
                Modifier.padding(horizontal = Space.gutter, vertical = Space.m),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val product = state.product
                when {
                    state.isPremium -> {
                        Text(stringResource(R.string.paywall_active), style = SwipeTheme.type.title, color = c.textPrimary)
                        Spacer(Modifier.height(Space.m))
                        PrimaryButton(stringResource(R.string.action_continue), onClose, Modifier.fillMaxWidth())
                        QuietButton(stringResource(R.string.paywall_manage), { context.openUrl(vm.manageUrl) })
                    }

                    product is ProductState.Available && selected != null -> {
                        AnimatedContent(
                            targetState = selected,
                            transitionSpec = {
                                val ms = if (reducedMotion) 0 else 160
                                fadeIn(tween(ms)) togetherWith fadeOut(tween(ms))
                            },
                            label = "cta",
                        ) { plan ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PrimaryButton(
                                    text = stringResource(if (plan.freeTrial != null) R.string.paywall_start_trial else R.string.paywall_subscribe),
                                    onClick = { context.findActivity()?.let { vm.subscribe(it, plan) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.hasPending,
                                )
                                Spacer(Modifier.height(Space.s))
                                Text(
                                    disclosure(plan),
                                    style = SwipeTheme.type.bodySmall,
                                    color = c.textSecondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
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
                            textAlign = TextAlign.Center,
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
                    Text(stringResource(statusText), style = SwipeTheme.type.bodySmall, color = c.textPrimary, textAlign = TextAlign.Center)
                }
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
private fun PlanPicker(
    plans: SubscriptionPlans,
    selected: PlanPeriod,
    reducedMotion: Boolean,
    onSelect: (PlanPeriod) -> Unit,
) {
    val savings = PlanSelector.yearlySavingsPercent(plans)
    val perMonth = PlanSelector.yearlyPerMonthMicros(plans)?.let { micros ->
        plans.yearly?.recurring?.currencyCode?.let { formatMicros(micros, it) }
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        plans.yearly?.let { plan ->
            PlanCard(
                plan = plan,
                title = stringResource(R.string.plan_yearly),
                price = stringResource(R.string.plan_price_year, plan.recurring.formattedPrice),
                detail = perMonth?.let { stringResource(R.string.plan_per_month, it) },
                badge = savings?.let { stringResource(R.string.plan_save, it) },
                selected = selected == PlanPeriod.YEARLY,
                reducedMotion = reducedMotion,
                onClick = { onSelect(PlanPeriod.YEARLY) },
            )
        }
        plans.monthly?.let { plan ->
            PlanCard(
                plan = plan,
                title = stringResource(R.string.plan_monthly),
                price = stringResource(R.string.plan_price_month, plan.recurring.formattedPrice),
                detail = null,
                badge = null,
                selected = selected == PlanPeriod.MONTHLY,
                reducedMotion = reducedMotion,
                onClick = { onSelect(PlanPeriod.MONTHLY) },
            )
        }
    }
}

@Composable
private fun PlanCard(
    plan: PlanOption,
    title: String,
    price: String,
    detail: String?,
    badge: String?,
    selected: Boolean,
    reducedMotion: Boolean,
    onClick: () -> Unit,
) {
    val c = SwipeTheme.colors
    val ms = if (reducedMotion) 0 else 180
    val borderColor by animateColorAsState(if (selected) c.textPrimary else c.border, tween(ms, easing = FastOutSlowInEasing), label = "planBorder")
    val background by animateColorAsState(if (selected) c.surface else c.surfaceSecondary, tween(ms, easing = FastOutSlowInEasing), label = "planBg")
    val borderWidth by animateDpAsState(if (selected) 1.5.dp else 1.dp, tween(ms), label = "planBorderWidth")
    val shape = RoundedCornerShape(Radii.card)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(background)
            .border(borderWidth, borderColor, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Space.l + Space.xs, vertical = Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        RadioDot(selected, borderColor)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(title, style = SwipeTheme.type.title, color = c.textPrimary)
                if (badge != null) {
                    Box(
                        Modifier.clip(CircleShape).background(c.keep).padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(badge, style = SwipeTheme.type.label, color = c.onPastel)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(price, style = SwipeTheme.type.body, color = c.textPrimary)
            val trial = plan.freeTrial?.let { trialLabel(it) }
            val sub = listOfNotNull(trial, detail).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = SwipeTheme.type.bodySmall, color = c.textSecondary)
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean, color: androidx.compose.ui.graphics.Color) {
    val c = SwipeTheme.colors
    Box(
        Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(c.textPrimary))
    }
}

@Composable
private fun trialLabel(period: Period): String = when {
    period.toTotalMonths() > 0 && period.days == 0 ->
        pluralStringResource(R.plurals.plan_trial_months, period.toTotalMonths().toInt(), period.toTotalMonths().toInt())
    else -> {
        val days = period.days + (period.toTotalMonths() * 30).toInt()
        pluralStringResource(R.plurals.plan_trial_days, days, days)
    }
}

/** Play-required clarity: price, period, auto-renewal and how to cancel. */
@Composable
private fun disclosure(plan: PlanOption): String {
    val price = plan.recurring.formattedPrice
    val yearly = plan.period == PlanPeriod.YEARLY
    val trial = plan.freeTrial
    return if (trial != null) {
        stringResource(
            if (yearly) R.string.paywall_disclosure_trial_year else R.string.paywall_disclosure_trial_month,
            trialLabel(trial),
            price,
        )
    } else {
        stringResource(if (yearly) R.string.paywall_disclosure_year else R.string.paywall_disclosure_month, price)
    }
}

private fun formatMicros(micros: Long, currencyCode: String): String? = runCatching {
    NumberFormat.getCurrencyInstance().apply { currency = Currency.getInstance(currencyCode) }
        .format(micros / 1_000_000.0)
}.getOrNull()

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
