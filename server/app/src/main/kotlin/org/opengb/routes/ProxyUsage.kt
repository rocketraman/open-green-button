package org.opengb.routes

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.apache.logging.log4j.kotlin.logger
import org.apache.logging.log4j.message.StringMapMessage
import org.opengb.AppDeps
import org.opengb.oauth.OAuthException
import org.opengb.proxy.BlobDecryptionException
import org.opengb.proxy.RefreshBlob
import org.opengb.proxy.TokenCrypto
import org.opengb.proxy.UsageClient
import org.opengb.utility.RefreshScope
import org.opengb.utility.UnknownUtilityException
import org.opengb.utility.UtilityProfile
import org.opengb.utility.UtilityRegistry
import kotlin.time.Instant

/**
 * `POST /proxy/usage` — pure streaming pass-through.
 *
 * The proxy decrypts the refresh blob, refreshes the access token at the utility's token
 * endpoint, GETs the subscription URI, and streams the upstream Atom feed body **byte for
 * byte** into the response. No XML parsing, no normalization — those happen on the HA
 * client. This keeps the proxy's memory footprint at O(network buffer) regardless of
 * how much history the utility returns.
 *
 * Rotated credentials (RFC 6749 §6) are surfaced via response **headers** rather than a
 * JSON envelope:
 *
 *   - `OpenGB-New-Encrypted-Refresh-Blob`
 *   - `OpenGB-New-Proxy-Token`
 *
 * The HA client checks for these on every successful response and updates its config entry
 * when present.
 */
private val proxyLog = logger("opengb.proxy")

fun Application.installProxyUsage(
  deps: AppDeps,
  usageClient: UsageClient,
) {
  routing {
    post("/proxy/usage") { handleProxyUsage(deps, usageClient) }
    post("/proxy/customer") { handleProxyCustomer(deps, usageClient) }
  }
}

@Serializable
data class ProxyUsageRequest(
  val encryptedRefreshBlob: String,
  /** ESPI `published-min` filter. JSON wire form is ISO 8601 with `Z` suffix, e.g.
   *  `2026-02-24T05:00:00Z` — kotlinx-serialization's built-in [Instant] serializer
   *  parses/emits that format. */
  val publishedMin: Instant? = null,
  /** ESPI `published-max` filter — same wire format. */
  val publishedMax: Instant? = null,
  /**
   * DIAGNOSTIC override of the ESPI date-filter query-parameter base name. Default (`null`) sends
   * `published-min`/`published-max`; set e.g. `"updated"` to send `updated-min`/`updated-max`.
   * Exists to probe what a non-conforming Data Custodian actually accepts without a redeploy per
   * experiment. The normal HA client never sends it. A confirmed-correct value for a utility
   * belongs in [org.opengb.utility.UtilityQuirks.dateFilterParam] instead — that always takes
   * precedence over this field (see [streamResource]).
   */
  val dateFilterParam: String? = null,
  /**
   * RELATIVE ESPI resource suffix to fetch instead of the subscription itself, e.g. `UsagePoint`
   * or `UsagePoint/{id}`. Joined onto the subscription URI from the caller's own encrypted blob.
   *
   * Exists because an asynchronous-batch custodian answers the subscription-level batch URL with
   * 202 forever — it is an enqueue endpoint, not a readable one — and notifies the prepared data
   * at a per-UsagePoint URL underneath it. Only the client knows which resources it wants and how
   * to parse them, so it names the suffix and this stays a pass-through: no parsing, no
   * aggregation, no per-utility knowledge.
   *
   * Deliberately a SUFFIX and not a URL. The proxy attaches the user's utility access token to
   * whatever it fetches, so accepting a caller-supplied absolute URL would make this endpoint an
   * authenticated SSRF gadget. Constraining it to a relative path under the subscription URI —
   * which comes from the encrypted blob, never from the request body — means a caller can only
   * ever reach resources inside its own authorization. See [RESOURCE_PATH_REGEX].
   */
  val resourcePath: String? = null,
)

const val HEADER_NEW_ENCRYPTED_REFRESH_BLOB: String = "OpenGB-New-Encrypted-Refresh-Blob"
const val HEADER_NEW_PROXY_TOKEN: String = "OpenGB-New-Proxy-Token"

