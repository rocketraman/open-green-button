package org.opengb.onboarding

import com.sksamuel.hoplite.Masked
import kotlinx.coroutines.runBlocking
import org.opengb.config.ClientAuthConfig
import org.opengb.config.CryptoConfig
import org.opengb.http.UtilityHttpClients
import org.opengb.oauth.OAuthClient
import org.opengb.oauth.OAuthException
import org.opengb.proxy.TokenCrypto
import org.opengb.utility.TokenAuthStyle
import org.opengb.utility.UtilityProfile
import java.io.File

/**
 * Operator diagnostic — settle the `refreshScope` DEFAULT question: does the Burlington Hydro /
 * London Hydro platform accept an EXPLICIT `scope` on the refresh grant (one equal to what it
 * granted), or does it only accept a scopeless refresh?
 *
 *  - If it accepts the explicit granted scope → `Granted` is a safe default (it also auto-fixes KU).
 *  - If it rejects the explicit scope but accepts omit → `Omit` must stay the default, and `Granted`
 *    is an opt-in for KU only.
 *
 * Burlington/London refresh tokens are REUSABLE (confirmed 2026-07; see [ProbeDateFilterParam]), so
 * running both variants off one token is safe and won't desync Home Assistant. Takes an
 * ALREADY-claimed account's `encryptedRefreshBlob` — e.g. from HA's `.storage/core.config_entries`
 * (`data.encrypted_refresh_blob` for the `greenbutton` entry). No end-user credentials involved.
 *
 * SECRET HANDLING: decrypts a real refresh token; neither it nor the access token is printed — only
 * a masked fingerprint. Error bodies (the `invalid_scope` JSON) carry no secret and are shown.
 *
 *   ./gradlew :app:onboardProbeBurlingtonRefreshScope --args="<encryptedRefreshBlob>"
 *
 * Requires in the gitignored .env:
 *   OPENGB_CRYPTO_AESKEYBASE64                    — decrypts the refresh blob
 *   OPENGB_UTILITY_BURLINGTON_HYDRO_CLIENTSECRET  — the OAuth client secret, for the refresh grant
 * Optional (only if this deployment presents a client cert; most don't):
 *   OPENGB_CLIENTAUTH_KEYSTOREBASE64 / …PASSWORD / …TYPE / …KEYALIAS / …KEYPASSWORD
 */
private const val BURLINGTON_TOKEN_URL_PROBE = "https://greenbutton.burlingtonhydro.com/oauth/token"
private const val BURLINGTON_AUTHORIZE_URL_PROBE = "https://greenbutton.burlingtonhydro.com/oauth/authorize"
private const val BURLINGTON_CLIENT_ID_PROBE = "opengreenbutton"
private const val BURLINGTON_SCOPE_PROBE =
  "FB=1_3_4_5_13_15_16_28_31_37_39_51_53_54_55_56_57_58_59_60_61_64_65_68_69"

// A throwaway HMAC pepper: TokenCrypto validates its length but decrypt() never uses it.
private const val DUMMY_PEPPER_B64 = "AAAAAAAAAAAAAAAAAAAAAA=="

