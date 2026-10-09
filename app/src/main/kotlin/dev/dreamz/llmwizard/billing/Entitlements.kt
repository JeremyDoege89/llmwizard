package dev.dreamz.llmwizard.billing

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.dreamz.llmwizard.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Whether Pro is unlocked (PLAN.md Phase 5, question 3). One interface, so features can move
 * between tiers and the direct build's license key (Phase 9) can plug in instead of Play Billing.
 */
interface Entitlements {
    val pro: Flow<Boolean>
    suspend fun setOwned(owned: Boolean)
    /** Debug builds only: pretend Pro is owned, to test gated screens without a Play track. */
    suspend fun setDebugOverride(on: Boolean)
}

/** What Pro adds in release 1. Kept in one place so the paywall and the gates agree. */
object ProFeatures {
    const val FREE_SERVERS = 1
    val list = listOf(
        "More than one server",
        "Sharing over Tailscale (coming)",
        "Advanced Ollama tuning (coming)",
    )
}

private val Context.entitlementStore by preferencesDataStore("entitlements")

/** The cached answer survives offline; Play Billing refreshes it whenever it can. */
class CachedEntitlements(private val context: Context) : Entitlements {
    private val owned = booleanPreferencesKey("pro_owned")
    private val debug = booleanPreferencesKey("pro_debug_override")

    override val pro: Flow<Boolean> = context.entitlementStore.data.map { it[owned] == true }
        .combine(context.entitlementStore.data.map { BuildConfig.DEBUG && it[debug] == true }) { a, b -> a || b }

    override suspend fun setOwned(owned: Boolean) { context.entitlementStore.edit { it[this.owned] = owned } }
    override suspend fun setDebugOverride(on: Boolean) { if (BuildConfig.DEBUG) context.entitlementStore.edit { it[debug] = on } }
}
