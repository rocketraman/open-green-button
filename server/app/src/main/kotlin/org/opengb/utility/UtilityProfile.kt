package org.opengb.utility

import com.sksamuel.hoplite.Masked
import org.opengb.config.ClientAuthConfig

/**
 * Static, per-utility configuration. One profile per data custodian we integrate with.
 *
 * All URLs and credentials are global (shared across every Home Assistant instance using this
 * proxy for the given utility), which is consistent with the stateless invariant: the only state
 * that lives on the server is what all users share, not what any individual user owns.
 */
data class UtilityProfile(
  val id: String,
  val displayName: String,
  val authorizeUrl: String,
  val tokenUrl: String,
  val clientId: String,
  val clientSecret: Masked,
  /**
   * Scope string sent verbatim as the `scope` param on the authorization request, or `null` to
   * omit `scope` entirely — in which case (RFC 6749 §3.3) the utility applies the scope fixed at
   * registration time. Set to `null` for custodians (e.g. Kentucky Utilities) that reject an
   * explicit `scope` with `invalid_scope`; record what they registered in [registeredScope].
   */
  val defaultScope: String? = null,
  /**
   * The scope of record shown to the customer on the consent screen (plain-English bullets +
   * verbatim string) and persisted when the utility doesn't echo a granted scope. It's what
   * actually applies whether we send it or the utility falls back to its registration default, so
   * it's the display/persistence source of truth. Defaults to [defaultScope] (DRY — utilities that
   * send a scope never repeat it); set explicitly only when [defaultScope] is `null`. Empty only
   * when a profile configures neither, a misconfiguration that renders as nothing.
   */
  val registeredScope: String = defaultScope ?: "",
  /**
   * How the `scope` param on the refresh_token grant is populated, decoupled from [defaultScope]
   * (the authorize scope). Defaults to [RefreshScope.Granted] — replay whatever the utility granted.
   * See [RefreshScope] for the modes and their HOCON syntax.
   */
  val refreshScope: RefreshScope = RefreshScope.Granted,
  /**
   * How far back the client backfills usage on the *initial* authorization, expressed as a
   * human-friendly window (`2y`, `6m`, `90d`). Surfaced to the Home Assistant client in the claim
   * response (as seconds); the client uses it to compute `published-min` on the first fetch.
   *
   * Per-utility because retention and the volume a utility will serve on a first pull vary — some
   * may not support 2 years. This is the single source of truth for the backfill window; the
   * client only falls back to its own default when an entry predates this field. Distinct from the
   * OAuth-scope `HistoryLength` preference (which asks the *utility* how much to authorize) — see
   * utilities.conf; for the test-lab harness the scope value isn't accepted, so this is the
   * effective control.
   */
  val initialHistory: String = "2y",
  /**
   * How often the Home Assistant client polls `/proxy/usage` for freshly-published data, as a
   * human-friendly window (`1d`, `12h`, `6h`). Surfaced to the client in the claim response (as
   * seconds); it drives the coordinator's `update_interval`.
   *
   * Per-utility because publish cadence and how aggressively a custodian tolerates polling vary.
   * Default `1d` — utilities publish interval data on a multi-hour-to-multi-day lag, so a daily
   * poll captures everything without hammering the resource server. The client falls back to its
   * own default only when an entry predates this field.
   */
  val pollInterval: String = "1d",
  /** Where the utility POSTs notifications. The proxy registers this URL at app submission time. */
  val notificationPath: String = "/notify/$id",
  val tokenAuthStyle: TokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
  /**
   * Per-utility TLS client-authentication (mTLS) override. Present ⇒ connections to this utility
   * present *this* keystore instead of the default [org.opengb.config.AppConfig.clientAuth]. Use it
   * for custodians whose cert requirements differ (e.g. a cert they issue rather than our
   * self-signed default). Absent / no keystore ⇒ fall back to the default block.
   */
  val clientAuth: ClientAuthConfig? = null,
  val quirks: UtilityQuirks = UtilityQuirks(),
) {
  /** [initialHistory] parsed to seconds. Throws if the configured spec is malformed. */
  val initialHistorySeconds: Long
    get() = parseHistoryWindowSeconds(initialHistory)

  /** [pollInterval] parsed to seconds. Throws if the configured spec is malformed. */
  val pollIntervalSeconds: Long
    get() = parseHistoryWindowSeconds(pollInterval)
}

enum class TokenAuthStyle {
  /** HTTP Basic header carrying client_id:client_secret. */
  HTTP_BASIC,

  /** client_id and client_secret as form params in the body. */
  FORM_BODY,
}

