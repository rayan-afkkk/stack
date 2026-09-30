package com.swipegallery.data.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.swipegallery.domain.billing.EntitlementState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.entitlementStore: DataStore<Preferences> by preferencesDataStore(name = "entitlement")

/**
 * Last entitlement confirmed by Google Play, so Premium keeps working offline. It is only
 * written from Play results (see BillingRepository); it is not a purchase record of its own.
 */
class EntitlementCache(context: Context) {
    private val store = context.applicationContext.entitlementStore

    private object Keys {
        val premium = booleanPreferencesKey("premium_confirmed")
        val pending = booleanPreferencesKey("purchase_pending")
        val verifiedAt = longPreferencesKey("last_verified_at")
    }

    val state: Flow<EntitlementState> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            EntitlementState(
                isPremium = p[Keys.premium] ?: false,
                hasPendingPurchase = p[Keys.pending] ?: false,
                lastVerifiedAtMillis = p[Keys.verifiedAt],
            )
        }

    suspend fun save(state: EntitlementState) {
        store.edit { p ->
            p[Keys.premium] = state.isPremium
            p[Keys.pending] = state.hasPendingPurchase
            val verified = state.lastVerifiedAtMillis
            if (verified != null) p[Keys.verifiedAt] = verified else p.remove(Keys.verifiedAt)
        }
    }
}