fun main(args: Array<String>) {
  val encryptedBlob =
    args.getOrNull(0)?.takeIf { it.isNotBlank() }
      ?: error("usage: onboardProbeBurlingtonRefreshScope <encryptedRefreshBlob>")
  val env = dotenv()
  val aesKey = env("OPENGB_CRYPTO_AESKEYBASE64", env)
  val crypto = TokenCrypto(CryptoConfig(aesKeyBase64 = Masked(aesKey), hmacPepperBase64 = Masked(DUMMY_PEPPER_B64)))
  val utility = burlingtonProfileProbe(env)
  val clientAuth = defaultClientAuthBurl(env)

  UtilityHttpClients.mtlsClient(clientAuth).use { http ->
    val clients = UtilityHttpClients.singleClient(http)
    val oauth = OAuthClient(clients)

    runBlocking {
      val blob = crypto.decrypt(encryptedBlob)
      // What "Granted" mode would replay on refresh is exactly blob.scope. If the utility echoed no
      // scope at exchange, fall back to the registered scope so we still test an explicit value.
      val grantedScope = blob.scope?.takeIf { it.isNotBlank() } ?: BURLINGTON_SCOPE_PROBE
      println("decrypted blob; blob.scope=${blob.scope}")
      println("token endpoint = $BURLINGTON_TOKEN_URL_PROBE")
      println()

      var refreshToken = blob.refreshToken
      // Reusable tokens, so order doesn't matter; carry forward any rotated token defensively anyway.
      for ((label, scope) in listOf("omit scope (current default)" to null, "explicit granted scope" to grantedScope)) {
        println("=== refresh variant: $label ===")
        println("  scope sent: ${scope ?: "(omitted)"}")
        try {
          val token = oauth.refresh(utility, refreshToken, scope)
          println("  HTTP 200 — ACCEPTED ✅  granted scope in response: ${token.scope}")
          val rotated = token.refreshToken
          if (!rotated.isNullOrBlank() && rotated != refreshToken) {
            println("  NOTE: refresh token rotated (${mask(rotated)}); carrying it forward")
            refreshToken = rotated
          }
        } catch (e: OAuthException) {
          println("  REJECTED ❌ ${e.message}")
        }
        println()
      }
      println("If BOTH accepted → Granted is a safe default. If only 'omit' accepted → keep Omit default.")
    }
  }
}

private fun burlingtonProfileProbe(env: Map<String, String>): UtilityProfile =
  UtilityProfile(
    id = "burlington_hydro",
    displayName = "Burlington Hydro",
    authorizeUrl = BURLINGTON_AUTHORIZE_URL_PROBE,
    tokenUrl = BURLINGTON_TOKEN_URL_PROBE,
    clientId = BURLINGTON_CLIENT_ID_PROBE,
    clientSecret = Masked(env("OPENGB_UTILITY_BURLINGTON_HYDRO_CLIENTSECRET", env)),
    defaultScope = BURLINGTON_SCOPE_PROBE,
    tokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
  )

private fun defaultClientAuthBurl(env: Map<String, String>): ClientAuthConfig? =
  optionalEnv("OPENGB_CLIENTAUTH_KEYSTOREBASE64", env)?.let { keystoreBase64 ->
    ClientAuthConfig(
      keystoreBase64 = Masked(keystoreBase64),
      keystorePassword = Masked(env("OPENGB_CLIENTAUTH_KEYSTOREPASSWORD", env)),
      keystoreType = optionalEnv("OPENGB_CLIENTAUTH_KEYSTORETYPE", env) ?: "PKCS12",
      keyAlias = optionalEnv("OPENGB_CLIENTAUTH_KEYALIAS", env),
      keyPassword = optionalEnv("OPENGB_CLIENTAUTH_KEYPASSWORD", env)?.let { Masked(it) },
    )
  }

private fun mask(value: String): String = if (value.length <= 12) "***" else "${value.take(8)}…(${value.length} chars)"

private fun env(
  key: String,
  dotenv: Map<String, String>,
): String =
  optionalEnv(key, dotenv)
    ?: error("missing required config: set $key in the environment or .env")

private fun optionalEnv(
  key: String,
  dotenv: Map<String, String>,
): String? = System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotenv[key]?.takeIf { it.isNotBlank() }

/** Minimal .env loader: search the working dir and ancestors for the first `.env`. */
private fun dotenv(): Map<String, String> {
  val file =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, ".env") }
      .firstOrNull { it.isFile } ?: return emptyMap()
  return file.readLines().mapNotNull { line ->
    val t = line.trim().removePrefix("export ").trim()
    if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
    val eq = t.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
    t.substring(0, eq).trim() to t.substring(eq + 1).trim().trim('"', '\'')
  }.toMap()
}
