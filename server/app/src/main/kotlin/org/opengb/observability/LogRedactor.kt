package org.opengb.observability

import org.opengb.config.CryptoConfig
import org.opengb.proxy.decodeBase64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Replaces per-user ESPI identifiers with a keyed hash before they reach a log line.
 *
 * A subscription id or usage-point id is a stable handle on one customer's utility account. The
 * server holds no per-user state, but its logs are retained by the hosting platform for days, so
 * writing those ids verbatim would persist exactly the thing the server otherwise never keeps —
 * and next to an access-log line it would tie them to a home IP address.
 *
 * The hash is a truncated HMAC rather than a blanket `[redacted]` so the logs stay useful: every
 * line about one subscription carries the same token, which is what following a single user's
 * polls across requests actually needs. It is keyed so that someone holding the logs and a guess
 * at an id cannot confirm it without the server secret.
 *
 * The key is DERIVED from the proxy-token pepper, not the pepper itself, so a log token can never
 * coincide with (or help forge) a proxy token.
 */
class LogRedactor(config: CryptoConfig) {
  private val key: SecretKeySpec =
    run {
      val pepper = decodeBase64(config.hmacPepperBase64.value, "OPENGB_CRYPTO_HMACPEPPERBASE64")
      SecretKeySpec(hmac(SecretKeySpec(pepper, HMAC_SHA256), KEY_LABEL), HMAC_SHA256)
    }

  /** The log token for one identifier, e.g. `h-3f9a1c0b7d2e`. Same input ⇒ same token. */
  fun id(value: String): String = "h-" + hmac(key, value).take(TOKEN_BYTES).joinToString("") { "%02x".format(it) }

  /**
   * [text] with every per-user identifier replaced by its token. Works on a bare URL and equally
   * on free text that merely contains URLs (a notification body, a custodian's status document).
   *
   * Two passes, because neither rule covers every custodian on its own: ids that follow a
   * per-user ESPI resource name (London Hydro's are plain integers, which nothing else would
   * recognise), then any remaining UUID wherever it appears.
   */
  fun text(text: String): String = UUID_REGEX.replace(RESOURCE_ID_REGEX.replace(text) { id(it.value) }) { id(it.value) }

  /**
   * How to find a request in the server's logs — appended to the error its caller receives.
   *
   * The logs hold hashed ids, so the subscription id in a user's bug report no longer matches
   * anything in them. This closes that gap from the user's side: their own log ends up carrying
   * the request id (present on every line the request emitted) and the URL exactly as it was
   * logged, whose `h-…` tokens find every other poll of the same subscription.
   */
  fun reference(
    requestId: String?,
    url: String,
  ): String = "request-id: ${requestId ?: "-"} | logged-as: ${text(url)}"

  private companion object {
    const val HMAC_SHA256 = "HmacSHA256"
    const val KEY_LABEL = "opengb.log-redaction.v1"

    // 48 bits: far past any collision risk at this population, short enough to read in a URL.
    const val TOKEN_BYTES = 6

    // The ESPI resources whose id identifies a customer or something that belongs to one.
    // Deliberately NOT ApplicationInformation (that id is ours, and onboarding needs it verbatim)
    // or ReadingType / LocalTimeParameters (shared reference data).
    val RESOURCE_ID_REGEX =
      Regex(
        "(?<=/(?:Subscription|UsagePoint|RetailCustomer|Authorization|MeterReading|IntervalBlock|" +
          "UsageSummary|ElectricPowerQualitySummary|Customer|CustomerAccount|CustomerAgreement|" +
          "ServiceLocation|Meter|EndDevice)/)[^/?#&\\s\"'<>]+",
      )

    val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun hmac(
      key: SecretKeySpec,
      value: String,
    ): ByteArray = Mac.getInstance(HMAC_SHA256).apply { init(key) }.doFinal(value.toByteArray(Charsets.UTF_8))
  }
}