/** The utility + access token to fetch a resource with, plus the ESPI resource pointers other
 *  resources (e.g. customer data) are located from. Produced by [prepareFetch]. */
private data class RefreshedFetch(
  val utility: UtilityProfile,
  val subscriptionUri: String,
  /** The ESPI Authorization resource URL captured at token exchange. GETting it yields the
   *  `customerResourceURI` that points at the customer-data batch. Null for older blobs. */
  val authorizationUri: String?,
  val accessToken: String,
)

/**
 * Shared prologue for the resource-proxy endpoints: authenticate the proxy token, refresh the
 * utility access token, and emit the rotated-credentials headers. Returns the refreshed context, or
 * null after already responding with the appropriate error.
 */
@Suppress("ReturnCount") // sequential guard clauses, each responding with its own error before bailing
private suspend fun RoutingContext.prepareFetch(
  deps: AppDeps,
  request: ProxyUsageRequest,
): RefreshedFetch? {
  val presentedToken =
    call.bearerToken() ?: run {
      call.respondError(HttpStatusCode.Unauthorized, "missing_bearer_token")
      return null
    }
  val blob = call.decryptBlob(deps.crypto, request.encryptedRefreshBlob) ?: return null
  if (!deps.crypto.verifyProxyToken(blob, presentedToken)) {
    call.respondError(HttpStatusCode.Unauthorized, "invalid_credentials")
    return null
  }
  val utility = call.resolveUtility(deps.registry, blob.utilityId) ?: return null
  val subscriptionUri = blob.subscriptionUri
  if (subscriptionUri.isNullOrBlank()) {
    call.respondError(HttpStatusCode.BadRequest, "no_subscription_uri")
    return null
  }

  // Resolve the refresh-grant scope from the per-utility mode (see UtilityProfile.refreshScope /
  // RefreshScope). Granted replays what the utility gave us at exchange (blob.scope); a null there
  // ⇒ omit, per RFC 6749 §6. OAuthClient.refresh omits the param when this is null.
  val refreshScope =
    when (val mode = utility.refreshScope) {
      RefreshScope.Omit -> null
      RefreshScope.Granted -> blob.scope
      is RefreshScope.Explicit -> mode.scope
    }
  val refreshed = call.refreshAccessToken(deps, utility, blob.refreshToken, refreshScope) ?: return null

  // The refresh may have redeemed a ONE-TIME refresh token (savagedata/OpenIddict), invalidating the
  // blob the client holds. Emit the rotated credentials NOW — before the resource fetch — so they
  // reach the client on *every* outcome, not just success; otherwise a post-refresh failure strands
  // the client with a dead refresh token. (Headers set here commit with whatever response we send.)
  val newCredentials = rotatedCredentials(deps.crypto, blob, refreshed.refreshToken)
  newCredentials?.let {
    call.response.header(HEADER_NEW_ENCRYPTED_REFRESH_BLOB, it.encryptedRefreshBlob)
    call.response.header(HEADER_NEW_PROXY_TOKEN, it.proxyToken)
  }
  return RefreshedFetch(utility, subscriptionUri, blob.authorizationUri, refreshed.accessToken)
}

private suspend fun RoutingContext.handleProxyUsage(
  deps: AppDeps,
  usageClient: UsageClient,
) {
  val request = call.parseRequest() ?: return
  // Validated BEFORE prepareFetch: that call refreshes the utility access token, and for a
  // custodian issuing one-time refresh tokens (savagedata/OpenIddict) the refresh BURNS the
  // client's stored token. A malformed request must not cost the caller its credentials.
  if (!isSafeResourcePath(request.resourcePath)) {
    return call.respondError(
      HttpStatusCode.BadRequest,
      "invalid_resource_path",
      "resourcePath must be a relative ESPI resource suffix such as `UsagePoint` or " +
        "`UsagePoint/{id}` — absolute URLs and traversal are rejected",
    )
  }
  val fetch = prepareFetch(deps, request) ?: return
  val resourceUri = resourceUriFor(fetch.subscriptionUri, request.resourcePath)
  call.streamResource(usageClient, fetch.utility, resourceUri, fetch.accessToken, request)
}

