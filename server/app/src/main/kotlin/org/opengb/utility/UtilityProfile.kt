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
)

data class UtilitiesConfig(val utilities: List<UtilityProfile> = emptyList())
