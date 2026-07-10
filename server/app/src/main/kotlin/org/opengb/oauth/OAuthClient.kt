package org.opengb.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.opengb.http.UtilityHttpClients
import org.opengb.utility.TokenAuthStyle
import org.opengb.utility.UtilityProfile
import java.util.Base64

/**
 * Authorization Code grant response per RFC 6749 §5.1, extended with the ESPI-specific resource
 * pointer fields that Green Button adds.
 */
@Serializable
data class TokenResponse(
  @SerialName("access_token") val accessToken: String,
  @SerialName("token_type") val tokenType: String? = null,
  @SerialName("refresh_token") val refreshToken: String? = null,
  @SerialName("expires_in") val expiresIn: Long? = null,
  val scope: String? = null,
  // ESPI-specific:
  @SerialName("resourceURI") val resourceUri: String? = null,
  @SerialName("authorizationURI") val authorizationUri: String? = null,
)

/**
 * Parameters for an OAuth2 client_credentials grant against a Data Custodian token endpoint (ESPI
 * onboarding). Bundled so the credentials + endpoint travel together rather than as a long argument
 * list.
 */
data class ClientCredentialsRequest(
  val tokenUrl: String,
  val clientId: String,
  val clientSecret: String,
  val scope: String? = null,
  val authStyle: TokenAuthStyle = TokenAuthStyle.HTTP_BASIC,
)

class OAuthClient(private val clients: UtilityHttpClients, private val json: Json = DEFAULT_JSON) {
  /**
   * Exchanges an OAuth authorization code for tokens at the utility's token endpoint.
   *
   * @throws OAuthException if the utility returns a non-2xx response or a malformed body.
   */
  suspend fun exchangeCode(
    utility: UtilityProfile,
    code: String,
    redirectUri: String,
  ): TokenResponse =
    post(utility) {
      append("grant_type", "authorization_code")
      append("code", code)
      append("redirect_uri", redirectUri)
    }

  suspend fun refresh(
    utility: UtilityProfile,
    refreshToken: String,
    scope: String? = null,
  ): TokenResponse =
    post(utility) {
      append("grant_type", "refresh_token")
      append("refresh_token", refreshToken)
      // RFC 6749 §6: an omitted `scope` means "same as originally granted". `null` omits it; any
      // non-null value is sent verbatim (some utilities reject an explicit scope even when it equals
      // what was granted, so callers default to null).
      if (scope != null) append("scope", scope)
    }

  /**
   * OAuth2 **client_credentials** grant (RFC 6749 §4.4). Used during ESPI 3.3 onboarding to obtain
   * a `registration_access_token` from a Data Custodian's token endpoint — the token we then present
   * to GET the ApplicationInformation resource.
   *
   * Unlike [exchangeCode]/[refresh] this is **not** utility-scoped: at onboarding time the utility
   * isn't in `utilities.conf` yet (its real credentials live in the ApplicationInformation we're
   * about to fetch). The caller therefore supplies the DC token URL, the *initial registration*
   * client credentials, and the [httpClient] to make the call over — which for savagedata must be
   * an mTLS client presenting our (non-self-signed) client certificate.
   */
  suspend fun clientCredentials(
    httpClient: HttpClient,
    request: ClientCredentialsRequest,
  ): TokenResponse =
    postForm(
      TokenEndpointCall(
        httpClient = httpClient,
        tokenUrl = request.tokenUrl,
        clientId = request.clientId,
        clientSecret = request.clientSecret,
        authStyle = request.authStyle,
        label = "onboarding client_credentials",
      ),
    ) {
      append("grant_type", "client_credentials")
      if (!request.scope.isNullOrBlank()) append("scope", request.scope)
    }

  private suspend fun post(
    utility: UtilityProfile,
    addParams: ParametersBuilder.() -> Unit,
  ): TokenResponse =
    postForm(
      TokenEndpointCall(
        httpClient = clients.forUtility(utility),
        tokenUrl = utility.tokenUrl,
        clientId = utility.clientId,
        clientSecret = utility.clientSecret.value,
        authStyle = utility.tokenAuthStyle,
        label = "Utility '${utility.id}'",
      ),
      addParams,
    )

  /** The token-endpoint coordinates + client auth for a single grant call. */
  private data class TokenEndpointCall(
    val httpClient: HttpClient,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String,
    val authStyle: TokenAuthStyle,
    val label: String,
  )

  private suspend fun postForm(
    call: TokenEndpointCall,
    addParams: ParametersBuilder.() -> Unit,
  ): TokenResponse {
    val (formParams, basic) = buildAuth(call.clientId, call.clientSecret, call.authStyle, addParams)
    val response: HttpResponse =
      call.httpClient.submitForm(url = call.tokenUrl, formParameters = formParams) {
        headers {
          if (basic != null) append(HttpHeaders.Authorization, "Basic $basic")
          append(HttpHeaders.Accept, "application/json")
        }
      }
    return parse(response, call.label)
  }

  private fun buildAuth(
    clientId: String,
    clientSecret: String,
    authStyle: TokenAuthStyle,
    addParams: ParametersBuilder.() -> Unit,
  ): Pair<Parameters, String?> {
    return when (authStyle) {
      TokenAuthStyle.HTTP_BASIC -> {
        val basic =
          Base64.getEncoder().encodeToString(
            "$clientId:$clientSecret".toByteArray(Charsets.UTF_8),
          )
        parameters { addParams() } to basic
      }
      TokenAuthStyle.FORM_BODY -> {
        val withCreds =
          parameters {
            addParams()
            append("client_id", clientId)
            append("client_secret", clientSecret)
          }
        withCreds to null
      }
    }
  }

  private suspend fun parse(
    response: HttpResponse,
    label: String,
  ): TokenResponse {
    val bodyText = response.bodyAsText()
    if (response.status != HttpStatusCode.OK) {
      throw OAuthException(
        "$label returned ${response.status.value} from token endpoint: $bodyText",
        statusCode = response.status.value,
      )
    }
    return try {
      json.decodeFromString(TokenResponse.serializer(), bodyText)
    } catch (e: SerializationException) {
      throw OAuthException(
        "$label returned a malformed token response: $bodyText",
        cause = e,
      )
    }
  }

  companion object {
    val DEFAULT_JSON =
      Json {
        ignoreUnknownKeys = true
        isLenient = true
      }
  }
}

class OAuthException(
  message: String,
  val statusCode: Int? = null,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