/**
 * Short, ESPI-shaped path segments only — letters, digits and hyphens, e.g. `UsagePoint` and
 * `UsagePoint/1c8dc9de-1c8f-5b47-9a35-4c98e6bd1ce1`.
 *
 * The rejections are the point: no scheme or authority (`:` and `//` are unmatched), no traversal
 * (`.` is unmatched, so `..` cannot appear), no absolute path (a leading `/` is unmatched), no
 * query or fragment (`?` and `#` are unmatched), and no percent-encoding (`%` is unmatched, so
 * `%2e%2e` can't smuggle traversal past this and get decoded downstream). Segment counts and
 * lengths are bounded so a caller can't build an unreasonable URL out of legal characters.
 */
private val RESOURCE_PATH_REGEX =
  Regex("""[A-Za-z][A-Za-z0-9]{0,31}(?:/[A-Za-z0-9][A-Za-z0-9-]{0,63}){0,3}""")

private fun isSafeResourcePath(resourcePath: String?): Boolean =
  resourcePath == null || RESOURCE_PATH_REGEX.matches(resourcePath)

/** The subscription URI itself, or the validated suffix joined beneath it. */
private fun resourceUriFor(
  subscriptionUri: String,
  resourcePath: String?,
): String =
  if (resourcePath.isNullOrEmpty()) {
    subscriptionUri
  } else {
    "${subscriptionUri.trimEnd('/')}/$resourcePath"
  }

private suspend fun RoutingContext.handleProxyCustomer(
  deps: AppDeps,
  usageClient: UsageClient,
) {
  val request = call.parseRequest() ?: return
  val fetch = prepareFetch(deps, request) ?: return
  val customerUri =
    resolveCustomerUri(usageClient, fetch) ?: return call.respondError(
      HttpStatusCode.BadRequest,
      "no_customer_uri",
      "the ESPI Authorization resource advertised no customerResourceURI and no customer URL " +
        "could be derived from ${fetch.subscriptionUri}",
    )
  // Customer data is a snapshot resource — the ESPI date-range filters don't apply, so strip them.
  call.streamResource(
    usageClient,
    fetch.utility,
    customerUri,
    fetch.accessToken,
    request.copy(publishedMin = null, publishedMax = null, dateFilterParam = null),
  )
}

/**
 * Locate the customer-data resource for this authorization.
 *
 * The spec-defined source is the ESPI **Authorization resource** (whose URL we captured at token
 * exchange): it advertises a `<customerResourceURI>` alongside the usage `<resourceURI>`. We GET it
 * and read that field — vendor-agnostic, no URL guessing. Only if the custodian doesn't populate
 * `customerResourceURI` (non-conformant) do we fall back to deriving the URL from the subscription
 * URI by swapping the ESPI batch segment.
 */
private suspend fun resolveCustomerUri(
  client: UsageClient,
  fetch: RefreshedFetch,
): String? {
  val advertised =
    fetch.authorizationUri?.let { authUri ->
      runCatching {
        val response = client.getResource(fetch.utility, authUri, fetch.accessToken)
        if (response.status == HttpStatusCode.OK) {
          extractCustomerResourceUri(response.bodyAsText())
        } else {
          null
        }
      }.getOrNull()
    }
  return advertised ?: customerUriFrom(fetch.subscriptionUri)
}

// Namespace-prefix-agnostic extraction of the ESPI Authorization resource's <customerResourceURI>.
// The Authorization resource is a few KB, so a regex over its text avoids pulling an XML parser into
// the otherwise parse-free proxy.
private val CUSTOMER_RESOURCE_URI_REGEX =
  Regex("""<(?:\w+:)?customerResourceURI>\s*([^<]+?)\s*</(?:\w+:)?customerResourceURI>""")

private fun extractCustomerResourceUri(authorizationXml: String): String? =
  CUSTOMER_RESOURCE_URI_REGEX
    .find(authorizationXml)
    ?.groupValues
    ?.get(1)
    ?.trim()
    ?.takeIf { it.isNotBlank() }

private const val BATCH_SUBSCRIPTION_SEGMENT = "/Batch/Subscription/"
private const val BATCH_RETAIL_CUSTOMER_SEGMENT = "/Batch/RetailCustomer/"

