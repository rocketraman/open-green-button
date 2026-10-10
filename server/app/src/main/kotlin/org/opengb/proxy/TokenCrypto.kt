package org.opengb.proxy

import kotlinx.serialization.json.Json
import org.opengb.config.CryptoConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric AES-GCM crypto for the refresh-token blob, plus HMAC derivation of the per-client
 * `proxy_token` that authenticates subsequent proxy calls without server-side state.
 *
 * Wire format of an encrypted blob (all binary, then base64url-encoded with no padding):
 *
 *     [ version:1 ][ iv:12 ][ ciphertext + GCM tag ]
 *
 *  - version = 0x01 (single byte, allows future rotation)
 *  - iv = 12 random bytes (GCM nonce)
 *  - ciphertext = AES-256/GCM-encrypted JSON of [RefreshBlob], 16-byte auth tag appended by JCE
 */
class TokenCrypto(
  config: CryptoConfig,
  private val json: Json = DEFAULT_JSON,
) {
  private val aesKey: SecretKeySpec =
    run {
      val raw = decodeBase64(config.aesKeyBase64.value, "OPENGB_CRYPTO_AESKEYBASE64")
      require(raw.size == 32) {
        "OPENGB_CRYPTO_AESKEYBASE64 must decode to exactly 32 bytes (256 bits), got ${raw.size}"
      }
      SecretKeySpec(raw, "AES")
    }
  private val hmacKey: SecretKeySpec =
    run {
      val raw = decodeBase64(config.hmacPepperBase64.value, "OPENGB_CRYPTO_HMACPEPPERBASE64")
      require(raw.size >= 16) {
        "OPENGB_CRYPTO_HMACPEPPERBASE64 must decode to at least 16 bytes, got ${raw.size}"
      }
      SecretKeySpec(raw, "HmacSHA256")
    }

  fun encrypt(blob: RefreshBlob): String {
    val plaintext = json.encodeToString(RefreshBlob.serializer(), blob).toByteArray(Charsets.UTF_8)
    val iv = ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }
    val cipher = Cipher.getInstance(AES_GCM)
    cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
    val ct = cipher.doFinal(plaintext)

    val out = ByteArray(1 + IV_LEN + ct.size)
    out[0] = VERSION
    System.arraycopy(iv, 0, out, 1, IV_LEN)
    System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.size)
    return BASE64_URL_NO_PAD.encodeToString(out)
  }

  // Each throw distinguishes a different failure mode the caller wants to log and metric on
  // (malformed base64, truncated payload, wrong version byte, authentication failure).
  // Collapsing into a single exception type would erase that signal.
  @Suppress("ThrowsCount")
  fun decrypt(blob: String): RefreshBlob {
    val bytes =
      try {
        BASE64_URL_NO_PAD.decode(blob)
      } catch (_: IllegalArgumentException) {
        throw BlobDecryptionException("Encrypted blob is not valid base64url.")
      }
    if (bytes.size < 1 + IV_LEN + GCM_TAG_BITS / 8) {
      throw BlobDecryptionException("Encrypted blob is truncated.")
    }
    if (bytes[0] != VERSION) {
      throw BlobDecryptionException("Encrypted blob has unsupported version byte 0x${"%02x".format(bytes[0])}.")
    }
    val iv = bytes.copyOfRange(1, 1 + IV_LEN)
    val ct = bytes.copyOfRange(1 + IV_LEN, bytes.size)
    val cipher = Cipher.getInstance(AES_GCM)
    cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
    val plaintext =
      try {
        cipher.doFinal(ct)
      } catch (e: javax.crypto.AEADBadTagException) {
        throw BlobDecryptionException("Encrypted blob authentication failed.", e)
      }
    return json.decodeFromString(RefreshBlob.serializer(), String(plaintext, Charsets.UTF_8))
  }

  /**
   * Derives a deterministic, opaque `proxy_token` from the refresh token + utility id. The server
   * never stores this — it recomputes it on each proxy request from the decrypted blob and
   * constant-time compares against the `Authorization: Bearer ...` header.
   */
  fun deriveProxyToken(blob: RefreshBlob): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(hmacKey)
    mac.update(blob.utilityId.toByteArray(Charsets.UTF_8))
    mac.update(0)
    mac.update(blob.refreshToken.toByteArray(Charsets.UTF_8))
    return BASE64_URL_NO_PAD.encodeToString(mac.doFinal())
  }

  fun verifyProxyToken(
    blob: RefreshBlob,
    presented: String,
  ): Boolean {
    val expected = deriveProxyToken(blob).toByteArray(Charsets.US_ASCII)
    val actual = presented.toByteArray(Charsets.US_ASCII)
    return MessageDigest.isEqual(expected, actual)
  }

  companion object {
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val VERSION: Byte = 0x01

    private val secureRandom = SecureRandom()
    private val BASE64_URL_NO_PAD = Base64Url

    val DEFAULT_JSON =
      Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
      }
  }

  /** Thin wrapper to keep call sites tidy. */
  private object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encodeToString(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(s: String): ByteArray = decoder.decode(s)
  }
}

class BlobDecryptionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal fun decodeBase64(
  value: String,
  name: String,
): ByteArray =
  try {
    // Accept both standard and URL-safe base64; tolerant of padding presence/absence.
    val normalized = value.replace('-', '+').replace('_', '/').trimEnd('=')
    val padding = (4 - normalized.length % 4) % 4
    Base64.getDecoder().decode(normalized + "=".repeat(padding))
  } catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("$name is not valid base64.", e)
  }
