@file:Suppress("MatchingDeclarationName") // file holds the test suite + a small Hoplite decode holder

package org.opengb.utility

import com.typesafe.config.ConfigFactory
import de.infix.testBalloon.framework.core.testSuite

data class QuirksHolder(val quirks: UtilityQuirks = UtilityQuirks())

val UtilityQuirksConfigTest by testSuite {
  test("date-filter quirks default to the ESPI spec form") {
    val quirks = loadHocon<QuirksHolder>("{}").quirks
    assert(quirks.dateFilterFormat == DateFilterFormat.INSTANT) { "got ${quirks.dateFilterFormat}" }
    assert(quirks.sendsPublishedMax) { "published-max must be sent unless a utility opts out" }
  }

  test("DATE_CEILING maps by name") {
    val quirks = loadHocon<QuirksHolder>("quirks: { dateFilterFormat: DATE_CEILING }").quirks
    assert(quirks.dateFilterFormat == DateFilterFormat.DATE_CEILING) { "got ${quirks.dateFilterFormat}" }
  }

  test("sendsPublishedMax can be turned off") {
    val quirks = loadHocon<QuirksHolder>("quirks: { sendsPublishedMax: false }").quirks
    assert(!quirks.sendsPublishedMax)
  }

  // The packaged utilities.conf is never decoded in tests — its clientId/clientSecret come from
  // `${?OPENGB_UTILITY_*}` env vars that don't exist here, so a full Hoplite load can't succeed.
  // Parse it as raw HOCON instead (no resolve, so the substitutions stay untouched) and assert the
  // literal values. That catches the two ways this file breaks in production and nowhere else: a
  // syntax error, and an enum name that doesn't exist — both of which surface only at boot on Fly.
  test("the packaged utilities.conf gives Alectra the async-batch date quirks") {
    val parsed = ConfigFactory.parseResources("utilities.conf")
    val alectra =
      parsed
        .getConfigList("utilities")
        .single { it.getString("id") == "alectra" }
    assert(alectra.getString("quirks.dateFilterFormat") == DateFilterFormat.DATE_CEILING.name) {
      alectra.getString("quirks.dateFilterFormat")
    }
    assert(!alectra.getBoolean("quirks.sendsPublishedMax"))
  }

  // Milton is the same Savage Data platform but answers synchronously — it must keep the spec
  // form. Pinned because "same vendor, copy the quirks over" is the obvious wrong move here.
  test("the packaged utilities.conf leaves Milton Hydro on the spec date form") {
    val parsed = ConfigFactory.parseResources("utilities.conf")
    val milton =
      parsed
        .getConfigList("utilities")
        .single { it.getString("id") == "milton_hydro" }
    assert(!milton.hasPath("quirks.dateFilterFormat")) { "milton_hydro must not opt into DATE_CEILING" }
  }
}
