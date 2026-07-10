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
import org.opengb.AppDeps
import org.opengb.oauth.OAuthException
import org.opengb.proxy.BlobDecryptionException
import org.opengb.proxy.RefreshBlob
import org.opengb.proxy.TokenCrypto
import org.opengb.proxy.UsageClient
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

  val refreshed = call.refreshAccessToken(deps, utility, blob.refreshToken, blob.scope) ?: return null

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
  val fetch = prepareFetch(deps, request) ?: return
  call.streamResource(usageClient, fetch.utility, fetch.subscriptionUri, fetch.accessToken, request)
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
    // 4xx on the token endpoint = utility rejected our refresh token (expired, revoked,
    // scope changed). HA should observe `utility_auth_expired` and trigger the reauth flow.
    // 5xx or no status = upstream transient — HA should retry, not reauth.
    val authRejected = e.statusCode in AUTH_REJECTED_STATUSES
    val status = if (authRejected) HttpStatusCode.Unauthorized else HttpStatusCode.BadGateway
    val errorKey = if (authRejected) "utility_auth_expired" else "utility_upstream_error"
    respondError(status, errorKey, e.message)
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
  // ESPI asynchronous batch delivery: the utility accepted the request but the dataset is
  // large enough that it's being assembled out-of-band. Per spec it will later POST an ESPI
  // Notification (a BatchList of resource URIs) to our registered NotificationURI — which we
  // currently discard (see Notify.kt). Until that retrieval flow exists, surface a DISTINCT,
  // machine-readable signal — passing the utility's 202 semantics through with a dedicated
  // `utility_data_pending` error key — so the HA client can guide the user instead of looping
  // on a generic upstream error.
  respondError(
    HttpStatusCode.Accepted,
    "utility_data_pending",
    "Utility returned 202 Accepted for ${upstream.call.request.url}: the dataset is being " +
      "prepared asynchronously and background (async batch) delivery is not yet supported",
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

// HTTP status codes from the utility token endpoint that mean "your refresh token is no
// good." RFC 6749 §5.2 maps these to invalid_grant/invalid_client/access_denied.
@Suppress("MagicNumber")
private val AUTH_REJECTED_STATUSES = setOf(400, 401, 403)

private val ESPI_ATOM_XML = ContentType("application", "atom+xml")
private const val MAX_UPSTREAM_ERROR_SNIPPET = 500
