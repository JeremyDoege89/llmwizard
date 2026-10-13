package dev.dreamz.llmwizard.core.catalog

import dev.dreamz.llmwizard.core.Fixtures
import dev.dreamz.llmwizard.core.scan.DiskInfo
import dev.dreamz.llmwizard.core.scan.Filesystem
import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.scan.MemoryInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderTest {
    private val catalog = Catalog.bundled()
    private val r = Recommender(catalog)

    private fun rec(fixture: String, intent: Intent = Intent.CHAT) = r.recommend(Fixtures.profile(fixture), intent)
    private fun HardwareProfile.withRamGb(gb: Long) = copy(memory = MemoryInfo(gb * 1_000_000_000, gb * 1_000_000_000 - 500_000_000))

    @Test fun `DL380p with 122 GB picks a large MoE, offers the 3B-active MoE as faster, flags dense 27B as slow`() {
        val rec = rec("dl380p-hyperv-ubuntu2404")
        val pick = rec.pick!!.candidate
        assertTrue(pick.entry.isMoe)
        assertEquals(Placement.CPU, pick.placement)
        assertTrue(pick.estimate!!.speedClass.atLeast(SpeedClass.USABLE))
        val faster = rec.faster!!.candidate.entry
        assertTrue(faster.isMoe && faster.activeParamsB!! in 3.0..4.0, faster.id)
        val dense27 = rec.candidates.single { it.entry.id == "qwen3.6-27b" }
        assertTrue(dense27.fits)
        assertTrue(dense27.estimate!!.speedClass in setOf(SpeedClass.SLOW, SpeedClass.PAINFUL))
        // Section 6 predicted 1-3 tok/s; measured 1.2 on wiz-ubuntu (2026-10-10).
        assertTrue(dense27.estimate!!.highTps * 1.2 >= 1.2 && dense27.estimate!!.lowTps <= 1.2, "measured 1.2 tok/s, got ${dense27.estimate}")
    }

    @Test fun `wiz-ubuntu shape (DL380p, 64 GB) matches PLAN section 6 - MoE with 3 to 4B active, 5 to 10ish tok-s`() {
        val p = Fixtures.profile("dl380p-hyperv-ubuntu2404").withRamGb(64)
        val pick = r.recommend(p).pick!!.candidate
        assertTrue(pick.entry.isMoe && pick.entry.activeParamsB!! in 3.0..4.0, pick.entry.id)
        assertTrue(pick.estimate!!.midTps in 5.0..15.0, pick.estimate.toString())
    }

    @Test fun `wiz-small gets a small model`() {
        val rec = rec("synthetic-wiz-small")
        assertTrue(rec.pick!!.candidate.entry.paramsB <= 4.5)
        assertEquals(false, rec.leastBad)
    }

    @Test fun `16 GB CPU-only laptop gets a small model on the CPU`() {
        val pick = rec("synthetic-laptop-16gb-cpu").pick!!.candidate
        assertEquals(Placement.CPU, pick.placement)
        assertTrue(pick.entry.paramsB <= 8)
    }

    @Test fun `8 GB NVIDIA card - small models go on the GPU and are offered as the faster choice`() {
        val rec = rec("synthetic-laptop-rtx4060")
        assertEquals(Placement.GPU, rec.faster!!.candidate.placement)
        assertEquals(SpeedClass.FAST, rec.faster!!.candidate.estimate!!.speedClass)
        assertTrue(rec.candidates.filter { it.placement == Placement.GPU }.all { it.memoryNeededBytes <= 8188L * 1024 * 1024 })
    }

    @Test fun `24 GB NVIDIA card runs the pick fully on the GPU at a fast speed`() {
        for (intent in listOf(Intent.CHAT, Intent.CODE)) {
            val pick = rec("synthetic-desktop-rtx3090", intent).pick!!.candidate
            assertEquals(Placement.GPU, pick.placement)
            assertEquals(SpeedClass.FAST, pick.estimate!!.speedClass)
            assertTrue(pick.entry.quality >= 4)
        }
        assertEquals("qwen3-coder-30b", rec("synthetic-desktop-rtx3090", Intent.CODE).pick!!.candidate.entry.id)
    }

    @Test fun `4 GB machine - says nothing is usable and offers the least bad tiny model`() {
        val rec = rec("synthetic-tiny-4gb")
        assertTrue(rec.leastBad)
        assertTrue(rec.pick!!.reason.startsWith("Nothing here reaches a usable speed"))
        assertTrue(rec.pick!!.candidate.entry.paramsB <= 1.0)
    }

    @Test fun `unknown memory gives no pick and says why, never treats it as zero`() {
        val p = Fixtures.profile("dl380p-hyperv-ubuntu2404").copy(memory = null)
        val rec = r.recommend(p)
        assertNull(rec.pick)
        assertTrue(rec.notes.any { "memory" in it })
    }

    @Test fun `too little disk is a fit failure with the numbers`() {
        val p = Fixtures.profile("dl380p-hyperv-ubuntu2404").copy(
            disk = DiskInfo(Filesystem("/dev/sda1", 40_000_000_000, 28_000_000_000, 12_000_000_000, "/")),
        )
        val rec = r.recommend(p)
        val big = rec.candidates.single { it.entry.id == "gpt-oss-120b" }
        assertTrue(!big.fits && "disk" in big.fitProblem!!)
        assertTrue(rec.pick!!.candidate.entry.downloadBytes < 12_000_000_000 - 6_500_000_000)
    }

    @Test fun `fits is not good - intent mismatch is said out loud`() {
        val rec = rec("synthetic-tiny-4gb", Intent.CODE)
        assertTrue(rec.notes.any { "not meant for" in it || "None of the models" in it })
    }

    @Test fun `assistant intent notes that tool accuracy is unmeasured`() {
        val rec = rec("dl380p-hyperv-ubuntu2404", Intent.ASSISTANT)
        assertTrue(rec.pick!!.candidate.entry.supportsTools)
        assertTrue(rec.notes.any { "has not been measured" in it })
    }

    @Test fun `every estimate is labeled as an estimate`() {
        val rec = rec("dl380p-hyperv-ubuntu2404")
        listOfNotNull(rec.pick, rec.faster, rec.smarter).forEach { assertTrue("(estimate)" in it.reason) }
    }

    @Test fun `catalog entries are sane`() {
        // BUILD_PLAN E1 grew the catalog past 40 (uncensored, coding, research entries).
        assertTrue(catalog.models.size in 15..60)
        assertTrue(catalog.models.count { it.isMoe } >= 2)
        catalog.models.forEach { e ->
            dev.dreamz.llmwizard.core.shell.Shell.modelTag(e.ollamaTag)
            assertTrue(e.downloadBytes > 100_000_000, e.id)
            assertTrue(e.quality in 1..5, e.id)
        }
        assertEquals(catalog.models.size, catalog.models.map { it.id }.toSet().size)
        assertNotNull(catalog.constants.classes.firstOrNull { it.minTps == 0.0 })
    }
}

