package org.opengb.observability

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import org.opengb.config.CryptoConfig
import java.util.Base64

private const val SUBSCRIPTION = "2d2e7cd1-0654-5932-a593-bb12de986f1f"
private const val USAGE_POINT = "0c52a218-006d-5530-a6ba-52c92a8df987"
private const val SAVAGE_BASE = "https://dc.example/espi/1_1/resource/Batch/Subscription"

val LogRedactorTest by testSuite {
  test("an id hashes to the same short token every time, and different ids differ") {
    val redactor = redactor()
    val token = redactor.id(SUBSCRIPTION)
    assert(Regex("h-[0-9a-f]{12}").matches(token)) { token }
    assert(token == redactor.id(SUBSCRIPTION))
    assert(token != redactor.id(USAGE_POINT))
  }

  test("the token depends on the server secret") {
    // Otherwise anyone holding the logs could confirm a guessed id by hashing it themselves.
    assert(redactor(seed = 11).id(SUBSCRIPTION) != redactor(seed = 13).id(SUBSCRIPTION))
  }

  test("a per-UsagePoint URL keeps its shape and query but loses both ids") {
    val redactor = redactor()
    val redacted =
      redactor.text("$SAVAGE_BASE/$SUBSCRIPTION/UsagePoint/$USAGE_POINT?published-min=2024-09-10T23%3A49%3A36Z")
    assert(
      redacted ==
        "$SAVAGE_BASE/${redactor.id(SUBSCRIPTION)}/UsagePoint/${redactor.id(USAGE_POINT)}" +
        "?published-min=2024-09-10T23%3A49%3A36Z",
    ) { redacted }
  }

  test("a plain-integer id is hashed by its position, and the ESPI version segment is left alone") {
    // London Hydro's ids are integers — no UUID rule would catch them — while `1_1` is the ESPI
    // version and must survive, or every redacted URL would be unreadable.
    val redactor = redactor()
    val redacted = redactor.text("https://dc.example/DataCustodian/espi/1_1/resource/Batch/Subscription/42")
    assert(redacted == "https://dc.example/DataCustodian/espi/1_1/resource/Batch/Subscription/${redactor.id("42")}") {
      redacted
    }
  }

  test("an ApplicationInformation id is not a customer id and stays readable") {
    val url = "https://dc.example/espi/1_1/resource/ApplicationInformation/42"
    assert(redactor().text(url) == url)
  }

  test("ids are found inside free text, not just in a bare URL") {
    val redacted =
      redactor().text(
        "<espi:resources>$SAVAGE_BASE/$SUBSCRIPTION/UsagePoint/$USAGE_POINT</espi:resources> " +
          "<id>urn:uuid:$USAGE_POINT</id>",
      )
    assert(!redacted.contains(SUBSCRIPTION) && !redacted.contains(USAGE_POINT)) { redacted }
    assert(redacted.contains("</espi:resources>")) { redacted }
  }
}

val AnonymizeIpTest by testSuite {
  test("an IPv4 address is reduced to its /24") {
    assert(anonymizeIp("203.0.113.77") == "203.0.113.0")
  }

  test("an IPv6 address is reduced to its /48") {
    assert(anonymizeIp("2001:db8:1234:5678:9abc:def0:1234:5678") == "2001:db8:1234:0:0:0:0:0")
  }

  test("anything that is not an IP literal is dropped rather than resolved or logged") {
    for (value in listOf(null, "", "localhost", "example.com", "not an ip", "1.2.3")) {
      assert(anonymizeIp(value) == null) { "$value -> ${anonymizeIp(value)}" }
    }
  }
}

private fun redactor(seed: Int = 11): LogRedactor =
  LogRedactor(
    CryptoConfig(
      aesKeyBase64 = Masked(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })),
      hmacPepperBase64 = Masked(Base64.getEncoder().encodeToString(ByteArray(32) { ((it + 1) * seed).toByte() })),
    ),
  )
