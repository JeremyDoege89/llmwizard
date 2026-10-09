package dev.dreamz.llmwizard.core.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Upkeep for the catalog (PLAN.md 5.5): resolves every `ollamaTag` against the Ollama registry and
 * reads the real download size from its manifest. Runs on the developer's machine through the
 * CLI; the phone never calls the registry.
 */
class CatalogCheck(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
    private val registry: String = "https://registry.ollama.ai",
) {
    data class Row(val entry: ModelEntry, val resolved: Boolean, val bytes: Long?, val problem: String?)

    suspend fun check(catalog: Catalog): List<Row> = catalog.models.map { check(it) }

    suspend fun check(e: ModelEntry): Row = withContext(Dispatchers.IO) {
        val (name, tag) = e.ollamaTag.split(':', limit = 2).let { it[0] to (it.getOrNull(1) ?: "latest") }
        val path = if ('/' in name) name else "library/$name"
        val req = Request.Builder().url("$registry/v2/$path/manifests/$tag")
            .header("Accept", "application/vnd.docker.distribution.manifest.v2+json").build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use Row(e, false, null, "HTTP ${resp.code}")
                val m = Json.parseToJsonElement(resp.body.string()).jsonObject
                val bytes = m["layers"]!!.jsonArray.sumOf { it.jsonObject["size"]!!.jsonPrimitive.long }
                Row(e, true, bytes, if (bytes != e.downloadBytes) "size changed: catalog ${e.downloadBytes}, registry $bytes" else null)
            }
        }.getOrElse { Row(e, false, null, it.message) }
    }

    /** The catalog with [rows]' registry sizes written in. */
    fun updated(catalog: Catalog, rows: List<Row>): Catalog =
        catalog.copy(models = catalog.models.map { m -> rows.firstOrNull { it.entry.id == m.id }?.bytes?.let { m.copy(downloadBytes = it) } ?: m })
}
