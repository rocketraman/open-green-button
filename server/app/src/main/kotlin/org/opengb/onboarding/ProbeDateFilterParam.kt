package org.opengb.onboarding

import com.sksamuel.hoplite.Masked
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.opengb.config.ClientAuthConfig
import org.opengb.config.CryptoConfig
import org.opengb.http.UtilityHttpClients
import org.opengb.oauth.OAuthClient
import org.opengb.proxy.TokenCrypto
import org.opengb.proxy.UsageClient
import org.opengb.utility.TokenAuthStyle
import org.opengb.utility.UtilityProfile
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Operator diagnostic — probe Burlington Hydro's resource server DIRECTLY (bypassing the deployed
 * proxy) with `published-min` vs `updated-min`, for an EXISTING account's subscription. Ground truth
 * for the "does `updated-min` actually filter to recent data, or return the whole history regardless
 * of window?" question — see docs/utilities/burlington-incremental-issue.md. Not part of the server.
 *
 * Unlike [FetchUsageDirect] (which redeems a FRESH claim for the sandbox), this takes an ALREADY
 * claimed account's `encryptedRefreshBlob` — e.g. copied from HA's `.storage/core.config_entries`
 * (`data.encrypted_refresh_blob` for the `greenbutton` domain entry) — so it exercises the real
 * production subscription instead of provisioning a new one. Burlington/London Hydro refresh tokens
 * are reusable (confirmed 2026-07), so the `oauth.refresh()` call below is safe to run standalone —
 * it will NOT desync Home Assistant's stored credentials the way a one-time-refresh-token custodian
 * (e.g. savagedata) would.
 *
 * SECRET HANDLING: this decrypts a real refresh token and mints a real access token for a live
 * account. Neither is ever printed — only a short, non-reversible fingerprint (see [mask]) — so
 * running this and sharing its console output does not leak usable credentials. Run it yourself,
 * in your own terminal, from your own `.env`; nothing here needs to be pasted anywhere.
 *
 * It reuses the production crypto + OAuth + fetch code paths:
 *   1. TokenCrypto.decrypt(blob)                        → the raw refresh token + subscriptionUri
 *   2. OAuthClient.refresh() against Burlington           → a real access token
 *   3. UsageClient.fetch() the subscription directly      → published-min / updated-min, narrow/wide
 *
 * Uses whatever DEFAULT `clientAuth` material the deployed proxy actually presents to Burlington
 * (see [UtilityHttpClients.from]) — that is, mTLS with our real cert IF this deployment's Fly
 * secrets set one, otherwise plain TLS, matching production exactly rather than assuming either
 * way (deployment.md's documented `fly secrets set` steps never set a clientAuth keystore, so most
 * deployments will fall through to plain TLS here — same as the Milton/savagedata diagnostic).
 *
 *   ./gradlew :app:onboardProbeDateFilterParam --args="<encryptedRefreshBlob>"
 *
 * Requires in the gitignored .env (Fly secrets — see README/notes for how to read them):
 *   OPENGB_CRYPTO_AESKEYBASE64                    — decrypts the refresh blob
 *   OPENGB_UTILITY_BURLINGTON_HYDRO_CLIENTSECRET  — the OAuth client secret, for the refresh grant
 *
 * Optional, only if this deployment presents a client cert to Burlington (most don't):
 *   OPENGB_CLIENTAUTH_KEYSTOREBASE64              — default mTLS keystore (PKCS12, base64)
 *   OPENGB_CLIENTAUTH_KEYSTOREPASSWORD            — keystore password (required if the above is set)
 *   OPENGB_CLIENTAUTH_KEYSTORETYPE                — optional, defaults to PKCS12
 *   OPENGB_CLIENTAUTH_KEYALIAS                    — optional
 *   OPENGB_CLIENTAUTH_KEYPASSWORD                 — optional
 */
private const val BURLINGTON_TOKEN_URL = "https://greenbutton.burlingtonhydro.com/oauth/token"

// Public (in utilities.conf), not a secret.
private const val BURLINGTON_CLIENT_ID = "opengreenbutton"
private const val BURLINGTON_SCOPE =
  "FB=1_3_4_5_13_15_16_28_31_37_39_51_53_54_55_56_57_58_59_60_61_64_65_68_69"

// A throwaway HMAC pepper: TokenCrypto validates its length but decrypt() never uses it.
private const val DUMMY_PEPPER_B64 = "AAAAAAAAAAAAAAAAAAAAAA=="

private val WINDOW = 4.days

fun main(args: Array<String>) {
  val encryptedBlob =
    args.getOrNull(0)?.takeIf { it.isNotBlank() }
      ?: error("usage: onboardProbeDateFilterParam <encryptedRefreshBlob>")
  val env = dotenv()
  val aesKey = env("OPENGB_CRYPTO_AESKEYBASE64", env)
  val crypto = TokenCrypto(CryptoConfig(aesKeyBase64 = Masked(aesKey), hmacPepperBase64 = Masked(DUMMY_PEPPER_B64)))
  val utility = burlingtonProfile(env)
  val clientAuth = defaultClientAuth(env)

  UtilityHttpClients.mtlsClient(clientAuth).use { mtls ->
    val clients = UtilityHttpClients.singleClient(mtls)
    val oauth = OAuthClient(clients)
    val usage = UsageClient(clients)

    runBlocking {
      // 1) decrypt the EXISTING blob → refresh token + real subscription URI
      val blob = crypto.decrypt(encryptedBlob)
      val subscriptionUri = blob.subscriptionUri ?: error("no subscriptionUri in blob")
      println("decrypted blob; subscriptionUri=$subscriptionUri")

      // 2) refresh → a real access token. Reusable refresh tokens on this platform (confirmed), so
      // this does not strand HA's stored credentials even if the response carries a new one.
      // Neither token is ever printed in full — see the SECRET HANDLING note above.
      val token = oauth.refresh(utility, blob.refreshToken, blob.scope)
      println("refreshed ok; expires_in=${token.expiresIn}")
      if (token.refreshToken != null && token.refreshToken != blob.refreshToken) {
        println(
          "NOTE: response carried a DIFFERENT refresh_token than the one in the blob: ${mask(token.refreshToken)}",
        )
      }
      println("access_token (masked) = ${mask(token.accessToken)}")
      println()

      val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
      probe(
        usage,
        utility,
        subscriptionUri,
        token.accessToken,
        "published",
        now - WINDOW,
        now,
        "published",
      )
/*
      probe(
        usage,
        utility,
        subscriptionUri,
        token.accessToken,
        "updated",
        now - WINDOW,
        now,
        "updated",
      )
*/
    }
  }
}

private fun burlingtonProfile(env: Map<String, String>): UtilityProfile =
  UtilityProfile(
    id = "burlington_hydro",
    displayName = "Burlington Hydro",
    authorizeUrl = "https://greenbutton.burlingtonhydro.com/oauth/authorize",
    tokenUrl = BURLINGTON_TOKEN_URL,
    clientId = BURLINGTON_CLIENT_ID,
    clientSecret = Masked(env("OPENGB_UTILITY_BURLINGTON_HYDRO_CLIENTSECRET", env)),
    defaultScope = BURLINGTON_SCOPE,
    tokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
  )

// OPTIONAL — mirrors production exactly: AppConfig.clientAuth (and therefore what the deployed
// proxy presents to Burlington) is only real client-cert material if this Fly secret is set; a
// deployment that never set it runs plain TLS. Deriving `null` here when the env var is absent
// reproduces that fallback instead of guessing which one this deployment actually uses.
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
  val range = readingDateRange(body)
  val outFile = File("probe-${label.replace(' ', '-')}.xml").also { it.writeText(body) }
  println("=== $label (filter=${dateFilterParam ?: "published"}) ===")
  // The request URL carries only the date-window query params (no token) — safe to print in full.
  println("  GET ${response.call.request.url}")
  println("  HTTP ${response.status.value}")
  println("  body: ${body.length} chars, ${range.count} IntervalReading(s)")
  if (range.count > 0) {
    println("  reading date range: ${range.earliest} .. ${range.latest}")
  } else if (range.parseFailure != null) {
    println("  (could not parse response as XML: ${range.parseFailure} — inspect the saved file)")
  }
  println("  saved to ${outFile.absolutePath}")
  println()
}

