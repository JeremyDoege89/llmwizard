package dev.dreamz.llmwizard.core.ssh

import java.io.File
import java.util.Properties

/** Host keys in a properties file, for the CLI. The app keeps them in Room. */
class FileHostKeyStore(private val file: File) : HostKeyStore {
    private fun load() = Properties().apply { if (file.isFile) file.inputStream().use { load(it) } }

    override fun get(host: String, port: Int): KnownHost? =
        load().getProperty("$host:$port")?.split(' ', limit = 2)?.let { KnownHost(it[0], it[1]) }

    @Synchronized
    override fun put(host: String, port: Int, key: KnownHost) = save { setProperty("$host:$port", "${key.keyType} ${key.fingerprint}") }

    @Synchronized
    override fun forget(host: String, port: Int) = save { remove("$host:$port") }

    private fun save(edit: Properties.() -> Unit) {
        val p = load().apply(edit)
        file.parentFile?.mkdirs()
        file.outputStream().use { p.store(it, "llmwizard known hosts: host:port = keytype SHA256:fingerprint") }
    }
}

/** In-memory store for tests and one-off runs. */
class MemoryHostKeyStore : HostKeyStore {
    private val map = mutableMapOf<String, KnownHost>()
    override fun get(host: String, port: Int) = synchronized(map) { map["$host:$port"] }
    override fun put(host: String, port: Int, key: KnownHost) = synchronized(map) { map["$host:$port"] = key }
    override fun forget(host: String, port: Int) { synchronized(map) { map.remove("$host:$port") } }
}
