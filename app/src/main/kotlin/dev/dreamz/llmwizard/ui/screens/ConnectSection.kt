package dev.dreamz.llmwizard.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.dreamz.llmwizard.core.exposure.BindOption
import dev.dreamz.llmwizard.core.exposure.Exposure
import dev.dreamz.llmwizard.core.exposure.ExposureState
import dev.dreamz.llmwizard.core.exposure.SnippetBundle
import dev.dreamz.llmwizard.ui.MonoBlock
import dev.dreamz.llmwizard.ui.SectionCard

/** "Share with other apps" (PLAN.md 5.9): private by default, one network at a time, no surprises. */
@Composable
fun ConnectSection(state: ExposureState?, model: String?, onLoad: () -> Unit, onExpose: (BindOption) -> Unit, onPrivate: () -> Unit) {
    SectionCard("Share with other apps") {
        if (state == null) {
            Text("By default only this app can use the model, through SSH. You can let other devices on your network use it too, for example Open WebUI or Home Assistant.")
            OutlinedButton(onLoad) { Text("Show sharing options") }
            return@SectionCard
        }
        Text(if (state.isPrivate) "Private: only this app can use the model." else "Shared on ${state.bind}.", fontWeight = FontWeight.SemiBold)
        Exposure.warnings(state).forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.isPrivate) {
            Text("Choose a network to share on:")
            Exposure.options(state).forEach { o ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row { Text(o.label, Modifier.weight(1f), fontWeight = FontWeight.Medium); if (o.allowed) TextButton({ onExpose(o) }) { Text("Share") } }
                    Text(o.reason, style = MaterialTheme.typography.bodySmall, color = if (o.allowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
            if (state.tailscalePresent) Text("Tailscale is installed on this machine. Sharing over Tailscale (your devices, from anywhere) comes in a later version.", style = MaterialTheme.typography.bodySmall)
        } else {
            Button(onPrivate) { Text("Go back to private") }
            val urls = Exposure.urls(state)
            val ctx = LocalContext.current
            fun copy(t: String) = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("llmwizard", t))
            urls.firstOrNull()?.let { base ->
                Text("Address for other apps", style = MaterialTheme.typography.labelLarge)
                MonoBlock("OpenAI-compatible: $base/v1  (API key: any text)\nOllama native: $base")
                Row { TextButton({ copy("$base/v1") }) { Text("Copy /v1 URL") }; TextButton({ copy(base) }) { Text("Copy native URL") } }
                qr("$base/v1")?.let { Image(it, "QR code for $base/v1", Modifier.size(180.dp), filterQuality = FilterQuality.None) }
                var open by remember { mutableStateOf<String?>(null) }
                Text("Ready-made settings", style = MaterialTheme.typography.labelLarge)
                SnippetBundle.bundled().snippets.forEach { sn ->
                    TextButton({ open = if (open == sn.id) null else sn.id }) { Text(sn.title) }
                    if (open == sn.id) {
                        val text = sn.render(base, model ?: "your-model")
                        MonoBlock(text)
                        TextButton({ copy(text) }) { Text("Copy") }
                    }
                }
            }
        }
    }
}

private fun qr(text: String): ImageBitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
