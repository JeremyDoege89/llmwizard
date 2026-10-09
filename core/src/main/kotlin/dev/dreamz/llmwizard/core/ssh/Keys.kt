package dev.dreamz.llmwizard.core.ssh

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The app's own login key (PLAN.md 5.1): Ed25519, written in OpenSSH's private key format so
 * sshj (and `ssh -i`) can read it. Uses BouncyCastle's lightweight API directly, which does
 * not depend on which JCA providers the platform registers.
 */
class GeneratedKey(
    /** `-----BEGIN OPENSSH PRIVATE KEY-----` text, unencrypted. Encrypt before storing. */
    val privateKeyOpenSsh: String,
    /** One `ssh-ed25519 AAAA… comment` line for authorized_keys. */
    val publicKeyLine: String,
    /** SHA256 fingerprint of the public key, as `ssh-keygen -lf` prints it. */
    val fingerprint: String,
)

object Keys {
    fun generateEd25519(comment: String, random: SecureRandom = SecureRandom()): GeneratedKey {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }
        val pair = gen.generateKeyPair()
        val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded
        val pubBlob = blob { str("ssh-ed25519"); bytes(pub) }

        val check = random.nextInt()
        var privSection = blob {
            int(check); int(check)
            str("ssh-ed25519")
            bytes(pub)
            bytes(priv + pub) // OpenSSH stores the 32-byte seed followed by the public key
            str(comment)
        }
        // Pad to the cipher block size (8 for "none") with 1, 2, 3, ...
        val pad = (8 - privSection.size % 8) % 8
        privSection += ByteArray(pad) { (it + 1).toByte() }

        val body = ByteArrayOutputStream().apply {
            write("openssh-key-v1".toByteArray()); write(0)
            write(blob {
                str("none"); str("none"); bytes(ByteArray(0))
                int(1)
                bytes(pubBlob)
                bytes(privSection)
            })
        }.toByteArray()

        val b64 = Base64.getEncoder().encodeToString(body).chunked(70).joinToString("\n")
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
        priv.fill(0)
        val line = "ssh-ed25519 " + Base64.getEncoder().encodeToString(pubBlob) + " " + comment
        return GeneratedKey(pem, line, fingerprintOfBlob(pubBlob))
    }

    fun fingerprintOfBlob(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** Fingerprint of an authorized_keys-style line. */
    fun fingerprintOfLine(line: String): String =
        fingerprintOfBlob(Base64.getDecoder().decode(line.trim().split(Regex("\\s+"))[1]))

    private class BlobWriter {
        val out = ByteArrayOutputStream()
        private val data = DataOutputStream(out)
        fun int(v: Int) = data.writeInt(v)
        fun bytes(b: ByteArray) { data.writeInt(b.size); data.write(b) }
        fun str(s: String) = bytes(s.toByteArray(Charsets.US_ASCII))
    }

    private fun blob(block: BlobWriter.() -> Unit): ByteArray = BlobWriter().apply(block).out.toByteArray()
}
