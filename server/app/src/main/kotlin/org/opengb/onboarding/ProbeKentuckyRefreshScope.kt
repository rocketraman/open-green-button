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

/**
 * Operator diagnostic — determine which `scope` value Kentucky Utilities' token endpoint accepts on
 * the **refresh_token** grant, without burning repeated customer re-authorizations.
 *
 * Background: KU's `/authorize` REQUIRES the full scope (bare `FB=` list and no-scope both fail with
 * `invalid_scope`), yet replaying that full scope — or nothing — on the later refresh grant fails
 * with `invalid_scope` "requested scope does not match the scope granted by the resource owner". The
 * hypothesis is that the format params (IntervalDuration/BlockDuration/SubscriptionFrequency/
 * HistoryLength) are authorize-time REQUEST preferences, not part of the GRANT, so refresh must send
 * only the bare `FB=` list. This probe replays one refresh token three ways and reports which KU
 * accepts, turning that hypothesis into a fact from a single token.
 *
 * No end-user credentials are involved: the refresh_token grant authenticates with our third-party
 * client_id/secret plus a refresh token. Like [ProbeDateFilterParam], this takes an ALREADY-claimed
 * account's `encryptedRefreshBlob` — e.g. copied from a successful KU connect (the claim response),
 * or from HA's `.storage/core.config_entries` (`data.encrypted_refresh_blob` for the `greenbutton`
 * entry) — so no new consent is needed if you still have one.
 *
 * ORDERING & ROTATION: variants are run expected-to-fail first (full scope, then no scope). A
 * rejected refresh (4xx) does NOT consume the refresh token, so those leave it valid for the next
 * variant. The bare `FB=` list is tried last because if it SUCCEEDS it may rotate the token. If any
 * variant unexpectedly succeeds early, the returned (rotated) token is carried into the remaining
 * variants so they fail on scope, not on `invalid_grant`.
 *
 * ⚠️ SIDE EFFECT: if KU issues one-time refresh tokens (unconfirmed — Burlington/London are reusable,
 * savagedata is not), a SUCCESSFUL variant here invalidates the token Home Assistant still holds,
 * desyncing that account until it re-auths. Run this against a throwaway/test KU account, and expect
 * to re-auth it afterward.
 *
 * SECRET HANDLING: decrypts a real refresh token. Neither it nor the minted access token is ever
 * printed — only a short non-reversible fingerprint ([mask]). KU error bodies (the `invalid_scope`
 * JSON) carry no secret and are printed verbatim to show the exact rejection.
 *
 *   ./gradlew :app:onboardProbeKentuckyRefreshScope --args="<encryptedRefreshBlob>"
 *
 * Requires in mise.local.toml (Fly secrets):
 *   OPENGB_CRYPTO_AESKEYBASE64                        — decrypts the refresh blob
 *   OPENGB_UTILITY_KENTUCKY_UTILITIES_CLIENTID        — the KU-issued client_id
 *   OPENGB_UTILITY_KENTUCKY_UTILITIES_CLIENTSECRET    — the OAuth client secret, for the refresh grant
 * Optional (only if this deployment presents a client cert; most don't — see ProbeDateFilterParam):
 *   OPENGB_CLIENTAUTH_KEYSTOREBASE64 / …PASSWORD / …TYPE / …KEYALIAS / …KEYPASSWORD
 */
private const val KU_TOKEN_URL = "https://mymeter.lge-ku.com/OAuthServer/token"
private const val KU_AUTHORIZE_URL = "https://mymeter.lge-ku.com/OAuthServer/authorize"

// Must match utilities.conf's kentucky_utilities defaultScope.
private const val KU_FULL_SCOPE =
  "FB=1_3_4_5_15_16_32_37_39;IntervalDuration=900_3600;BlockDuration=Monthly;" +
    "SubscriptionFrequency=Daily;HistoryLength=94608000"
private const val KU_FB_ONLY_SCOPE = "FB=1_3_4_5_15_16_32_37_39"

