package app.hivpn.core.network

import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies the server directory before anything from it is trusted.
 *
 * ECDSA P-256 / SHA-256 is used instead of Ed25519 because it is available through the
 * standard JCA provider on every Android version back to API 26; Ed25519 is not.
 * Uses java.util.Base64 (API 26+) so this class is plain JVM and unit-testable.
 */
class DirectoryVerifier(publicKeyBase64: String, private val clock: () -> Long = System::currentTimeMillis) {

    private val publicKey: PublicKey? = publicKeyBase64.takeIf { it.isNotBlank() }?.let {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(it)))
    }

    sealed interface Outcome {
        data class Valid(val payload: DirectoryPayload) : Outcome
        data object NoKeyConfigured : Outcome
        data object BadSignature : Outcome
        data object Expired : Outcome
        data class Malformed(val detail: String) : Outcome
    }

    fun verify(signed: SignedDirectoryDto): Outcome {
        val key = publicKey ?: return Outcome.NoKeyConfigured
        val payloadBytes = try {
            Base64.getDecoder().decode(signed.payload)
        } catch (e: IllegalArgumentException) {
            return Outcome.Malformed("payload is not base64")
        }
        val sigBytes = try {
            Base64.getDecoder().decode(signed.signature)
        } catch (e: IllegalArgumentException) {
            return Outcome.Malformed("signature is not base64")
        }
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payloadBytes)
                verify(sigBytes)
            }
        } catch (e: java.security.SignatureException) {
            false
        }
        if (!ok) return Outcome.BadSignature

        val payload = try {
            json.decodeFromString(DirectoryPayload.serializer(), payloadBytes.decodeToString())
        } catch (e: Exception) {
            return Outcome.Malformed(e.javaClass.simpleName)
        }
        if (payload.expiresAt * 1000 < clock()) return Outcome.Expired
        return Outcome.Valid(payload)
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
