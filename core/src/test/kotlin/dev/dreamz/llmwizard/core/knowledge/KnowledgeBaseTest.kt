package dev.dreamz.llmwizard.core.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KnowledgeBaseTest {
    private val kb = KnowledgeBase.bundled()

    @Test fun `bundled articles load, ids are unique and related links resolve`() {
        assertTrue(kb.articles.size >= 4)
        assertEquals(kb.articles.size, kb.articles.map { it.id }.toSet().size)
        kb.articles.forEach { a ->
            assertTrue(a.markdown.length > 400, a.id)
            assertEquals(a.relatedArticles.size, kb.related(a).size, "${a.id} links to a missing article")
        }
    }

    @Test fun `search ranks title hits first and needs every word`() {
        assertEquals("dense-vs-moe", kb.search("mixture experts").first().id)
        assertEquals("uncensored-models", kb.search("abliterated").first().id)
        assertTrue(kb.search("abliterated zebra").isEmpty())
        assertEquals(kb.articles.size, kb.search("  ").size)
    }

    @Test fun `contextual help skips articles not written yet`() {
        val help = ContextualHelp.forScreen("recommend", kb)
        assertEquals(listOf("dense-vs-moe", "memory-bandwidth", "uncensored-models"), help.map { it.id })
        assertTrue(ContextualHelp.forScreen("no-such-screen", kb).isEmpty())
    }

    @Test fun `parser reads the header and refuses a broken one`() {
        val a = KnowledgeBase.parse("---\nid: x\ntitle: T: with colon\ncategory: MODELS\ndifficulty: ADVANCED\ntags: a, b\nsummary: S\n---\n" + "word ".repeat(450))
        assertEquals("T: with colon", a.title)
        assertEquals(setOf("a", "b"), a.tags)
        assertEquals(3, a.readMinutes)
        assertThrows<IllegalArgumentException> { KnowledgeBase.parse("no header") }
        assertThrows<IllegalArgumentException> { KnowledgeBase.parse("---\nid: x\n---\nbody") }
    }
}
