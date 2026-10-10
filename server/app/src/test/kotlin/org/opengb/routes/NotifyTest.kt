package org.opengb.routes

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.opengb.appModule
import org.opengb.buildAppDeps
import org.opengb.config.AppConfig
import org.opengb.config.CryptoConfig
import org.opengb.config.LandingConfig
import org.opengb.config.ServerConfig
import org.opengb.config.StateConfig
import org.opengb.observability.captureLogs
import org.opengb.utility.UtilityProfile
import java.util.Base64

/**
 * Tests for the `POST /notify/{utility}` endpoint. The critical property during ESPI 3.3
 * onboarding is that a notification for an id that is **not yet configured** (its credentials
 * don't exist until we fetch the ApplicationInformation this notification points at) is still
 * accepted with a 2xx — a 404 here makes the Data Custodian retry and email an error.
 */
val NotifyTest by testSuite {
  test("accepts a notification for an unconfigured (mid-onboarding) utility with 200") {
    runNotify {
      val resp =
        client.post("/notify/milton_hydro") {
          contentType(ContentType.Application.Xml)
          setBody(APP_INFO_NOTIFICATION)
        }
      assert(resp.status == HttpStatusCode.OK) { "got ${resp.status}: ${resp.bodyAsText()}" }
    }
  }

  test("accepts a notification for a configured utility with 200") {
    runNotify {
      val resp =
        client.post("/notify/mock") {
          contentType(ContentType.Application.Xml)
          setBody(APP_INFO_NOTIFICATION)
        }
      assert(resp.status == HttpStatusCode.OK) { "got ${resp.status}: ${resp.bodyAsText()}" }
    }
  }

  test("a data-available notification is logged without the customer's ids") {
    // The BatchList names one customer's subscription and meters. The platform keeps these lines
    // for days, so neither id may appear — but the body keeps its shape and each id its own
    // stable token, so a custodian's behaviour can still be read off the logs.
    val events =
      captureLogs("opengb.notify") {
        runNotify {
          client.post("/notify/mock") {
            contentType(ContentType.Application.Xml)
            setBody(DATA_AVAILABLE_NOTIFICATION)
          }
        }
      }
    val event = events.single { it["utility.id"] == "mock" }
    assert(event.values.none { SUBSCRIPTION_ID in it || USAGE_POINT_ID in it }) { event.toString() }
    assert(event.getValue("notify.body").contains("<espi:BatchList")) { event.toString() }
    val resources = event.getValue("notify.resources")
    assert(Regex("/Batch/Subscription/h-[0-9a-f]{12}/UsagePoint/h-[0-9a-f]{12}").containsMatchIn(resources)) {
      resources
    }
  }

  test("an onboarding notification is still logged verbatim") {
    // Recovering the ApplicationInformation URL from the logs IS the onboarding procedure, and
    // this notification carries no customer data to protect.
    for (utilityId in listOf("mock", "not_yet_configured")) {
      val events =
        captureLogs("opengb.notify") {
          runNotify {
            client.post("/notify/$utilityId") {
              contentType(ContentType.Application.Xml)
              setBody(APP_INFO_NOTIFICATION)
            }
          }
        }
      val event = events.single { it["utility.id"] == utilityId }
      assert(event["notify.body"] == APP_INFO_NOTIFICATION) { event.toString() }
      assert(event.getValue("notify.resources").contains("/ApplicationInformation/42")) { event.toString() }
    }
  }

  test("the access log records neither the query string nor the caller's full address") {
    // The query string is where the OAuth callback carries the utility's one-time `code`.
    val events =
      captureLogs("opengb.access") {
        runNotify {
          client.post("/notify/mock?code=s3cret&state=abc") { header("Fly-Client-IP", "203.0.113.77") }
        }
      }
    val event = events.single { it["url.path"]?.startsWith("/notify/mock") == true }
    assert(event["url.path"] == "/notify/mock") { event.toString() }
    assert(event["client.ip"] == "203.0.113.0") { event.toString() }
    assert(event.values.none { "s3cret" in it || "203.0.113.77" in it }) { event.toString() }
  }

  test("accepts an empty-body notification") {
    runNotify {
      val resp = client.post("/notify/milton_hydro")
      assert(resp.status == HttpStatusCode.OK) { "got ${resp.status}: ${resp.bodyAsText()}" }
    }
  }
}

private fun runNotify(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
      block()
    }
  }
}

// A minimal ESPI BatchList whose resource href points at the ApplicationInformation resource the
// third party must GET next. Only URIs — no secrets — as in a real notification.
private val APP_INFO_NOTIFICATION =
  """
  <?xml version="1.0" encoding="UTF-8"?>
  <ns1:BatchList xmlns:ns1="http://naesb.org/espi">
    <ns1:resources>https://sandboxdc.savagedata.com:4243/DataCustodian/espi/1_1/resource/ApplicationInformation/42</ns1:resources>
  </ns1:BatchList>
  """.trimIndent()

private const val SUBSCRIPTION_ID = "2d2e7cd1-0654-5932-a593-bb12de986f1f"
private const val USAGE_POINT_ID = "0c52a218-006d-5530-a6ba-52c92a8df987"

// The shape savagedata actually sends when a batch is ready: an Atom feed wrapping a BatchList of
// per-UsagePoint URLs under the subscription.
private val DATA_AVAILABLE_NOTIFICATION =
  """
  <feed xmlns="http://www.w3.org/2005/Atom">
    <id>urn:uuid:b0f6878a-e3b8-5de9-b407-c77a15869764</id>
    <entry>
      <content>
        <espi:BatchList xmlns:espi="http://naesb.org/espi">
          <espi:resources>https://dc.mock/espi/1_1/resource/Batch/Subscription/$SUBSCRIPTION_ID/UsagePoint/$USAGE_POINT_ID</espi:resources>
        </espi:BatchList>
      </content>
      <link rel="self" href="https://dc.mock/Batch/Bulk/000001" type="espi-entry" />
    </entry>
  </feed>
  """.trimIndent()
