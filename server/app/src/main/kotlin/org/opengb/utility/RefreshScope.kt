package org.opengb.utility

/**
 * How to populate the `scope` parameter on a utility's **refresh_token** grant, decoupled from the
 * authorize-time scope ([UtilityProfile.defaultScope]).
 *
 * RFC 6749 §6 makes `scope` OPTIONAL on refresh (an omitted scope means "the originally granted
 * scope"). This is a sealed type rather than a nullable string because "replay whatever was granted"
 * ([Granted]) is *dynamic* — it lives in the token-exchange response, not in config — and can't be
 * expressed as a static value.
 *
 * Hoplite maps the subtypes from HOCON: the objects by name (`refreshScope = Omit` / `= Granted`),
 * and [Explicit] by shape (`refreshScope { scope = "FB=..." }`). Absent ⇒ the [Granted] default.
 */
sealed interface RefreshScope {
  /**
   * Send no `scope` on refresh. Spec-conformant (RFC 6749 §6: an omitted scope means "the granted
   * scope"), but not universally accepted — Kentucky Utilities rejects a scopeless refresh with
   * `invalid_scope`, which is why [Granted] and [Explicit] exist.
   */
  data object Omit : RefreshScope

  /**
   * Replay the scope the utility granted at token exchange (`RefreshBlob.scope`); degrades to [Omit]
   * when the utility echoed no scope. The default: it satisfies custodians that (non-conformantly)
   * demand the grant echoed back on refresh — e.g. Kentucky Utilities — and is a harmless no-op for
   * conformant ones (Burlington/London accept it verbatim; confirmed 2026-07-10).
   */
  data object Granted : RefreshScope

  /** Send a fixed scope verbatim — an escape hatch for a custodian needing a specific value. */
  data class Explicit(val scope: String) : RefreshScope
}