// A throwaway HMAC pepper: TokenCrypto validates its length but decrypt() never uses it.
private const val DUMMY_PEPPER_B64 = "AAAAAAAAAAAAAAAAAAAAAA=="

private data class ScopeVariant(val label: String, val scope: String?)

fun main(args: Array<String>) {
  val encryptedBlob =
    args.getOrNull(0)?.takeIf { it.isNotBlank() }
      ?: error("usage: onboardProbeKentuckyRefreshScope <encryptedRefreshBlob>")
  val env = System.getenv()
  val aesKey = env("OPENGB_CRYPTO_AESKEYBASE64", env)
  val crypto = TokenCrypto(CryptoConfig(aesKeyBase64 = Masked(aesKey), hmacPepperBase64 = Masked(DUMMY_PEPPER_B64)))
  val utility = kentuckyProfile(env)
  val clientAuth = defaultClientAuth(env)

  // Expected-to-fail first (rejections don't consume the token); the likely-good one (bare FB list)
  // last, since a success may rotate the token.
  val variants =
    listOf(
      ScopeVariant("full scope (with format params — what /authorize requires)", KU_FULL_SCOPE),
      ScopeVariant("no scope (omitted → RFC 6749 §6 'same as granted')", null),
      ScopeVariant("bare FB list (format params stripped — the hypothesised grant)", KU_FB_ONLY_SCOPE),
    )

  UtilityHttpClients.mtlsClient(clientAuth).use { http ->
    val clients = UtilityHttpClients.singleClient(http)
    val oauth = OAuthClient(clients)

    runBlocking {
      val blob = crypto.decrypt(encryptedBlob)
      println("decrypted blob; utilityId=${blob.utilityId}, blob.scope=${blob.scope}")
      println("token endpoint = $KU_TOKEN_URL")
      println("client_id = ${utility.clientId}")
      println()

      var refreshToken = blob.refreshToken
      for (variant in variants) {
        println("=== refresh variant: ${variant.label} ===")
        println("  scope sent: ${variant.scope ?: "(omitted)"}")
        try {
          val token = oauth.refresh(utility, refreshToken, variant.scope)
          println("  HTTP 200 — ACCEPTED ✅")
          println("  granted scope in response: ${token.scope}")
          println("  expires_in=${token.expiresIn}")
          val rotated = token.refreshToken
          if (!rotated.isNullOrBlank() && rotated != refreshToken) {
            println("  NOTE: refresh token ROTATED (${mask(rotated)}); carrying it into later variants")
            refreshToken = rotated
          }
        } catch (e: OAuthException) {
          // e.message already includes "returned <status> from token endpoint: <body>".
          println("  REJECTED ❌ ${e.message}")
        }
        println()
      }
      println("Done. The first ACCEPTED variant is the scope to send on KU refresh grants.")
    }
  }
}

private fun kentuckyProfile(env: Map<String, String>): UtilityProfile =
  UtilityProfile(
    id = "kentucky_utilities",
    displayName = "Kentucky Utilities",
    authorizeUrl = KU_AUTHORIZE_URL,
    tokenUrl = KU_TOKEN_URL,
    clientId = env("OPENGB_UTILITY_KENTUCKY_UTILITIES_CLIENTID", env),
    clientSecret = Masked(env("OPENGB_UTILITY_KENTUCKY_UTILITIES_CLIENTSECRET", env)),
    defaultScope = KU_FULL_SCOPE,
    tokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
  )

// OPTIONAL — mirrors production: the default clientAuth is only real client-cert material if this
// Fly secret is set; a deployment that never set it runs plain TLS. Deriving `null` when the env
// var is absent reproduces that fallback rather than guessing (see ProbeDateFilterParam).
private fun defaultClientAuth(env: Map<String, String>): ClientAuthConfig? =
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
    ?: error("missing required config: set $key via mise (mise.local.toml)")

private fun optionalEnv(
  key: String,
  dotenv: Map<String, String>,
): String? = System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotenv[key]?.takeIf { it.isNotBlank() }