private data class ReadingDateRange(
  val count: Int,
  val earliest: Instant? = null,
  val latest: Instant? = null,
  val parseFailure: String? = null,
)

// Namespace-agnostic XPath scoped to IntervalReading/timePeriod/start specifically — NOT a
// blanket "any <start> element" match. UsageSummary/billingPeriod also has its own <start>
// (a completely different, often much-older date); conflating the two would silently corrupt
// the earliest/latest computation with billing-period dates instead of reading dates.
private const val INTERVAL_READING_START_XPATH =
  "//*[local-name()='IntervalReading']/*[local-name()='timePeriod']/*[local-name()='start']/text()"

// Any parse failure on an untrusted/error body (e.g. a 400's HTML/JSON error page) should be
// reported, not crash the whole probe sweep — deliberately broad catch.
@Suppress("TooGenericExceptionCaught")
private fun readingDateRange(xmlBody: String): ReadingDateRange {
  if (xmlBody.isBlank()) return ReadingDateRange(count = 0)
  return try {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        // Hardening against XXE — parses a remote response, no reason to resolve external entities.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
      }
    val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlBody)))
    val nodes =
      XPathFactory.newInstance().newXPath().evaluate(
        INTERVAL_READING_START_XPATH,
        doc,
        XPathConstants.NODESET,
      )
    val epochSeconds =
      (nodes as org.w3c.dom.NodeList)
        .let { list -> (0 until list.length).mapNotNull { list.item(it).nodeValue?.trim()?.toLongOrNull() } }
    ReadingDateRange(
      count = epochSeconds.size,
      earliest = epochSeconds.minOrNull()?.let { Instant.fromEpochSeconds(it) },
      latest = epochSeconds.maxOrNull()?.let { Instant.fromEpochSeconds(it) },
    )
  } catch (e: Exception) {
    // A non-200 / non-XML body (e.g. a 400's error page) shouldn't crash the whole probe sweep —
    // surface it via the return value; the caller reports it and falls back to the saved file.
    ReadingDateRange(count = 0, parseFailure = e.message ?: e.javaClass.simpleName)
  }
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
