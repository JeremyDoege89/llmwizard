package dev.dreamz.llmwizard.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.dreamz.llmwizard.core.recipe.JobRef
import dev.dreamz.llmwizard.core.recipe.ResumeStore
import dev.dreamz.llmwizard.core.recipe.Transcript
import dev.dreamz.llmwizard.core.recipe.TranscriptEntry
import dev.dreamz.llmwizard.core.ssh.HostKeyStore
import dev.dreamz.llmwizard.core.ssh.KnownHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed versions of the stores :core asks for. Called from IO threads only. */
class RoomResumeStore(private val dao: JobDao) : ResumeStore {
    override fun get(target: String, recipeId: String, stepId: String) =
        dao.getSync(target, recipeId, stepId)?.let { JobRef(it.jobId, it.step, it.startedAt) }
    override fun put(target: String, recipeId: String, stepId: String, ref: JobRef) =
        dao.putSync(JobEntity(target, recipeId, stepId, ref.jobId, ref.step, ref.startedAt))
    override fun clear(target: String, recipeId: String, stepId: String) = dao.clearSync(target, recipeId, stepId)
}

class RoomTranscript(private val dao: TranscriptDao) : Transcript {
    override fun record(entry: TranscriptEntry) =
        dao.insertSync(TranscriptRow(0, entry.target, entry.at, entry.kind, entry.script, entry.exitCode, entry.outputTail))
}

/** Host keys live on the server rows. A server row must exist before its key can be stored. */
class RoomHostKeyStore(private val dao: ServerDao) : HostKeyStore {
    override fun get(host: String, port: Int) = dao.byHostSync(host, port)?.let { s ->
        if (s.hostKeyType != null && s.hostKeyFingerprint != null) KnownHost(s.hostKeyType, s.hostKeyFingerprint) else null
    }
    override fun put(host: String, port: Int, key: KnownHost) = dao.setHostKeySync(host, port, key.keyType, key.fingerprint)
    /** Forgetting a host key is a deliberate action the user takes (PLAN.md 5.1). */
    override fun forget(host: String, port: Int) = dao.setHostKeySync(host, port, null, null)
}

private val Context.settingsStore by preferencesDataStore("settings")

class Settings(private val context: Context) {
    private val firstRunDone = booleanPreferencesKey("first_run_done")
    val firstRunAccepted: Flow<Boolean> = context.settingsStore.data.map { it[firstRunDone] == true }
    suspend fun acceptFirstRun() { context.settingsStore.edit { it[firstRunDone] = true } }
}
