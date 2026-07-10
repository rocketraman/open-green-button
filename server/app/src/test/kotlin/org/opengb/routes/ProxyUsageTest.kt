package org.opengb.routes

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.opengb.appModule
import org.opengb.buildAppDeps
import org.opengb.config.AppConfig
import org.opengb.config.CryptoConfig
import org.opengb.config.LandingConfig
import org.opengb.config.ServerConfig
import org.opengb.config.StateConfig
import org.opengb.proxy.RefreshBlob
import org.opengb.proxy.TokenCrypto
import org.opengb.utility.TokenAuthStyle
import org.opengb.utility.UtilityProfile
import org.opengb.utility.UtilityQuirks
import java.util.Base64
import kotlin.time.Instant

/**
 * End-to-end test of `POST /proxy/usage` against a Ktor MockEngine that fakes:
 *   - the utility's token endpoint (refresh grant)
 *   - the utility's resource endpoint (returns a small ESPI Atom feed)
 *
 * After the move to pure streaming pass-through, this test no longer covers XML parsing or
 * normalization — those moved to the HA client. The interesting behaviour here is the auth
 * + refresh + stream-the-body-verbatim wire glue, plus the rotated-credentials surface that
 * now flows via response **headers** rather than a JSON envelope.
 */
val ProxyUsageTest by testSuite {
  test("happy path: streams the upstream Atom XML through verbatim") {
    runProxyUsage { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.OK) { "got ${resp.status}: ${resp.bodyAsText()}" }
      assert(resp.headers[HttpHeaders.ContentType]?.startsWith("application/atom+xml") == true) {
        resp.headers[HttpHeaders.ContentType].toString()
      }
      val body = resp.bodyAsText()
      // The fake upstream returned MOCK_USAGE_FEED — assert we pass it through byte-for-byte
      // (whitespace and all) rather than re-emit a parsed form.
      assert(body == MOCK_USAGE_FEED) {
        "body did not round-trip verbatim:\n--- got ---\n$body\n--- expected ---\n$MOCK_USAGE_FEED"
      }
      assert(resp.headers[HEADER_NEW_ENCRYPTED_REFRESH_BLOB] == null)
      assert(resp.headers[HEADER_NEW_PROXY_TOKEN] == null)
    }
  }

  test("surfaces rotated credentials via response headers (not the body)") {
    runProxyUsage(refreshTokenInResponse = "rt_rotated_value") { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.OK)
      val rotatedBlob =
        resp.headers[HEADER_NEW_ENCRYPTED_REFRESH_BLOB]
          ?: error("expected $HEADER_NEW_ENCRYPTED_REFRESH_BLOB header when refresh token rotated")
      val rotatedToken =
        resp.headers[HEADER_NEW_PROXY_TOKEN]
          ?: error("expected $HEADER_NEW_PROXY_TOKEN header when refresh token rotated")

      val crypto = TokenCrypto(ctx.config.crypto)
      val newBlob = crypto.decrypt(rotatedBlob)
      assert(newBlob.refreshToken == "rt_rotated_value")
      assert(newBlob.utilityId == ctx.utility.id)
      assert(newBlob.subscriptionUri == ctx.subscriptionUri)
      assert(crypto.deriveProxyToken(newBlob) == rotatedToken)

      // Body is still the upstream XML — credentials don't pollute it.
      assert(resp.bodyAsText() == MOCK_USAGE_FEED)
    }
  }

  test("preserves rotated credentials on an upstream failure (post-refresh error must not strand client)") {
    // Refresh rotates the (one-time) refresh token, then the resource server 500s. The rotated
    // blob MUST still come back so the client keeps its refreshed token and can retry instead of
    // being forced to re-authorize.
    runProxyUsage(
      resourceStatus = HttpStatusCode.InternalServerError,
      refreshTokenInResponse = "rt_rotated_value",
    ) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      // 500 from the resource server is now propagated verbatim (transient; client retries).
      assert(resp.status == HttpStatusCode.InternalServerError) { resp.bodyAsText() }
      assert(resp.bodyAsText().contains("utility_upstream_error")) { resp.bodyAsText() }
      val rotatedBlob =
        resp.headers[HEADER_NEW_ENCRYPTED_REFRESH_BLOB]
          ?: error("rotated blob header missing on upstream failure — client would be stranded")
      val crypto = TokenCrypto(ctx.config.crypto)
      assert(crypto.decrypt(rotatedBlob).refreshToken == "rt_rotated_value")
      assert(resp.headers[HEADER_NEW_PROXY_TOKEN] != null)
    }
  }

  test("401 invalid_credentials when the proxy token doesn't match the blob") {
    runProxyUsage { client, ctx ->
      val resp =
        client.postProxyUsage(presentedToken = "definitely-not-the-right-token", ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.Unauthorized) { resp.bodyAsText() }
      assert(resp.bodyAsText().contains("invalid_credentials"))
    }
  }

  test("401 utility_auth_expired when the utility rejects the refresh token") {
    runProxyUsage(tokenEndpointStatus = HttpStatusCode.Unauthorized) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.Unauthorized) { resp.bodyAsText() }
      assert(resp.bodyAsText().contains("utility_auth_expired"))
    }
  }

  // Refresh scope is decoupled from the authorize scope (UtilityProfile.refreshScope).
  test("refresh replays the granted blob scope when no refreshScope override is set") {
    var sent: String? = "UNSET"
    runProxyUsage(captureTokenRequest = { sent = scopeSentOnRefresh(it) }) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.OK) { resp.bodyAsText() }
    }
    // The harness seeds blob.scope = utility.defaultScope.
    assert(sent == "FB=1;IntervalDuration=900") { "expected granted blob scope on refresh; got $sent" }
  }

  test("refreshScope overrides the scope sent on the refresh grant") {
    var sent: String? = "UNSET"
    runProxyUsage(
      refreshScope = "FB=1",
      captureTokenRequest = { sent = scopeSentOnRefresh(it) },
    ) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.OK) { resp.bodyAsText() }
    }
    assert(sent == "FB=1") { "expected refreshScope override on refresh grant; got $sent" }
  }

  test("blank refreshScope omits the scope param on the refresh grant") {
    var sent: String? = "UNSET"
    runProxyUsage(
      refreshScope = "",
      captureTokenRequest = { sent = scopeSentOnRefresh(it) },
    ) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.OK) { resp.bodyAsText() }
    }
    assert(sent == null) { "expected no scope param when refreshScope is blank; got $sent" }
  }

  test("propagates the resource server's 5xx status verbatim (transient — client retries)") {
    runProxyUsage(resourceStatus = HttpStatusCode.InternalServerError) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      // Was collapsed to 502; now propagated so the client sees the real upstream status. Still a
      // 5xx, so the client's retry semantics are unchanged.
      assert(resp.status == HttpStatusCode.InternalServerError) { resp.bodyAsText() }
      assert(resp.bodyAsText().contains("utility_upstream_error"))
    }
  }

  test("propagates ANY real upstream status verbatim — never synthesizes 502 for a non-error status") {
    // Regression guard against re-introducing a "only propagate 4xx/5xx, else 502" range check.
    // The proxy must not second-guess a real upstream response: 502 is reserved for "couldn't get
    // a response at all" (the catch block). 201 is outside the old 400..599 range (and has no
    // redirect semantics that would confuse the test client), so it proves the guard is gone.
    runProxyUsage(resourceStatus = HttpStatusCode.Created) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.Created) { resp.bodyAsText() }
      assert(resp.status != HttpStatusCode.BadGateway) { resp.bodyAsText() }
    }
  }

  test("propagates a resource-server 4xx verbatim so the client won't retry a permanent failure") {
    // The real case: Burlington returns 403 access_denied for a resource our OAuth scope doesn't
    // cover (e.g. customer data). Collapsing that to 502 made the client loop forever; propagating
    // the 403 lets it recognize a permanent failure and stop. 502 is reserved for a genuine gateway
    // failure (no response from upstream), not a valid 403.
    runProxyUsage(resourceStatus = HttpStatusCode.Forbidden) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      assert(resp.status == HttpStatusCode.Forbidden) { resp.bodyAsText() }
      assert(resp.bodyAsText().contains("utility_upstream_error")) { resp.bodyAsText() }
      // The upstream status is still spelled out in the detail message for diagnostics.
      assert(resp.bodyAsText().contains("returned 403")) { resp.bodyAsText() }
    }
  }

  test("202 utility_data_pending when the resource server defers a large dataset") {
    runProxyUsage(resourceStatus = HttpStatusCode.Accepted) { client, ctx ->
      val resp = client.postProxyUsage(ctx.proxyToken, ctx.encryptedBlob)
      // Distinct from the generic 502 path: a utility 202 (async batch / "available later")
      // is passed through as 202 with a dedicated error key the HA client keys on.
      assert(resp.status == HttpStatusCode.Accepted) { resp.bodyAsText() }
      assert(resp.bodyAsText().contains("utility_data_pending")) { resp.bodyAsText() }
    }
  }

  test("400 invalid_request when publishedMin is sent as a JSON number instead of a string") {
    runProxyUsage { client, ctx ->
      val resp =
        client.post("/proxy/usage") {
          header(HttpHeaders.Authorization, "Bearer ${ctx.proxyToken}")
          contentType(ContentType.Application.Json)
          setBody("""{"encryptedRefreshBlob":"${ctx.encryptedBlob}","publishedMin":1708664400}""")
        }
      assert(resp.status == HttpStatusCode.BadRequest) { resp.bodyAsText() }
      val body = resp.bodyAsText()
      assert(body.contains("invalid_request")) { body }
      assert(body.contains("publishedMin", ignoreCase = true)) { body }
    }
  }

  test("forwards published-min/max to the resource server as ISO 8601 with Z suffix") {
    var capturedUrl: io.ktor.http.Url? = null
    runProxyUsage(captureResourceRequest = { capturedUrl = it.url }) { client, ctx ->
      val resp =
        client.postProxyUsage(
          ctx.proxyToken,
          ctx.encryptedBlob,
          publishedMin = Instant.parse("2024-02-23T05:00:00Z"),
          publishedMax = Instant.parse("2026-02-24T05:00:00Z"),
        )
      assert(resp.status == HttpStatusCode.OK)
    }
    val parsed = capturedUrl ?: error("resource server was not called")
    assert(parsed.parameters["published-min"] == "2024-02-23T05:00:00Z") { parsed.toString() }
    assert(parsed.parameters["published-max"] == "2026-02-24T05:00:00Z") { parsed.toString() }
  }

  test("a utility quirk sends updated-min/max instead of published-min/max") {
    // Mechanism test for UtilityQuirks.dateFilterParam — a per-utility, not client-driven,
    // override of the ESPI date-filter query-parameter base name. Motivated by (but NOT
    // currently used for) Burlington Hydro: a live probe initially looked like updated-min was
    // needed there, but a later, more careful probe showed updated-min returns the FULL history
    // regardless of window — not incremental — so Burlington does not set this quirk. See
    // UtilityQuirks.dateFilterParam's doc comment and docs/utilities/burlington-incremental-
    // issue.md for the full story. The mechanism itself is still real and tested here in case a
    // different utility genuinely needs it.
    var capturedUrl: io.ktor.http.Url? = null
    runProxyUsage(
      captureResourceRequest = { capturedUrl = it.url },
      quirks = UtilityQuirks(dateFilterParam = "updated"),
    ) { client, ctx ->
      val resp =
        client.postProxyUsage(
          ctx.proxyToken,
          ctx.encryptedBlob,
          publishedMin = Instant.parse("2024-02-23T05:00:00Z"),
          publishedMax = Instant.parse("2026-02-24T05:00:00Z"),
        )
      assert(resp.status == HttpStatusCode.OK) { resp.bodyAsText() }
    }
    val parsed = capturedUrl ?: error("resource server was not called")
    assert(parsed.parameters["updated-min"] == "2024-02-23T05:00:00Z") { parsed.toString() }
    assert(parsed.parameters["updated-max"] == "2026-02-24T05:00:00Z") { parsed.toString() }
    assert(parsed.parameters["published-min"] == null) { parsed.toString() }
    assert(parsed.parameters["published-max"] == null) { parsed.toString() }
  }

  test("a utility quirk wins over the client's (diagnostic-only) dateFilterParam") {
    var capturedUrl: io.ktor.http.Url? = null
    runProxyUsage(
      captureResourceRequest = { capturedUrl = it.url },
      quirks = UtilityQuirks(dateFilterParam = "updated"),
    ) { client, ctx ->
      // Client asks for "published"; the utility quirk must still win.
      val resp =
        client.postProxyUsage(
          ctx.proxyToken,
          ctx.encryptedBlob,
          publishedMin = Instant.parse("2024-02-23T05:00:00Z"),
          dateFilterParam = "published",
        )
      assert(resp.status == HttpStatusCode.OK) { resp.bodyAsText() }
    }
    val parsed = capturedUrl ?: error("resource server was not called")
    assert(parsed.parameters["updated-min"] == "2024-02-23T05:00:00Z") { parsed.toString() }
    assert(parsed.parameters["published-min"] == null) { parsed.toString() }
  }

  test("clamps a future published-max to now (custodians reject a future date with a 400)") {
    val farFuture = Instant.parse("2099-01-01T00:00:00Z")
    var capturedUrl: io.ktor.http.Url? = null
    runProxyUsage(captureResourceRequest = { capturedUrl = it.url }) { client, ctx ->
      val resp =
        client.postProxyUsage(
          ctx.proxyToken,
          ctx.encryptedBlob,
          publishedMin = Instant.parse("2024-02-23T05:00:00Z"),
          publishedMax = farFuture,
        )
      assert(resp.status == HttpStatusCode.OK)
    }
    val parsed = capturedUrl ?: error("resource server was not called")
    val sentMaxText = parsed.parameters["published-max"] ?: error("no published-max sent")
    assert(Instant.parse(sentMaxText) < farFuture) { "future published-max was not clamped: $sentMaxText" }
    // Whole-second precision only — Burlington's platform 400s a sub-second published-max.
    assert(!sentMaxText.contains(".")) { "clamped published-max carried sub-second precision: $sentMaxText" }
    // published-min (in the past) is passed through untouched, as ISO 8601.
    assert(parsed.parameters["published-min"] == "2024-02-23T05:00:00Z") { parsed.toString() }
  }
}

