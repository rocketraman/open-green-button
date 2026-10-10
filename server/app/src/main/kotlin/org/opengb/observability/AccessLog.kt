package org.opengb.observability

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import org.apache.logging.log4j.kotlin.logger
import org.apache.logging.log4j.kotlin.withLoggingContext
import java.net.InetAddress

private val accessLog = logger("opengb.access")

/**
 * One structured log entry per request, emitted at the end of the pipeline.
 *
 * Field placement:
 *  - **Per-event fields** (method, path, status, duration, client network, user agent) live in
 *    the [AccessLogMessage] MapMessage payload and reach the JSON output via the `map` resolver.
 *  - **Request-scoped fields** (`http.request.id`, `trace.id`) live in ThreadContext for the
 *    duration of the request via [withLoggingContext], so any *other* log statement emitted
 *    during the request (e.g. an OAuth token-exchange warning) inherits them automatically.
 *    They reach the JSON output via the `mdc` resolver.
 *
 * Two things are deliberately NOT recorded, because the platform retains these lines:
 *  - the **query string** — on the OAuth callback it carries the utility's one-time authorization
 *    `code`, a credential, and nothing in it is needed to read an access log;
 *  - the caller's **full IP address** — see [anonymizeIp].
 *
 * Probes (`/health`, `/ready`) and the Fly metrics scrape (`/metrics`) are skipped so they don't
 * fill the log stream with noise.
 */
fun Application.installAccessLog() {
  intercept(ApplicationCallPipeline.Setup) {
    val path = call.request.local.uri.substringBefore('?')
    if (path == "/health" || path == "/ready" || path == "/metrics") {
      proceed()
      return@intercept
    }

    val start = System.nanoTime()
    val traceId = call.request.headers["Fly-Trace-Id"]
    val requestId = call.callId

    val contextMap: Map<String, String> =
      buildMap {
        requestId?.let { put("http.request.id", it) }
        traceId?.let { put("trace.id", it) }
      }

    // withLoggingContext is coroutine-aware — the ThreadContext entries are preserved
    // across suspension points inside proceed().
    withLoggingContext(contextMap) {
      try {
        proceed()
      } finally {
        AccessLogMessage(
          method = call.request.local.method.value,
          path = path,
          status = call.response.status()?.value ?: 0,
          durationNanos = System.nanoTime() - start,
          clientIp = anonymizeIp(call.request.headers["Fly-Client-IP"] ?: call.request.local.remoteHost),
          userAgent = call.request.headers["User-Agent"],
        ).log()
      }
    }
  }
}

/**
 * One structured log entry for an HTTP request. Field names follow ECS schema where possible so
 * downstream tooling (Grafana, OpenSearch with ECS templates, etc.) understands them out of the
 * box. `trace.id` and `http.request.id` are intentionally *not* fields on this class — they
 * come from ThreadContext set by [installAccessLog].
 */
class AccessLogMessage(
  val method: String,
  val path: String,
  val status: Int,
  val durationNanos: Long,
  val clientIp: String?,
  val userAgent: String?,
) : StructuredLogMessage() {
  init {
    field("http.request.method", method)
    field("url.path", path)
    field("http.response.status_code", status)
    field("event.duration", durationNanos)
    field("client.ip", clientIp)
    field("user_agent.original", userAgent)
  }

  override val humanMessage: String
    get() = "$method $path -> $status"

  fun log() = accessLog.info(this)
}

/**
 * The network [address] belongs to, with the host part zeroed: a /24 for IPv4 (`203.0.113.7` →
 * `203.0.113.0`) and a /48 for IPv6 — the smallest block an ISP normally hands one subscriber.
 *
 * A Home Assistant install calls from its owner's home connection, so the full address is a
 * handle on a household. The network is enough for what an access log is for — telling callers
 * apart roughly, spotting one that misbehaves — without recording which household it was. Null
 * for anything that is not an IP literal (never resolved: a lookup here would be a DNS query per
 * request).
 */
internal fun anonymizeIp(address: String?): String? {
  val candidate = address?.trim().orEmpty()
  IPV4_REGEX.matchEntire(candidate)?.let { return "${it.groupValues[1]}.0" }
  if (!IPV6_LITERAL_REGEX.matches(candidate)) return null
  val bytes = runCatching { InetAddress.getByName(candidate).address }.getOrNull() ?: return null
  if (bytes.size != IPV6_BYTES) return null
  bytes.fill(0, IPV6_NETWORK_BYTES, IPV6_BYTES)
  return InetAddress.getByAddress(bytes).hostAddress
}

private val IPV4_REGEX = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3})\.\d{1,3}""")

// Hex groups and colons only, with at least one colon: `InetAddress.getByName` parses that as a
// literal and never as a host name, so it cannot trigger a lookup.
private val IPV6_LITERAL_REGEX = Regex("""(?=.*:)[0-9a-fA-F:]+""")
private const val IPV6_BYTES = 16
private const val IPV6_NETWORK_BYTES = 6
