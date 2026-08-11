@file:Suppress("MatchingDeclarationName") // file holds the test suite + a small Hoplite decode holder

package org.opengb.utility

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.PropertySource
import de.infix.testBalloon.framework.core.testSuite

data class RefreshScopeHolder(val refreshScope: RefreshScope = RefreshScope.Granted)

// Build a HOCON-string loader WITHOUT the default env/system-property sources — the interactive
// shell's Lmod exports carry `${...}` in their values and break Hoplite's substitution pass (see
// the reference note on loadConfig). We add back everything else the default loader has.
internal inline fun <reified T : Any> loadHocon(hocon: String): T =
  ConfigLoaderBuilder
    .empty()
    .addDefaultDecoders()
    .addDefaultParsers()
    .addDefaultParamMappers()
    .addDefaultNodeTransformers()
    .addDefaultResolvers()
    .allowEmptySources()
    .addSource(PropertySource.string(hocon, "conf"))
    .build()
    .loadConfigOrThrow<T>()

val RefreshScopeConfigTest by testSuite {
  test("absent maps to the Granted default") {
    assert(loadHocon<RefreshScopeHolder>("{}").refreshScope == RefreshScope.Granted)
  }

  test("Omit maps by name") {
    assert(loadHocon<RefreshScopeHolder>("refreshScope: Omit").refreshScope == RefreshScope.Omit)
  }

  test("Granted maps by name") {
    assert(loadHocon<RefreshScopeHolder>("refreshScope: Granted").refreshScope == RefreshScope.Granted)
  }

  test("Explicit maps by shape") {
    val rs = loadHocon<RefreshScopeHolder>("""refreshScope: { scope: "FB=1_2" }""").refreshScope
    assert(rs == RefreshScope.Explicit("FB=1_2")) { "got $rs" }
  }
}