private data class ProxyUsageCtx(
  val config: AppConfig,
  val utility: UtilityProfile,
  val subscriptionUri: String,
  val refreshBlob: RefreshBlob,
  val encryptedBlob: String,
  val proxyToken: String,
)

// The refresh grant is an application/x-www-form-urlencoded POST (FormDataContent → ByteArrayContent);
// pull the `scope` form param the proxy actually sent to the token endpoint.
private fun scopeSentOnRefresh(request: HttpRequestData): String? {
  val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
  return parseQueryString(body)["scope"]
}

private val testJson = Json { encodeDefaults = false }

private suspend fun HttpClient.postProxyUsage(
  presentedToken: String,
  encryptedBlob: String,
  publishedMin: Instant? = null,
  publishedMax: Instant? = null,
  dateFilterParam: String? = null,
): HttpResponse =
  post("/proxy/usage") {
    header(HttpHeaders.Authorization, "Bearer $presentedToken")
    contentType(ContentType.Application.Json)
    setBody(
      testJson.encodeToString(
        ProxyUsageRequest(
          encryptedRefreshBlob = encryptedBlob,
          publishedMin = publishedMin,
          publishedMax = publishedMax,
          dateFilterParam = dateFilterParam,
        ),
      ),
    )
  }

@Suppress("LongMethod", "LongParameterList")
private fun runProxyUsage(
  tokenEndpointStatus: HttpStatusCode = HttpStatusCode.OK,
  resourceStatus: HttpStatusCode = HttpStatusCode.OK,
  // Default matches the blob's refresh token, so happy-path tests see no rotation.
  refreshTokenInResponse: String = "rt_mock_value",
  captureResourceRequest: ((HttpRequestData) -> Unit)? = null,
  captureTokenRequest: ((HttpRequestData) -> Unit)? = null,
  refreshScope: String? = null,
  quirks: UtilityQuirks = UtilityQuirks(),
  block: suspend (io.ktor.client.HttpClient, ProxyUsageCtx) -> Unit,
) {
  val subscriptionUri = "https://utility.mock/espi/1_1/resource/Batch/Subscription/42"
  val utility =
    UtilityProfile(
      id = "mock",
      displayName = "Mock Utility",
      authorizeUrl = "https://utility.mock/authorize",
      tokenUrl = "https://utility.mock/token",
      clientId = "client_id_xyz",
      clientSecret = Masked("client_secret_xyz"),
      defaultScope = "FB=1;IntervalDuration=900",
      refreshScope = refreshScope,
      tokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
      quirks = quirks,
    )

  val tokenResponseJson =
    """
    {"access_token":"at_mock","refresh_token":"$refreshTokenInResponse",
    "expires_in":3600,"token_type":"Bearer"}
    """.trimIndent()
  val mockHttp =
    HttpClient(MockEngine) {
      engine {
        addHandler { request ->
          handleMockRequest(
            request,
            tokenEndpointStatus,
            resourceStatus,
            tokenResponseJson,
            captureResourceRequest,
            captureTokenRequest,
          )
        }
      }
    }

  val config =
    AppConfig(
      server = ServerConfig(publicBaseUrl = "http://test.local"),
      crypto =
        CryptoConfig(
          aesKeyBase64 = Masked(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })),
          hmacPepperBase64 =
            Masked(
              Base64.getEncoder().encodeToString(ByteArray(32) { ((it + 1) * 11).toByte() }),
            ),
        ),
      state = StateConfig(),
      landing = LandingConfig(),
      utilities = listOf(utility),
    )
  val deps = buildAppDeps(config, mockHttp)
  val crypto = TokenCrypto(config.crypto)
  val refreshBlob =
    RefreshBlob(
      utilityId = utility.id,
      refreshToken = "rt_mock_value",
      subscriptionUri = subscriptionUri,
      scope = utility.defaultScope,
    )
  val ctx =
    ProxyUsageCtx(
      config = config,
      utility = utility,
      subscriptionUri = subscriptionUri,
      refreshBlob = refreshBlob,
      encryptedBlob = crypto.encrypt(refreshBlob),
      proxyToken = crypto.deriveProxyToken(refreshBlob),
    )

  runBlocking {
    testApplication {
      application { appModule(deps) }
      client.use { block(it, ctx) }
    }
  }
}