private fun customerUriFrom(subscriptionUri: String): String? =
  if (subscriptionUri.contains(BATCH_SUBSCRIPTION_SEGMENT)) {
    subscriptionUri.replace(BATCH_SUBSCRIPTION_SEGMENT, BATCH_RETAIL_CUSTOMER_SEGMENT)
  } else {
    null
  }

private fun ApplicationCall.bearerToken(): String? {
  val header = request.headers["Authorization"]?.trim() ?: return null
  if (!header.startsWith("Bearer ", ignoreCase = true)) return null
  return header.substring("Bearer ".length).trim().takeIf { it.isNotEmpty() }
}

private suspend fun ApplicationCall.parseRequest(): ProxyUsageRequest? =
  try {
    receive<ProxyUsageRequest>()
  } catch (e: BadRequestException) {
    // ContentNegotiation wraps the converter's failure (kotlinx-serialization throwing on
    // a malformed body, missing required field, or type mismatch like a JSON number where
    // an Instant was expected) in BadRequestException — the original SerializationException
    // is the `cause`. Surface its message so the client sees what was actually wrong.
    respondError(HttpStatusCode.BadRequest, "invalid_request", e.cause?.message ?: e.message)
    null
  }

private suspend fun ApplicationCall.decryptBlob(
  crypto: TokenCrypto,
  encrypted: String,
): RefreshBlob? =
  try {
    crypto.decrypt(encrypted)
  } catch (e: BlobDecryptionException) {
    respondError(HttpStatusCode.BadRequest, "invalid_blob", e.message)
    null
  }

private suspend fun ApplicationCall.resolveUtility(
  registry: UtilityRegistry,
  utilityId: String,
): UtilityProfile? =
  try {
    registry.require(utilityId)
  } catch (_: UnknownUtilityException) {
    // The blob's utility id refers to a profile the server no longer knows. Treat as a
    // permanent state mismatch — HA should re-run the connect flow against a current utility.
    respondError(HttpStatusCode.BadRequest, "unknown_utility", "utility_id=$utilityId")
    null
  }

private data class RefreshOutcome(val accessToken: String, val refreshToken: String?)

