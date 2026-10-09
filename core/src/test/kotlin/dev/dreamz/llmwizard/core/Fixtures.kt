package dev.dreamz.llmwizard.core

import dev.dreamz.llmwizard.core.scan.HardwareProfile
import dev.dreamz.llmwizard.core.scan.ScanParser
import java.io.File

object Fixtures {
    val root = File(System.getProperty("llmwizard.root") ?: "..")
    fun text(name: String) = File(root, "fixtures/$name.txt").readText()
    fun profile(name: String): HardwareProfile = ScanParser.parse(text(name))
    fun all(): List<String> = File(root, "fixtures").listFiles()!!.filter { it.name.endsWith(".txt") }.map { it.nameWithoutExtension }.sorted()
}