@Suppress("LongParameterList")
private fun MockRequestHandleScope.handleMockRequest(
  request: HttpRequestData,
  tokenEndpointStatus: HttpStatusCode,
  resourceStatus: HttpStatusCode,
  tokenResponseJson: String,
  captureResourceRequest: ((HttpRequestData) -> Unit)?,
  captureTokenRequest: ((HttpRequestData) -> Unit)?,
) = when {
  request.url.toString().startsWith("https://utility.mock/token") -> {
    captureTokenRequest?.invoke(request)
    if (tokenEndpointStatus == HttpStatusCode.OK) {
      respond(
        content = ByteReadChannel(tokenResponseJson),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    } else {
      respond(content = "utility rejected refresh", status = tokenEndpointStatus)
    }
  }
  request.url.toString().startsWith("https://utility.mock/espi/1_1/resource/Batch/Subscription/") -> {
    captureResourceRequest?.invoke(request)
    if (resourceStatus == HttpStatusCode.OK) {
      respond(
        content = ByteReadChannel(MOCK_USAGE_FEED),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"),
      )
    } else {
      respond(content = "utility resource server down", status = resourceStatus)
    }
  }
  else -> respondError(HttpStatusCode.NotImplemented)
}

// A tiny representative ESPI feed. The proxy never inspects it — these tests only assert
// the wire goes through verbatim — so it doesn't need to be schema-perfect, only deterministic
// for the round-trip equality check.
private val MOCK_USAGE_FEED =
  """
  <?xml version="1.0" encoding="UTF-8"?>
  <feed xmlns="http://www.w3.org/2005/Atom">
    <id>urn:uuid:test-feed</id>
    <title>Mock</title>
    <updated>2026-06-02T20:00:00Z</updated>
    <entry xmlns:espi="http://naesb.org/espi">
      <id>urn:uuid:up1</id>
      <link rel="self" href="https://utility.mock/UsagePoint/up1"/>
      <content><espi:UsagePoint><espi:ServiceCategory><espi:kind>0</espi:kind></espi:ServiceCategory><espi:status>1</espi:status></espi:UsagePoint></content>
    </entry>
  </feed>
  """.trimIndent()
