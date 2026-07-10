package org.opengb.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.strong
import kotlinx.html.style
import kotlinx.html.summary
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import org.apache.logging.log4j.kotlin.logger
import org.opengb.AppDeps
import org.opengb.oauth.ClaimRecord
import org.opengb.oauth.OAuthException
import org.opengb.oauth.PendingOAuth
import org.opengb.proxy.RefreshBlob
import org.opengb.utility.ScopeSummary
import org.opengb.utility.UnknownUtilityException
import org.opengb.utility.UtilityProfile

internal val connectLog = logger("opengb.connect")

/**
 * Routes:
 *  - GET /connect/{utility}/scope[?ha_nonce=...] — the Third Party "Scope Selection Screen": a
 *    confirmation page listing what data the customer is about to share, with a button that
 *    continues to `.../start`. This is the URI a Data Custodian (e.g. PG&E) redirects the customer
 *    to when the flow is *custodian-initiated* (customer picked us from the utility's portal). It's
 *    also linked from the marketing site's "Connect" buttons so web visitors get the same consent
 *    step. Registered with custodians as `thirdPartyScopeSelectionScreenURI`.
 *  - GET /connect/{utility}/start[?ha_nonce=...] — generates CSRF state, 302s the user to the
 *    utility's authorize URL. Reachable directly (Home-Assistant-initiated flow) or via the scope
 *    screen above.
 *  - GET /connect/{utility}/callback?code=...&state=... — exchanges code for tokens, encrypts the
 *    refresh blob, stashes it behind a one-time claim code, and renders an HTML page showing the
 *    code to the user.
 */
