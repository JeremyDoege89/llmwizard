package dev.dreamz.llmwizard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.dreamz.llmwizard.core.ssh.Keys
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The app's SSH key (PLAN.md 5.1, 5.10). The private key is encrypted with an AES-GCM key that
 * lives in the Android Keystore and never leaves it, and is stored in app-private files, which
 * allowBackup=false keeps off backups. security-crypto is deprecated, so this uses the Keystore
 * directly.
 */
class KeyVault(context: Context) {
    private val dir = File(context.filesDir, "keys").apply { mkdirs() }
    private val encFile = File(dir, "app_ed25519.enc")
    private val pubFile = File(dir, "app_ed25519.pub")

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun hasKey() = encFile.isFile && pubFile.isFile

    /** Creates the key the first time; returns the public key line. */
    @Synchronized
    fun ensureKey(): String {
        if (hasKey()) return pubFile.readText().trim()
        val k = Keys.generateEd25519("llmwizard@" + android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "-"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val ct = cipher.doFinal(k.privateKeyOpenSsh.toByteArray())
        encFile.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ct)
        pubFile.writeText(k.publicKeyLine + "\n")
        return k.publicKeyLine
    }

    fun publicKey(): String? = if (pubFile.isFile) pubFile.readText().trim() else null

    fun fingerprint(): String? = publicKey()?.let { Keys.fingerprintOfLine(it) }

    /** The decrypted private key, for one connection attempt. */
    @Synchronized
    fun privateKey(): String {
        val blob = encFile.readBytes()
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen).toString(Charsets.UTF_8)
    }

    private companion object { const val ALIAS = "llmwizard_ssh_key_wrap" }
}