private suspend fun ApplicationCall.refreshAccessToken(
  deps: AppDeps,
  utility: UtilityProfile,
  refreshToken: String,
  scope: String?,
): RefreshOutcome? =
  try {
    val tokens = deps.oauth.refresh(utility, refreshToken, scope)
    RefreshOutcome(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
  } catch (e: OAuthException) {
    // Diagnostic: since we can't drive a utility's OAuth ourselves without its end-user
    // credentials, this is our only window onto what a live custodian (Kentucky Utilities) does on
    // refresh — the exact scope we sent and the exact body it rejected with. Neither is a secret.
    connectLog.warn(
      "Refresh failed for utility=${utility.id} scopeSent=${scope ?: "(omitted)"}: ${e.message}",
    )
    // Distinguish WHOSE credential the token endpoint rejected (RFC 6749 §5.2 maps token-endpoint
    // failures to distinct HTTP statuses), because they need opposite responses:
    //
    //   401 invalid_client — OUR clientId/clientSecret failed to authenticate. A token endpoint
    //     returns 401 *specifically* for client-auth failure; an expired/revoked refresh token is
    //     `invalid_grant` → 400, never 401. No user action can fix this — it strands every account
    //     on the utility — so it must NOT drive an HA reauth. Surface a distinct server-side error
    //     and log loudly for the operator; HA treats the non-`utility_auth_expired` response as a
    //     transient failure and retries, so it self-heals once the misconfigured secret is fixed.
    //
    //   400 invalid_grant / 403 access_denied — the resource owner's grant is gone (expired,
    //     revoked, or scope changed). HA should observe `utility_auth_expired` and reauth.
    //
    //   5xx or no status — upstream transient; HA should retry, not reauth.
    when {
      e.statusCode == HTTP_UNAUTHORIZED -> {
        connectLog.error(
          "Client authentication REJECTED by utility=${utility.id} token endpoint (HTTP 401 " +
            "invalid_client). This is a SERVER-SIDE misconfiguration — verify the configured " +
            "clientId/clientSecret for this utility — not a user reauth condition.",
        )
        respondError(HttpStatusCode.BadGateway, "utility_client_auth_failed", e.message)
      }
      e.statusCode in GRANT_REJECTED_STATUSES ->
        respondError(HttpStatusCode.Unauthorized, "utility_auth_expired", e.message)
      else ->
        respondError(HttpStatusCode.BadGateway, "utility_upstream_error", e.message)
    }
    null
  }

private data class NewCredentials(val encryptedRefreshBlob: String, val proxyToken: String)

private fun rotatedCredentials(
  crypto: TokenCrypto,
  oldBlob: RefreshBlob,
  newRefreshToken: String?,
): NewCredentials? {
  if (newRefreshToken == null || newRefreshToken == oldBlob.refreshToken) return null
  val updated = oldBlob.copy(refreshToken = newRefreshToken)
  return NewCredentials(
    encryptedRefreshBlob = crypto.encrypt(updated),
    proxyToken = crypto.deriveProxyToken(updated),
  )
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ApplicationCall.streamResource(
  client: UsageClient,
  utility: UtilityProfile,
  subscriptionUri: String,
  accessToken: String,
  request: ProxyUsageRequest,
) {
  // TRUE zero-copy streaming: run the whole response inside the client's `execute { }` block (so the
  // upstream body channel is never buffered), and respond with a pull-based [ByteReadChannelContent].
  // The engine consumes that channel *as part of* `respond(...)`, so the copy finishes before the
  // block returns — the upstream stays alive throughout, and memory is O(engine buffer), not O(feed).
  // (The no-block `execute()` reads the whole body into memory → OOM on a multi-MB ESPI feed;
  // `respondBytesWriter` defers its producer until after the block returns → ClosedByteChannelException.)
  var responseStarted = false
  try {
    client
      .fetch(
        utility = utility,
        subscriptionUri = subscriptionUri,
        accessToken = accessToken,
        publishedMin = request.publishedMin,
        publishedMax = request.publishedMax,
        // A confirmed per-utility quirk always wins over the client's diagnostic-only
        // override — see [UtilityQuirks.dateFilterParam]. The HA client never sets this, so
        // in practice this is utility.quirks.dateFilterParam or the spec-default `published`.
        dateFilterParam = utility.quirks.dateFilterParam ?: request.dateFilterParam,
      ).execute { upstream ->
        when {
          upstream.status == HttpStatusCode.Accepted -> handleUpstreamAccepted(upstream)
          upstream.status != HttpStatusCode.OK -> handleUpstreamFailure(upstream)
          else -> {
            val upstreamContentType =
              upstream.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) } ?: ESPI_ATOM_XML
            responseStarted = true
            respond(ByteReadChannelContent(upstream.bodyAsChannel(), upstreamContentType))
          }
        }
      }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    // A failure BEFORE we started responding (timeout / connection reset / TLS) is a clean, retryable
    // upstream error — the rotated-credentials headers set by the caller ride along, so the client
    // keeps its refreshed token. Once streaming has begun the headers are committed, so we can't send
    // an error body; let the exception surface as a truncated stream.
    if (responseStarted) throw e
    respondError(
      HttpStatusCode.BadGateway,
      "utility_upstream_error",
      "Resource fetch failed for $subscriptionUri: ${e.message}",
    )
  }
}

