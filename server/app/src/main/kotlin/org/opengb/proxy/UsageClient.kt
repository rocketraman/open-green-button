package org.opengb.proxy

import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import org.opengb.http.UtilityHttpClients
import org.opengb.utility.DateFilterFormat
import org.opengb.utility.UtilityProfile
import java.time.LocalDate
import kotlin.time.Instant

/**
 * Prepares a streaming GET against an ESPI resource server — typically the
 * `subscription_uri` returned in the OAuth token response, e.g.
 * `https://.../espi/1_1/resource/Batch/Subscription/<uuid>`.
 *
 * Returns an unfinalized [HttpStatement] rather than the body itself; the caller is expected
 * to `.execute { response -> ... }` and stream `response.bodyAsChannel()` directly into the
 * outbound proxy response without materializing it in memory. This is what lets the proxy
 * stay tiny even when a utility returns multi-MB Atom feeds.
 */
class UsageClient(private val clients: UtilityHttpClients) {
  @Suppress("LongParameterList")
  suspend fun fetch(
    utility: UtilityProfile,
    subscriptionUri: String,
    accessToken: String,
    publishedMin: Instant? = null,
    publishedMax: Instant? = null,
    dateFilterParam: String? = null,
  ): HttpStatement {
    // Base name of the ESPI date-range query params (`published` → published-min/published-max).
    // Overridable per request for diagnosing a non-conforming custodian (e.g. `updated`).
    val base = dateFilterParam?.takeIf { it.isNotBlank() } ?: "published"
    // Clamp published-max to now: no utility publishes future-dated readings, and savagedata
    // rejects a future published-max with a bare 400 (the Home Assistant client sends now + a
    // 1-day lookahead margin, which is what tripped this). Dropped entirely for a custodian that
    // discards it when canonicalizing an async batch request — see UtilityQuirks.sendsPublishedMax.
    val effectiveMax =
      publishedMax?.takeIf { utility.quirks.sendsPublishedMax }?.let { minOf(it, nowInstant()) }
    val format = utility.quirks.dateFilterFormat
    val url =
      URLBuilder(subscriptionUri)
        .apply {
          publishedMin?.let { parameters.append("$base-min", format.render(it)) }
          effectiveMax?.let { parameters.append("$base-max", format.render(it)) }
        }.buildString()

    return clients.forUtility(utility).prepareGet(url) {
      headers {
        append(HttpHeaders.Authorization, "Bearer $accessToken")
        append(HttpHeaders.Accept, "application/atom+xml, application/xml")
      }
    }
  }

  /**
   * Buffered GET of a *small* ESPI resource (e.g. the Authorization resource, whose
   * `customerResourceURI` tells us where the customer-data batch lives). Unlike [fetch], this
   * reads the whole body into memory — safe because these metadata resources are a few KB, not
   * the multi-MB usage feeds [fetch] streams. Returns the raw [HttpResponse]; the caller inspects
   * the status and reads `bodyAsText()`.
   */
  suspend fun getResource(
    utility: UtilityProfile,
    url: String,
    accessToken: String,
  ): HttpResponse =
    clients.forUtility(utility).get(url) {
      headers {
        append(HttpHeaders.Authorization, "Bearer $accessToken")
        append(HttpHeaders.Accept, "application/atom+xml, application/xml")
      }
    }

  // Whole-second precision, on purpose: Burlington's Green Button platform rejects a published-max
  // that carries sub-second precision with a bare 400, and the raw millis from currentTimeMillis()
  // would leak into the ISO string (…:31.383Z) when we clamp a future max down to now.
  private fun nowInstant(): Instant = Instant.fromEpochSeconds(System.currentTimeMillis() / MILLIS_PER_SECOND)

  private companion object {
    const val MILLIS_PER_SECOND = 1000L
  }
}

/**
 * Render an ESPI date-filter value in the form the custodian expects.
 *
 * [DateFilterFormat.DATE_CEILING] deliberately erases the time of day. That is the POINT, not a
 * loss: it makes two polls on the same UTC day produce a byte-identical URL, which is the property
 * an asynchronous-batch custodian needs to hand back the dataset it prepared for the previous poll
 * instead of enqueuing a new job. A `-min` that drifts by a second every poll can never match.
 */
private fun DateFilterFormat.render(instant: Instant): String =
  when (this) {
    DateFilterFormat.INSTANT -> instant.toString()
    DateFilterFormat.DATE_CEILING -> LocalDate.ofEpochDay(ceilToWholeDay(instant)).toString()
  }

/** Days since the epoch, rounded UP — an instant already on a day boundary stays put. */
private fun ceilToWholeDay(instant: Instant): Long {
  val days = Math.floorDiv(instant.epochSeconds, SECONDS_PER_DAY)
  return if (Math.floorMod(instant.epochSeconds, SECONDS_PER_DAY) == 0L) days else days + 1
}

private const val SECONDS_PER_DAY = 86_400L