fun Application.installConnect(deps: AppDeps) {
  routing {
    get("/connect/{utility}/scope") { handleScopeScreen(deps) }
    get("/connect/{utility}/start") { handleStart(deps) }
    get("/connect/{utility}/callback") { handleCallback(deps) }
  }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleScopeScreen(deps: AppDeps) {
  val utility = resolveUtility(deps) ?: return
  // ha_nonce is present when Home Assistant initiates; absent when a Data Custodian sends the
  // customer here from their portal. Thread it through to `.../start` so the HA case is preserved.
  val haNonce = call.request.queryParameters["ha_nonce"]
  val continueUrl =
    URLBuilder("${deps.config.server.publicBaseUrl}/connect/${utility.id}/start").apply {
      if (!haNonce.isNullOrBlank()) parameters.append("ha_nonce", haNonce)
    }.buildString()
  call.respondHtml {
    renderScopeScreen(
      utility = utility,
      continueUrl = continueUrl,
      scopeLines = ScopeSummary.describe(utility.registeredScope),
      historyText = humanizeWindow(utility.initialHistory),
    )
  }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleStart(deps: AppDeps) {
  val utility = resolveUtility(deps) ?: return
  val state =
    deps.stateStore.create(
      PendingOAuth(
        utilityId = utility.id,
        haNonce = call.request.queryParameters["ha_nonce"],
      ),
    )
  val redirectUri = "${deps.config.server.publicBaseUrl}/connect/${utility.id}/callback"
  val url =
    URLBuilder(utility.authorizeUrl).apply {
      parameters.append("response_type", "code")
      parameters.append("client_id", utility.clientId)
      parameters.append("redirect_uri", redirectUri)
      if (utility.defaultScope != null) parameters.append("scope", utility.defaultScope)
      parameters.append("state", state)
    }.buildString()
  // No logger emits the outbound authorize redirect otherwise (the access log records only
  // method/path/status, and this is a server-side 302, not an HttpClient call). Log it so we can
  // confirm whether `scope` is actually sent — the URL carries client_id/state but no secret.
  connectLog.info(
    "authorize redirect utility=${utility.id} scopeSent=${utility.defaultScope != null} url=$url",
  )
  call.respondRedirect(url, permanent = false)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCallback(deps: AppDeps) {
  val utility = resolveUtility(deps) ?: return
  val state = call.request.queryParameters["state"]
  val code = call.request.queryParameters["code"]
  val error = call.request.queryParameters["error"]

  if (error != null) {
    call.respondCallbackError("Utility returned error: $error")
    return
  }
  if (state.isNullOrBlank() || code.isNullOrBlank()) {
    call.respondCallbackError("Missing 'state' or 'code' query parameter.")
    return
  }

  val pending = deps.stateStore.consume(state)
  if (pending == null) {
    call.respondCallbackError("OAuth state is unknown, expired, or already used.")
    return
  }
  if (pending.utilityId != utility.id) {
    call.respondCallbackError("OAuth state does not match this utility.")
    return
  }

  val refreshBlob = exchangeAndBuildBlob(deps, utility, code) ?: return
  val encrypted = deps.crypto.encrypt(refreshBlob)
  val proxyToken = deps.crypto.deriveProxyToken(refreshBlob)
  val claimCode =
    deps.claimStore.create(
      ClaimRecord(
        utilityId = utility.id,
        encryptedRefreshBlob = encrypted,
        proxyToken = proxyToken,
        subscriptionUri = refreshBlob.subscriptionUri,
        scope = refreshBlob.scope,
      ),
    )
  call.respondHtml {
    renderClaimPage(
      utilityDisplayName = utility.displayName,
      claimCode = claimCode,
      // No nonce ⇒ the flow was custodian-initiated (customer came from the utility's portal, not
      // from a waiting Home Assistant), so spell out the HA steps rather than assuming HA is open.
      haInitiated = pending.haNonce != null,
    )
  }
}

/** 404s on unknown utility and returns null so the caller can `return`. */
private suspend fun io.ktor.server.routing.RoutingContext.resolveUtility(deps: AppDeps): UtilityProfile? {
  val utilityId = call.parameters["utility"].orEmpty()
  return try {
    deps.registry.require(utilityId)
  } catch (_: UnknownUtilityException) {
    call.respondText("Unknown utility '$utilityId'.", ContentType.Text.Plain, HttpStatusCode.NotFound)
    null
  }
}

private suspend fun io.ktor.server.routing.RoutingContext.exchangeAndBuildBlob(
  deps: AppDeps,
  utility: UtilityProfile,
  code: String,
): RefreshBlob? {
  val redirectUri = "${deps.config.server.publicBaseUrl}/connect/${utility.id}/callback"
  val tokens =
    try {
      deps.oauth.exchangeCode(utility, code, redirectUri)
    } catch (e: OAuthException) {
      connectLog.warn(
        "Token exchange failed for utility=${utility.id}: ${e.message}",
      )
      call.respondCallbackError("Could not exchange authorization code with utility.")
      return null
    }
  val refreshToken = tokens.refreshToken
  if (refreshToken.isNullOrBlank()) {
    call.respondCallbackError("Utility did not issue a refresh token.")
    return null
  }
  // The scope the utility echoes here IS the granted scope we replay on refresh. Logged so we can
  // see whether a custodian (e.g. Kentucky Utilities) returns a granted scope at all, and if so
  // whether it's the bare FB list (params stripped) vs. what we requested. No secrets in a scope.
  connectLog.info(
    "Token exchange ok for utility=${utility.id}: grantedScope=${tokens.scope}",
  )
  // Persist the scope the utility granted (echoed in the token response), or null if it echoed none.
  // Two consumers: (1) RefreshScope.Granted — the default — replays this verbatim on the refresh
  // grant (see ProxyUsage / UtilityProfile.refreshScope); a null here ⇒ omit scope on refresh.
  // (2) it's surfaced to Home Assistant via the claim record (ClaimRecord.scope → the /claim
  // response) as informational metadata.
  return RefreshBlob(
    utilityId = utility.id,
    refreshToken = refreshToken,
    subscriptionUri = tokens.resourceUri,
    authorizationUri = tokens.authorizationUri,
    scope = tokens.scope,
  )
}

private suspend fun ApplicationCall.respondCallbackError(message: String) {
  respondHtml(HttpStatusCode.BadRequest) {
    head {
      title { +"Authorization failed" }
      meta(charset = "utf-8")
      style { unsafe { raw(CSS) } }
    }
    body {
      h1 { +"Authorization failed" }
      p { +message }
      p { +"You can close this window and try again from Home Assistant." }
    }
  }
}

/**
 * The Third Party Scope Selection Screen. Lists — in plain English — the data the customer is about
 * to authorize, shows the exact ESPI scope string for transparency, and continues to `.../start`.
 */
private fun HTML.renderScopeScreen(
  utility: UtilityProfile,
  continueUrl: String,
  scopeLines: List<String>,
  historyText: String,
) {
  head {
    title { +"Share your ${utility.displayName} data" }
    meta(charset = "utf-8")
    meta(name = "viewport", content = "width=device-width,initial-scale=1")
    style { unsafe { raw(CSS) } }
  }
  body {
    h1 { +"Share your ${utility.displayName} data" }
    p {
      +"You're about to let "
      strong { +"Open Green Button" }
      +" access the following data from ${utility.displayName} on your behalf, so it can appear "
      +"in your Home Assistant Energy dashboard:"
    }
    ul("scope-list") {
      scopeLines.forEach { li { +it } }
      li { +"Up to $historyText of history on the first sync" }
    }
    p("muted") {
      +"You'll sign in at ${utility.displayName} and confirm on their page — we never see your "
      +"utility password. Your readings and the access token are stored only in your Home "
      +"Assistant, never on our server."
    }
    a(href = continueUrl, classes = "btn") { +"Continue to ${utility.displayName}" }
    details {
      summary { +"Show the exact data-sharing scope" }
      div("code-box code-box--left") {
        code { +utility.registeredScope }
      }
    }
  }
}

private fun HTML.renderClaimPage(
  utilityDisplayName: String,
  claimCode: String,
  haInitiated: Boolean,
) {
  head {
    title { +"Connected — paste your claim code" }
    meta(charset = "utf-8")
    meta(name = "viewport", content = "width=device-width,initial-scale=1")
    style { unsafe { raw(CSS) } }
  }
  body {
    h1 { +"Connected to $utilityDisplayName" }
    if (haInitiated) {
      p { +"Paste this code into Home Assistant within 10 minutes:" }
    } else {
      // Custodian-initiated: the customer has no Home Assistant window waiting, so guide them there.
      p { +"Almost done — finish in Home Assistant within 10 minutes:" }
      ol("steps-list") {
        li {
          +"In Home Assistant, go to "
          strong { +"Settings → Devices & Services → Add Integration" }
          +" and pick "
          strong { +"Open Green Button" }
          +" (install it via HACS first if you haven't)."
        }
        li { +"Choose $utilityDisplayName, then paste this code:" }
      }
    }
    div("code-box") {
      code { +claimCode }
    }
    p("muted") {
      +"Single-use. Lost it? Restart the connect flow from Home Assistant."
    }
  }
}

/**
 * Render a compact history window spec (`2y`, `6m`, `90d`) as a human phrase for the scope screen.
 * Falls back to the raw spec for anything unexpected — this is display copy, not a parser.
 */
private fun humanizeWindow(spec: String): String {
  val amount = spec.dropLast(1)
  val unit =
    when (spec.lastOrNull()) {
      'y' -> "year"
      'm' -> "month"
      'w' -> "week"
      'd' -> "day"
      else -> return spec
    }
  val plural = if (amount == "1") unit else "${unit}s"
  return "$amount $plural"
}

private const val CSS = """
body { font-family: system-ui, sans-serif; max-width: 560px; margin: 4rem auto; padding: 0 1.5rem; color: #1a1a1a; line-height: 1.55; }
h1 { font-size: 1.6rem; margin-bottom: 0.5rem; }
.muted { color: #666; font-size: 0.92em; }
.code-box { background: #f4f7f4; border: 1px solid #d5e2d5; padding: 1rem 1.25rem; border-radius: 6px; margin: 1.5rem 0; text-align: center; }
.code-box code { font-size: 1.4rem; letter-spacing: 0.03em; color: #2d662d; word-break: break-all; user-select: all; }
.code-box--left { text-align: left; }
.code-box--left code { font-size: 0.95rem; }
.scope-list { padding-left: 1.25rem; }
.scope-list li { margin-bottom: 0.4rem; }
.steps-list { padding-left: 1.25rem; }
.steps-list li { margin-bottom: 0.5rem; }
.btn { display: inline-block; background: #2d662d; color: #fff; padding: 0.7rem 1.4rem; border-radius: 8px; font-weight: 600; text-decoration: none; margin: 0.5rem 0 1rem; }
.btn:hover { filter: brightness(1.08); }
details { margin: 1rem 0; }
summary { cursor: pointer; color: #666; font-size: 0.92em; }
"""
