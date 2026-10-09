package dev.dreamz.llmwizard.cli

import dev.dreamz.llmwizard.core.ollama.BenchmarkResult
import dev.dreamz.llmwizard.core.recipe.JobRef
import dev.dreamz.llmwizard.core.recipe.ResumeStore
import dev.dreamz.llmwizard.core.recipe.Transcript
import dev.dreamz.llmwizard.core.recipe.TranscriptEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** The CLI's stand-ins for the app's Room tables. */
class FileResumeStore(private val file: File) : ResumeStore {
    private val ser = MapSerializer(String.serializer(), JobRef.serializer())
    private fun load(): MutableMap<String, JobRef> =
        if (file.isFile) Json.decodeFromString(ser, file.readText()).toMutableMap() else mutableMapOf()
    private fun save(m: Map<String, JobRef>) { file.parentFile.mkdirs(); file.writeText(Json.encodeToString(ser, m)) }
    private fun key(t: String, r: String, s: String) = "$t/$r/$s"

    @Synchronized override fun get(target: String, recipeId: String, stepId: String) = load()[key(target, recipeId, stepId)]
    @Synchronized override fun put(target: String, recipeId: String, stepId: String, ref: JobRef) = save(load().apply { put(key(target, recipeId, stepId), ref) })
    @Synchronized override fun clear(target: String, recipeId: String, stepId: String) = save(load().apply { remove(key(target, recipeId, stepId)) })
}

/** Append-only JSON lines. Holds scripts and exit codes, never anything sent on stdin. */
class FileTranscript(private val file: File) : Transcript {
    override fun record(entry: TranscriptEntry) {
        file.parentFile.mkdirs()
        file.appendText(Json.encodeToString(TranscriptEntry.serializer(), entry) + "\n")
    }
}

@Serializable
data class BenchRecord(val target: String, val result: BenchmarkResult, val estimate: String?)

/** Pairs of estimate and measurement: how the speed constants get calibrated (PLAN.md 5.5). */
class BenchStore(private val file: File) {
    fun add(target: String, r: BenchmarkResult, estimate: String?) {
        file.parentFile.mkdirs()
        file.appendText(Json.encodeToString(BenchRecord.serializer(), BenchRecord(target, r, estimate)) + "\n")
    }
}
