package dev.dreamz.llmwizard.core.catalog

import dev.dreamz.llmwizard.core.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderFilterTest {
    private val catalog = Catalog.bundled()
    private val r = Recommender(catalog)
    private val dl380p = Fixtures.profile("dl380p-hyperv-ubuntu2404")

    @Test fun `catalog entries are consistent`() {
        val known = setOf("chat", "coding", "reasoning", "research", "uncensored")
        catalog.models.forEach { e ->
            assertTrue(e.tags.isNotEmpty() && known.containsAll(e.tags), "${e.id} tags ${e.tags}")
            assertEquals(e.uncensored, "uncensored" in e.tags, "${e.id}: uncensored flag and tag disagree")
            assertTrue(e.license != null, "${e.id} has no licence")
            if (e.supportsTools) assertTrue(e.toolAccuracy == null || e.toolAccuracy!! >= 0.0)
        }
        assertEquals(catalog.models.size, catalog.models.map { it.ollamaTag }.toSet().size)
        assertTrue(catalog.models.count { it.uncensored } >= 10)
    }

    @Test fun `default filter never offers an uncensored model, so the plain setup flow is unchanged`() {
        val rec = r.recommend(dl380p)
        assertTrue(rec.candidates.none { it.entry.uncensored })
        listOfNotNull(rec.pick, rec.faster, rec.smarter).forEach { assertFalse(it.candidate.entry.uncensored) }
        val withoutUncensored = Recommender(catalog.copy(models = catalog.models.filterNot { it.uncensored })).recommend(dl380p)
        assertEquals(withoutUncensored.pick!!.candidate.entry.id, rec.pick!!.candidate.entry.id)
        assertEquals(withoutUncensored.faster?.candidate?.entry?.id, rec.faster?.candidate?.entry?.id)
        // Installed models are still looked up across the whole catalog.
        assertTrue(rec.allCandidates.any { it.entry.uncensored })
    }

    @Test fun `uncensored filter on the DL380p picks the abliterated MoE, not a dense 70B`() {
        val rec = r.recommend(dl380p, filter = ModelFilter.UNCENSORED)
        assertTrue(rec.candidates.all { it.entry.uncensored })
        val pick = rec.pick!!.candidate
        assertTrue(pick.entry.uncensored && pick.entry.isMoe, pick.entry.id)
        assertTrue(pick.estimate!!.speedClass.atLeast(SpeedClass.USABLE))
        val dense70 = rec.candidates.single { it.entry.id == "dolphin-llama3-70b" }
        assertTrue(dense70.fits)
        assertEquals(SpeedClass.PAINFUL, dense70.estimate!!.speedClass)
    }

    @Test fun `tag, quality, tools and size filters combine`() {
        val f = ModelFilter(tags = setOf("coding"), uncensored = null, minQuality = 4, supportsTools = true, maxDownloadBytes = 20_000_000_000)
        val hits = catalog.models.filter(f::matches)
        assertTrue(hits.isNotEmpty())
        hits.forEach { assertTrue("coding" in it.tags && it.quality >= 4 && it.supportsTools && it.downloadBytes <= 20_000_000_000, it.id) }
        assertTrue(catalog.models.filter(ModelFilter(query = "DOLPHIN", uncensored = null)::matches).all { "dolphin" in it.ollamaTag })
    }

    @Test fun `a filter that matches nothing says so instead of picking`() {
        val rec = r.recommend(dl380p, filter = ModelFilter(tags = setOf("no-such-tag")))
        assertNull(rec.pick)
        assertTrue(rec.notes.any { "filter" in it })
    }

    @Test fun `explanations are labelled estimates and carry the catch`() {
        val rec = r.recommend(dl380p, filter = ModelFilter.EVERYTHING)
        rec.allCandidates.forEach { assertTrue(it.explanation.startsWith(it.entry.name), it.entry.id) }
        val dolphin = rec.allCandidates.single { it.entry.id == "dolphin-mistral-7b" }.explanation
        assertTrue("(estimate)" in dolphin && "uncensored" in dolphin && "download" in dolphin, dolphin)
        val moe = rec.allCandidates.single { it.entry.id == "qwen3.6-35b-a3b" }.explanation
        assertTrue("mixture-of-experts" in moe && "measured" in moe, moe)
        assertTrue("commercial" in rec.allCandidates.single { it.entry.id == "command-r-35b" }.explanation)
        assertTrue("wait" in rec.allCandidates.single { it.entry.id == "dolphin-llama3-70b" }.explanation)
    }

    @Test fun `a model's own context window caps its KV estimate`() {
        val wv = catalog.byId("wizard-vicuna-uncensored-30b")!!
        assertEquals(r.kvBytes(wv, 2048), r.kvBytes(wv, 32768))
        val q = catalog.byId("qwen3.6-35b-a3b")!!
        assertTrue(r.kvBytes(q, 32768) > r.kvBytes(q, 8192))
    }
}
