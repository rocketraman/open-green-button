package org.opengb.utility

/**
 * Turns a Green Button ESPI scope string (e.g. `FB=1_3_4_5_13;IntervalDuration=3600;
 * HistoryLength=...`) into plain-English bullet points for the customer-facing scope
 * confirmation screen (the Third Party "Scope Selection Screen" some Data Custodians — notably
 * PG&E — redirect the customer to during OAuth).
 *
 * This is a best-effort *human summary*, not an authoritative ESPI decoder: the confirmation
 * screen also shows the exact scope string verbatim, so mislabelling an obscure Function Block
 * can't misrepresent what's actually requested. Numbers we can't confidently attribute fall
 * through to a single generic catch-all line rather than being dropped silently.
 */
object ScopeSummary {
  private data class Bucket(val fbs: Set<Int>, val label: String)

  // ESPI Function Block IDs we can attribute to a customer-facing category. The 53–69
  // customer/account/billing split mirrors the function-block notes in utilities.conf (Burlington
  // profile); anything outside these falls through to the generic catch-all line in describe().
  private const val FB_SERVICE_POINTS = 1
  private const val FB_READING_TYPE = 3
  private const val FB_READING_UNITS = 5
  private const val FB_INTERVAL_USAGE = 4
  private const val FB_CUSTOMER_ACCOUNT_START = 53
  private const val FB_CUSTOMER_ACCOUNT_END = 61
  private const val FB_BILLING_START = 62
  private const val FB_BILLING_END = 69

  private val buckets =
    listOf(
      Bucket(setOf(FB_SERVICE_POINTS), "Which meters and service points are on your account"),
      Bucket(setOf(FB_READING_TYPE, FB_READING_UNITS), "Meter reading details (reading types and units of measure)"),
      Bucket(setOf(FB_INTERVAL_USAGE), "Your energy usage readings — e.g. hourly/interval consumption"),
      Bucket((FB_CUSTOMER_ACCOUNT_START..FB_CUSTOMER_ACCOUNT_END).toSet(), "Basic customer and account information"),
      Bucket((FB_BILLING_START..FB_BILLING_END).toSet(), "Billing and cost information"),
    )

  /** Human-readable bullet points describing the data groups the [scope] authorizes. */
  fun describe(scope: String?): List<String> {
    if (scope == null) return emptyList()
    val fbs = parseFunctionBlocks(scope)
    val lines = buckets.filter { bucket -> bucket.fbs.any(fbs::contains) }.map { it.label }.toMutableList()
    val covered = buckets.flatMapTo(mutableSetOf()) { it.fbs }
    if (fbs.any { it !in covered }) {
      lines += "Related service, program, and metadata needed to interpret the above"
    }
    return lines
  }

  /**
   * Extract the Function Block numbers from the `FB=1_3_4_...` token. Tolerates the `;`/`,`/space
   * separators different custodians use between the FB token and format params.
   */
  private fun parseFunctionBlocks(scope: String): Set<Int> {
    val fbToken =
      scope.split(';', ',', ' ')
        .map(String::trim)
        .firstOrNull { it.startsWith("FB=") }
        ?: return emptySet()
    return fbToken.removePrefix("FB=")
      .split('_')
      .mapNotNullTo(mutableSetOf()) { it.toIntOrNull() }
  }
}
