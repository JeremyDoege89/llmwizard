package dev.dreamz.llmwizard.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.BuildConfig
import dev.dreamz.llmwizard.billing.PlayBilling
import dev.dreamz.llmwizard.billing.ProFeatures
import dev.dreamz.llmwizard.ui.SectionCard
import dev.dreamz.llmwizard.ui.UpgradeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeRoute(vm: UpgradeViewModel, onBack: () -> Unit) {
    val pro by vm.pro.collectAsState()
    val price by vm.billing.price.collectAsState()
    val state by vm.billing.state.collectAsState()
    val activity = LocalContext.current as? Activity
    Scaffold(topBar = { TopAppBar(title = { Text("llmwizard Pro") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(if (pro) "Pro is unlocked. Thank you." else "One payment, yours to keep") {
                ProFeatures.list.forEach { Text("• $it") }
                Text("No subscription, no ads, nothing that tracks you. The free version keeps one server, chat and everything for installing.", style = MaterialTheme.typography.bodySmall)
            }
            if (!pro) {
                Button({ activity?.let { vm.billing.buy(it) } }, Modifier.fillMaxWidth()) { Text(price?.let { "Unlock Pro for $it" } ?: "Unlock Pro") }
                TextButton({ vm.billing.refresh() }) { Text("Restore a purchase") }
            }
            when (val s = state) {
                is PlayBilling.State.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                PlayBilling.State.Pending -> Text("The payment is pending. Pro unlocks when Google Play confirms it.")
                PlayBilling.State.Canceled -> Text("Cancelled. Nothing was charged.")
                else -> {}
            }
            if (BuildConfig.DEBUG) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Debug build: pretend Pro is owned", Modifier.weight(1f))
                Switch(pro, { vm.setDebugOverride(it) })
            }
        }
    }
}