private suspend fun ApplicationCall.handleUpstreamAccepted(upstream: HttpResponse) {
  // ESPI asynchronous batch delivery: the utility accepted the request but is assembling the
  // dataset out-of-band. Per spec it later POSTs an ESPI Notification (a BatchList of resource
  // URIs) to our registered NotificationURI — which we currently discard (see Notify.kt). Until
  // that retrieval flow exists, surface a DISTINCT, machine-readable signal — passing the
  // utility's 202 semantics through with a dedicated `utility_data_pending` error key — so the HA
  // client can guide the user instead of looping on a generic upstream error.
  //
  // Capture the WHOLE response, exactly as [handleUpstreamFailure] does for an error. A 202 body
  // is a status document, not a feed, so reading it costs nothing and there is nothing to stream.
  // This is the only window we have onto what a live async custodian actually says: it is not
  // reproducible without a real authorization at a custodian that defers, and Alectra (the one
  // confirmed case, github.com/rocketraman/open-green-button-homeassistant/issues/10) is
  // production-only. Whether the 202 names the prepared batch's URL decides the whole fix: if it
  // does, the client can hand that URL back on the next poll and we never need to correlate an
  // out-of-band notification to a subscription — the proxy stays stateless. If it doesn't,
  // consuming the BatchList (and giving the proxy real storage) is the only way through.
  val body = upstream.bodyAsText().take(MAX_UPSTREAM_ERROR_SNIPPET)
  val responseHeaders =
    upstream.headers.entries().joinToString(", ") { (name, values) -> "$name: ${values.joinToString(",")}" }
  // Called out separately from the header dump because these are the decisive fields — worth
  // being greppable on their own rather than buried in a header blob.
  val batchLocation = upstream.headers[HttpHeaders.Location] ?: upstream.headers["Content-Location"]
  proxyLog.info(
    StringMapMessage().apply {
      put("espi.async_batch.request_url", upstream.call.request.url.toString())
      put("http.response.status_code", upstream.status.value.toString())
      put("http.response.headers", responseHeaders)
      batchLocation?.let { put("espi.async_batch.location", it) }
      upstream.headers[HttpHeaders.RetryAfter]?.let { put("http.response.retry_after", it) }
      if (body.isNotBlank()) put("http.response.body", body)
    },
  )
  respondError(
    HttpStatusCode.Accepted,
    "utility_data_pending",
    "Utility returned 202 Accepted for ${upstream.call.request.url}: the dataset is being " +
      "prepared asynchronously and background (async batch) delivery is not yet supported | " +
      "response-headers: [$responseHeaders] | body: $body",
  )
}

private suspend fun ApplicationCall.handleUpstreamFailure(upstream: HttpResponse) {
  val body = upstream.bodyAsText().take(MAX_UPSTREAM_ERROR_SNIPPET)
  // Forward the DC's RAW response detail — status, headers, and body — so an upstream failure is
  // diagnosable without a live reproduction. The full URL (including any date filter we appended)
  // shows exactly what we asked for; the response headers carry the real signal when the body is
  // empty (e.g. savagedata returns a bare 400, but an `x-response-time-ms`/`server` header proves
  // the request reached their app rather than being bounced at the edge).
  val responseHeaders =
    upstream.headers.entries().joinToString(", ") { (name, values) -> "$name: ${values.joinToString(",")}" }
  // PROPAGATE the resource server's own status VERBATIM. If upstream gave us a real HTTP response
  // it is not a gateway failure — it's data the client is entitled to act on with standard HTTP
  // semantics (4xx = permanent, don't retry — e.g. Burlington's 403 access_denied; 5xx = transient,
  // retry). We used to collapse everything to 502, which hid that and made the client loop forever
  // on permanent failures. 502 Bad Gateway now means ONLY what it says — we couldn't get a valid
  // response from upstream at all (connection/timeout/TLS) — and is emitted solely from
  // streamResource's catch block, never here.
  respondError(
    upstream.status,
    "utility_upstream_error",
    "Resource server returned ${upstream.status.value} for ${upstream.call.request.url} | " +
      "response-headers: [$responseHeaders] | body: $body",
  )
}

private suspend fun ApplicationCall.respondError(
  status: HttpStatusCode,
  error: String,
  message: String? = null,
) = respond(status, ErrorBody(error = error, message = message))

// Token-endpoint statuses that mean "the resource owner's GRANT is no good" (expired, revoked, or
// scope mismatch) → the user must re-authorize. RFC 6749 §5.2 maps these to invalid_grant (400) and
// access_denied (403). 401 is deliberately EXCLUDED: a 401 from the token endpoint is
// `invalid_client` — OUR client credentials failed — a server-side problem no reauth can fix. It is
// handled separately in [refreshAccessToken].
@Suppress("MagicNumber")
private val GRANT_REJECTED_STATUSES = setOf(400, 403)

@Suppress("MagicNumber")
private const val HTTP_UNAUTHORIZED = 401

private val ESPI_ATOM_XML = ContentType("application", "atom+xml")
private const val MAX_UPSTREAM_ERROR_SNIPPET = 500
