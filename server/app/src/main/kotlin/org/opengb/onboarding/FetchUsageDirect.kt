package org.opengb.onboarding

import com.sksamuel.hoplite.Masked
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opengb.config.CryptoConfig
import org.opengb.http.UtilityHttpClients
import org.opengb.oauth.OAuthClient
import org.opengb.proxy.TokenCrypto
import org.opengb.proxy.UsageClient
import org.opengb.utility.TokenAuthStyle
import org.opengb.utility.UtilityProfile
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Operator diagnostic — hit savagedata's usage resource DIRECTLY (bypassing the deployed proxy) so
 * we can see the RAW response (status + all headers + body) for a Connect-My-Data usage request,
 * and iterate on the query shape without a proxy redeploy. Milton-specific; not part of the server.
 *
 * It reuses the production crypto + OAuth + fetch code paths:
 *   1. redeem the one-time claim code at the proxy   → encrypted refresh blob (+ subscriptionUri)
 *   2. TokenCrypto.decrypt(blob) with the AES key     → the raw refresh token
 *   3. OAuthClient.refresh() against savagedata        → a user access token (rotates the RT once)
 *   4. UsageClient.fetch() the subscription directly   → print raw status/headers/body per shape
 *
 * savagedata ignores client certs at runtime (verified), so this uses a plain TLS client.
 *
 *   ./gradlew :app:onboardFetchUsageDirect --args="<claimCode> [dateFilterParam]"
 *
 * Requires in the gitignored .env (both are Fly secrets — see README/notes for how to read them):
 *   OPENGB_CRYPTO_AESKEYBASE64                 — decrypts the refresh blob
 *   OPENGB_UTILITY_MILTON_HYDRO_CLIENTSECRET   — the CMD client secret, for the refresh grant
 */
private const val PROXY_BASE = "https://api.opengreenbutton.org"
private const val MILTON_TOKEN_URL = "https://sandboxdc.savagedata.com:4243/connect/token"

// Public (in utilities.conf), not a secret — the milton_hydro OAuth client id from ApplicationInformation.
private const val MILTON_CLIENT_ID = "12cc85f5-896d-4851-a359-d3698dbcf52b"

// A throwaway HMAC pepper: TokenCrypto validates its length but decrypt() never uses it.
private const val DUMMY_PEPPER_B64 = "AAAAAAAAAAAAAAAAAAAAAA=="

// How much of the (possibly large) response body to echo per probe.
private const val BODY_SNIPPET = 600

fun main(args: Array<String>) {
  val claim =
    args.getOrNull(0)?.takeIf { it.isNotBlank() }
      ?: error("usage: onboardFetchUsageDirect <claimCode> [dateFilterParam]")
  val dateFilterParam = args.getOrNull(1)?.takeIf { it.isNotBlank() }
  val env = dotenv()
  val aesKey = env("OPENGB_CRYPTO_AESKEYBASE64", env)
  val clientSecret = env("OPENGB_UTILITY_MILTON_HYDRO_CLIENTSECRET", env)

  val crypto = TokenCrypto(CryptoConfig(aesKeyBase64 = Masked(aesKey), hmacPepperBase64 = Masked(DUMMY_PEPPER_B64)))
  val utility =
    UtilityProfile(
      id = "milton_hydro",
      displayName = "Milton Hydro",
      authorizeUrl = "https://sandboxdc.savagedata.com:4243/connect/authorize",
      tokenUrl = MILTON_TOKEN_URL,
      clientId = MILTON_CLIENT_ID,
      clientSecret = Masked(clientSecret),
      defaultScope = "unused",
      tokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
    )

  // savagedata ignores client certs at runtime, so a plain TLS client is fine.
  UtilityHttpClients.mtlsClient(null).use { plain ->
    val clients = UtilityHttpClients.singleClient(plain)
    val oauth = OAuthClient(clients)
    val usage = UsageClient(clients)

    runBlocking {
      // 1) redeem the claim at the proxy
      val claimResp = plain.post("$PROXY_BASE/claim/$claim")
      val claimJson = claimResp.bodyAsText()
      check(claimResp.status == HttpStatusCode.OK) {
        "claim redeem failed (HTTP ${claimResp.status.value}) — the code must be FRESH and UNUSED " +
          "(claims are single-use and short-lived). body: $claimJson"
      }
      val claimObj = Json.parseToJsonElement(claimJson).jsonObject
      val encryptedBlob =
        claimObj["encryptedRefreshBlob"]?.jsonPrimitive?.content
          ?: error("no encryptedRefreshBlob in claim response: $claimJson")
      println("claim redeemed; subscriptionUri=${claimObj["subscriptionUri"]?.jsonPrimitive?.content}")

      // 2) decrypt → refresh token + subscription uri
      val blob = crypto.decrypt(encryptedBlob)
      val subscriptionUri = blob.subscriptionUri ?: error("no subscriptionUri in blob")

      // 3) refresh (rotates the one-time RT once) → a (savagedata makes these long-lived) access token
      val token = oauth.refresh(utility, blob.refreshToken, blob.scope)
      println("refreshed ok; expires_in=${token.expiresIn}")
      // SENSITIVE (sandbox usage-data token) — paste these two to drive raw curl experiments without
      // burning another claim; the token is effectively non-expiring.
      println("access_token    = ${token.accessToken}")
      println("subscriptionUri = $subscriptionUri")
      println()

      // 4) sweep query shapes with the single long-lived token. Pass a dateFilterParam arg to pin one
      //    base name; otherwise test both `published` and `updated`. Edit these calls freely.
      val now = Clock.System.now()
      val bases = dateFilterParam?.let { listOf(it) } ?: listOf("published", "updated")
      for (base in bases) {
        probe(usage, utility, subscriptionUri, token.accessToken, "$base both-30d", now - 30.days, now, base)
        probe(usage, utility, subscriptionUri, token.accessToken, "$base min-only", now - 30.days, null, base)
      }
    }
  }
}

@Suppress("LongParameterList")
private suspend fun probe(
  usage: UsageClient,
  utility: UtilityProfile,
  subscriptionUri: String,
  accessToken: String,
  label: String,
  publishedMin: Instant?,
  publishedMax: Instant?,
  dateFilterParam: String?,
) {
  val response: HttpResponse =
    usage.fetch(utility, subscriptionUri, accessToken, publishedMin, publishedMax, dateFilterParam).execute()
  val body = response.bodyAsText()
  println("=== $label (filter=${dateFilterParam ?: "published"}) ===")
  println("  GET ${response.call.request.url}")
  println("  HTTP ${response.status.value}")
  response.headers.entries().forEach { (k, v) -> println("    $k: ${v.joinToString(",")}") }
  println("  body (${body.length} chars): ${body.take(BODY_SNIPPET).replace("\n", " ")}")
  println()
}

private fun env(
  key: String,
  dotenv: Map<String, String>,
): String =
  (System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotenv[key]?.takeIf { it.isNotBlank() })
    ?: error("missing required config: set $key in the environment or .env")

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