/**
 * Per-utility flags that escape-hatch around spec deviations. Add booleans here as we encounter
 * non-conforming behaviour in the wild; do NOT add behaviour-changing logic to the registry itself.
 */
data class UtilityQuirks(
  val sendsRefreshTokenOnRefresh: Boolean = true,
  val requiresClientCredentialsForMetadata: Boolean = false,
  /**
   * Override the ESPI date-range query-parameter base name this utility's resource server
   * actually honors for incremental retrieval, e.g. `"updated"` to send `updated-min`/
   * `updated-max` instead of the spec-default `published-min`/`published-max`.
   *
   * Escape hatch for a utility whose interval resources genuinely carry a stale `<published>`
   * timestamp (e.g. resource-creation time) rather than data-availability time, which would make
   * `published-min` filter out every interval on an incremental (recent-window) poll. Before
   * setting this for a utility, confirm with a DIRECT probe (bypassing our own client — an
   * HA-side cursor bug can produce the same 0-reading symptom) that `updated-min` genuinely
   * scopes to the requested window and doesn't just return the full history regardless of it —
   * see the Burlington Hydro investigation (2026-07-08, docs/utilities/burlington-incremental-
   * issue.md) for a case where it looked like the fix but turned out not to be: a properly
   * scoped `updated-min` probe returned an IDENTICAL reading count/date-range whether the window
   * was 4 days or 2 years wide, meaning it wasn't filtering at all — using it for periodic
   * polling would have pulled the entire history on every poll. Null (default) ⇒ spec-default
   * `published`.
   */
  val dateFilterParam: String? = null,
  /**
   * Wire format for the ESPI date-filter *values* this utility's resource server keys its prepared
   * batches on. Default [DateFilterFormat.INSTANT] is the spec form and what every synchronous
   * custodian gets.
   *
   * Exists for custodians that answer the batch request with **202 Accepted** (ESPI asynchronous
   * batch delivery) and then prepare the dataset under a URL of their OWN choosing — one they
   * canonicalize from what we asked for. Our request only matches the prepared batch if we ask in
   * the custodian's canonical form; ask in ours and every poll enqueues a fresh job and gets a
   * fresh 202, forever. See [sendsPublishedMax], which travels with this.
   *
   * INFERRED FROM A SINGLE SAMPLE — treat as unconfirmed. Alectra (Savage Data) POSTed a BatchList
   * to /notify/alectra on 2026-08-06T20:31:01Z naming
   * `…/Batch/Subscription/{id}?published-min=2024-08-07` in answer to a request whose actual
   * published-min was `2024-08-06T20:19Z` and which also carried a published-max: date only, no
   * `-max`, and rounded UP to the next whole day. Whether the custodian truly keys by URL, and
   * whether the rounding is a ceiling or an off-by-one we've misread, is exactly what the 202
   * diagnostics in [org.opengb.routes.ProxyUsage] are there to settle. Revisit this once a real
   * 202's headers and body are in the logs.
   *
   * KNOWN COST of the ceiling: it moves `-min` FORWARD by up to a day, which eats into the one-day
   * overlap the client leaves on an incremental poll to catch late-published corrections — in the
   * worst case reducing that margin to nothing. Acceptable only because the alternative here is
   * the status quo of no data at all; if the logs show the custodian floors rather than ceilings,
   * prefer flooring, which spends margin in the safe direction.
   */
  val dateFilterFormat: DateFilterFormat = DateFilterFormat.INSTANT,
  /**
   * Whether to send the `-max` half of the ESPI date-range filter at all. Default `true` (spec
   * behaviour). Set `false` for a custodian that drops it when canonicalizing an asynchronous
   * batch request — sending a half the custodian discards guarantees our URL never matches the
   * batch it prepared. Companion to [dateFilterFormat]; the two were observed together and only
   * make sense together.
   *
   * Safe to turn off: the `-max` bound only ever excluded future-dated readings, which no
   * custodian publishes (see the clamp in [org.opengb.proxy.UsageClient]), so omitting it widens
   * the window to "everything published since `-min`" — which is what an incremental poll wants.
   */
  val sendsPublishedMax: Boolean = true,
)

/** Wire format for ESPI date-filter values. See [UtilityQuirks.dateFilterFormat]. */
enum class DateFilterFormat {
  /** ISO 8601 instant, e.g. `2024-08-06T20:19:33Z` — the ESPI spec form. */
  INSTANT,

  /** Date only, rounded UP to the next whole UTC day, e.g. `2024-08-07`. */
  DATE_CEILING,
}

data class UtilitiesConfig(val utilities: List<UtilityProfile> = emptyList())
