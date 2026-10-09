package dev.dreamz.llmwizard.core.bundle

import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.core.recipe.Recipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/**
 * The recipe and catalog bundle (PLAN.md 5.6, Phase 5): one JSON file plus a detached Ed25519
 * signature. A recipe is root on someone's server, so an unsigned, badly signed or older bundle
 * is ignored and the copy built into the app is used instead.
 */
@Serializable
data class DataBundle(
    val version: Int,
    val createdAt: String,
    val recipes: Map<String, JsonElement>,
    val catalog: JsonElement,
    val snippets: JsonElement? = null,
) {
    fun recipe(id: String): Recipe? = recipes[id]?.let { Recipe.json.decodeFromJsonElement(Recipe.serializer(), it) }
    fun catalog(): Catalog = Catalog.json.decodeFromJsonElement(Catalog.serializer(), catalog)
}

sealed interface BundleResult {
    data class Accepted(val bundle: DataBundle, val keyId: String) : BundleResult
    data class Rejected(val reason: String) : BundleResult
}

/** A pinned public key. The app ships two (current and next) so the signing key can be rotated. */
data class PinnedKey(val id: String, val publicKey: ByteArray)

object BundleSigning {
    private val json = Json { ignoreUnknownKeys = true }
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    /** A new signing key pair, base64. The private half belongs offline, with Jay. */
    fun generateKeyPair(random: SecureRandom = SecureRandom()): Pair<String, String> {
        val priv = Ed25519PrivateKeyParameters(random)
        return b64.encodeToString(priv.encoded) to b64.encodeToString(priv.generatePublicKey().encoded)
    }

    fun sign(bundleBytes: ByteArray, privateKeyB64: String): String {
        val priv = Ed25519PrivateKeyParameters(unb64.decode(privateKeyB64.trim()), 0)
        val s = Ed25519Signer().apply { init(true, priv); update(bundleBytes, 0, bundleBytes.size) }
        return b64.encodeToString(s.generateSignature())
    }

    /**
     * Verifies [bundleBytes] against [signatureB64] with any of [keys], then checks the version
     * is not lower than [minVersion] (rollback protection) and that every recipe parses.
     */
    fun verify(bundleBytes: ByteArray, signatureB64: String, keys: List<PinnedKey>, minVersion: Int): BundleResult {
        val sig = runCatching { unb64.decode(signatureB64.trim()) }.getOrNull() ?: return BundleResult.Rejected("signature is not base64")
        val key = keys.firstOrNull { k ->
            runCatching {
                Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(k.publicKey, 0)); update(bundleBytes, 0, bundleBytes.size) }.verifySignature(sig)
            }.getOrDefault(false)
        } ?: return BundleResult.Rejected("signature does not match any pinned key")
        val bundle = runCatching { json.decodeFromString(DataBundle.serializer(), bundleBytes.toString(Charsets.UTF_8)) }.getOrElse {
            return BundleResult.Rejected("bundle does not parse: ${it.message}")
        }
        if (bundle.version < minVersion) return BundleResult.Rejected("bundle version ${bundle.version} is older than ${minVersion}, which was already seen")
        for (id in bundle.recipes.keys) runCatching { bundle.recipe(id) }.onFailure { return BundleResult.Rejected("recipe $id does not parse") }
        runCatching { bundle.catalog() }.onFailure { return BundleResult.Rejected("catalog does not parse") }
        return BundleResult.Accepted(bundle, key.id)
    }

    /** Builds a bundle from the repo's recipes/ and catalog/ directories (the signing tool's input). */
    fun make(version: Int, createdAt: String, recipes: Map<String, String>, catalog: String, snippets: String?): String {
        val r = recipes.mapValues { json.parseToJsonElement(it.value) }
        val b = DataBundle(version, createdAt, r, json.parseToJsonElement(catalog), snippets?.let(json::parseToJsonElement))
        return Json { prettyPrint = false }.encodeToString(DataBundle.serializer(), b)
    }

    fun pinned(id: String, publicKeyB64: String) = PinnedKey(id, unb64.decode(publicKeyB64.trim()))

    @Suppress("unused") private fun JsonObject.asMap() = this
}
