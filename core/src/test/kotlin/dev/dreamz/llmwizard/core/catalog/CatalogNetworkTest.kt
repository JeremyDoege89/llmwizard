package dev.dreamz.llmwizard.core.catalog

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** Fails when a catalog tag no longer resolves on the registry (PLAN.md 5.5). Needs the internet. */
@EnabledIfSystemProperty(named = "llmwizard.network", matches = "true")
class CatalogNetworkTest {
    @Test fun `every catalog tag resolves on registry ollama ai`() = runBlocking {
        val bad = CatalogCheck().check(Catalog.bundled()).filterNot { it.resolved }
        assertTrue(bad.isEmpty(), "Unresolved: " + bad.joinToString { "${it.entry.ollamaTag} (${it.problem})" })
    }
}