/** The real Hyper-V VMs on the DL380p (scanned 2026-10-10), PLAN.md section 6. */
class HyperVFixturesTest {
    private val r = Recommender(Catalog.bundled())

    @Test fun `wiz-ubuntu (64 GB, 10 vCPU) picks the 3B-active MoE at 5 to 10ish tok-s`() {
        val pick = r.recommend(Fixtures.profile("hyperv-wiz-ubuntu")).pick!!.candidate
        assertTrue(pick.entry.isMoe && pick.entry.activeParamsB!! in 3.0..4.0, pick.entry.id)
        assertTrue(pick.estimate!!.midTps in 5.0..12.0, pick.estimate.toString())
    }

    @Test fun `wiz-small gets a small model`() {
        assertTrue(r.recommend(Fixtures.profile("hyperv-wiz-small")).pick!!.candidate.entry.paramsB <= 4.5)
    }

    @Test fun `wiz-compat is warned about compatibility mode, and the estimate brackets the measured speed`() {
        val p = Fixtures.profile("hyperv-wiz-compat")
        assertTrue(dev.dreamz.llmwizard.core.scan.Verdicts.of(p).any { it.code == "hyperv-compat" })
        // Measured 2026-10-10: qwen3:0.6b 6.3 tok/s here, 19.8 on the same VM with AVX visible.
        val e = r.recommend(p).candidates.single { it.entry.ollamaTag == "qwen3:0.6b" }.estimate!!
        assertTrue(6.3 in e.lowTps * 0.8..e.highTps * 1.2, e.toString())
        val twin = r.recommend(Fixtures.profile("hyperv-wiz-small")).candidates.single { it.entry.ollamaTag == "qwen3:0.6b" }.estimate!!
        assertTrue(19.8 in twin.lowTps * 0.8..twin.highTps * 1.2, twin.toString())
    }

    @Test fun `wiz-ubuntu estimate brackets the measured 1_2 tok-s of dense 27B`() {
        val e = r.recommend(Fixtures.profile("hyperv-wiz-ubuntu")).candidates.single { it.entry.ollamaTag == "qwen3.6:27b" }.estimate!!
        assertTrue(1.2 in e.lowTps * 0.8..e.highTps * 1.2, e.toString())
    }

    @Test fun `wiz-ubuntu estimate brackets the measured 7_1 tok-s of the picked MoE`() {
        val e = r.recommend(Fixtures.profile("hyperv-wiz-ubuntu")).pick!!.candidate.estimate!!
        assertTrue(7.1 in e.lowTps * 0.8..e.highTps * 1.2, e.toString())
    }
}
