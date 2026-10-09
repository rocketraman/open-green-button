package org.opengb.routes

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.opengb.AppDeps
import org.opengb.appModule
import org.opengb.buildAppDeps
import org.opengb.config.AppConfig
import org.opengb.config.CryptoConfig
import org.opengb.config.LandingConfig
import org.opengb.config.ServerConfig
import org.opengb.config.StateConfig
import org.opengb.oauth.PendingOAuth
import org.opengb.utility.UtilityProfile
import java.util.Base64

/**
 * Tests for the error branch of `GET /connect/{utility}/callback`: the utility's
 * `error_description` is shown to the customer, but only when it arrives with a state we issued.
 */
val ConnectCallbackTest by testSuite {
  test("shows the utility's error_description when the state is one we issued") {
    runCallback { deps ->
      val state = deps.stateStore.create(PendingOAuth(utilityId = "mock", haNonce = null))
      val resp =
        client.get("/connect/mock/callback") {
          parameter("error", "server_error")
          parameter("error_description", "Internal system <b>failed</b> to load services.")
          parameter("state", state)
        }
      val body = resp.bodyAsText()
      assert(resp.status == HttpStatusCode.BadRequest) { "got ${resp.status}" }
      assert("Utility returned error: server_error" in body) { body }
      assert("Message from Mock Utility:" in body) { body }
      assert("Internal system &lt;b&gt;failed&lt;/b&gt; to load services." in body) { body }
      assert(deps.stateStore.consume(state) == null) { "state should have been consumed" }
    }
  }

  test("omits error_description when the state is unknown") {
    runCallback {
      val resp =
        client.get("/connect/mock/callback") {
          parameter("error", "server_error")
          parameter("error_description", "Call this number to fix your account")
          parameter("state", "not-a-real-state")
        }
      val body = resp.bodyAsText()
      assert(resp.status == HttpStatusCode.BadRequest) { "got ${resp.status}" }
      assert("Utility returned error: server_error" in body) { body }
      assert("Call this number" !in body) { body }
    }
  }

  test("truncates an over-long error_description") {
    runCallback { deps ->
      val state = deps.stateStore.create(PendingOAuth(utilityId = "mock", haNonce = null))
      val resp =
        client.get("/connect/mock/callback") {
          parameter("error", "server_error")
          parameter("error_description", "x".repeat(2000))
          parameter("state", state)
        }
      val body = resp.bodyAsText()
      assert("x".repeat(500) in body) { body }
      assert("x".repeat(501) !in body) { body }
    }
  }
}

private fun runCallback(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(AppDeps) -> Unit) {
  val utility =
    UtilityProfile(
      id = "mock",
      displayName = "Mock Utility",
      authorizeUrl = "https://utility.mock/authorize",
      tokenUrl = "https://utility.mock/token",
      clientId = "client_id_xyz",
      clientSecret = Masked("client_secret_xyz"),
      defaultScope = "FB=1;IntervalDuration=900",
    )
  val config =
    AppConfig(
      server = ServerConfig(publicBaseUrl = "http://test.local"),
      crypto =
        CryptoConfig(
          aesKeyBase64 = Masked(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })),
          hmacPepperBase64 =
            Masked(Base64.getEncoder().encodeToString(ByteArray(32) { ((it + 1) * 11).toByte() })),
        ),
      state = StateConfig(),
      landing = LandingConfig(),
      utilities = listOf(utility),
    )
  val deps = buildAppDeps(config)
  runBlocking {
    testApplication {
      application { appModule(deps) }
      block(deps)
    }
  }
}
