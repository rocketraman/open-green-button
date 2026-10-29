package org.opengb.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import org.opengb.config.AppConfig
import org.opengb.config.ClientAuthConfig
import org.opengb.utility.UtilityProfile
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import java.net.http.HttpClient as JdkHttpClient

/**
 * Owns the outbound [HttpClient]s used to talk to utility endpoints and resolves which one a given
 * utility should use.
 *
 * We use Ktor's Java engine (the JDK's `java.net.http` client, TLS via JSSE) rather than CIO: CIO
 * brings its own TLS implementation that stops at TLS 1.2 (KTOR-6737), and some Data Custodians
 * (e.g. Consumers Energy) only accept TLS 1.3.
 *
 * mTLS client certificates are negotiated per TLS connection, and a client pins one [SSLContext]
 * for its whole connection pool — so utilities that must present *different* certs cannot share a
 * single client. We therefore keep:
 *
 *  - one [default] client, built from [AppConfig.clientAuth] (the self-signed cert, or plain TLS
 *    when no default material is configured), shared by every utility without its own override, and
 *  - one client per utility that declares its own [UtilityProfile.clientAuth] material.
 *
 * [forUtility] does the default/override lookup the whole feature is about: a utility's own client
 * if it has one, else the default.
 */
class UtilityHttpClients private constructor(
  private val default: HttpClient,
  private val perUtility: Map<String, HttpClient>,
  /** Clients we constructed and are responsible for closing (excludes an injected test client). */
  private val owned: List<HttpClient>,
) : AutoCloseable {
  /** The client a connection to [utility] should use: its own override if present, else the default. */
  fun forUtility(utility: UtilityProfile): HttpClient = perUtility[utility.id] ?: default

  override fun close() {
    owned.forEach { runCatching { it.close() } }
  }

  companion object {
    /**
     * Test seam: route every utility through one caller-supplied client (e.g. a Ktor MockEngine).
     * We don't own it, so [close] leaves it alone.
     */
    fun singleClient(http: HttpClient): UtilityHttpClients =
      UtilityHttpClients(default = http, perUtility = emptyMap(), owned = emptyList())

    /**
     * Build a single standalone [HttpClient] with the given client-auth material (or plain TLS when
     * [auth] carries none). Used by the onboarding driver, which talks to a Data Custodian that
     * isn't a configured utility yet but requires the same mTLS client certificate. Caller owns and
     * must [HttpClient.close] the returned client.
     */
    fun mtlsClient(auth: ClientAuthConfig?): HttpClient = buildClient(auth)

    /**
     * Build the real clients from config: a default client plus one per utility that carries its
     * own client-auth material.
     */
    fun from(config: AppConfig): UtilityHttpClients {
      val default = buildClient(config.clientAuth)
      val perUtility =
        config.utilities
          .filter { it.clientAuth?.hasMaterial() == true }
          .associate { it.id to buildClient(it.clientAuth) }
      return UtilityHttpClients(default, perUtility, owned = listOf(default) + perUtility.values)
    }

    // ESPI Data Custodians assemble a subscription batch on demand and can take tens of seconds
    // before the first byte — especially on a first, full-history pull. Give utility calls a
    // generous cap (the CIO engine's default 15s cut that off against savagedata; the Java engine
    // has no default at all, so this is also what bounds a hung connection).
    private const val UTILITY_REQUEST_TIMEOUT_MS = 300_000L

    private fun buildClient(auth: ClientAuthConfig?): HttpClient {
      val clientAuthContext = if (auth != null && auth.hasMaterial()) clientAuthSslContext(auth) else null
      return HttpClient(Java) {
        engine {
          // Java's default is HTTP/2; stay on HTTP/1.1, which is what every utility has been
          // exercised against.
          protocolVersion = JdkHttpClient.Version.HTTP_1_1
          if (clientAuthContext != null) config { sslContext(clientAuthContext) }
        }
        install(HttpTimeout) { requestTimeoutMillis = UTILITY_REQUEST_TIMEOUT_MS }
      }
    }

    /**
     * An [SSLContext] that presents the private key + cert chain from the configured keystore as our
     * client certificate during the TLS handshake, trusting the platform's default CAs.
     */
    private fun clientAuthSslContext(auth: ClientAuthConfig): SSLContext {
      val storePassword =
        requireNotNull(auth.keystorePassword?.value) {
          "clientAuth.keystorePassword is required when a keystore is configured"
        }.toCharArray()
      val keyStore = loadKeyStore(auth, storePassword)
      // A separately-encrypted key entry can carry its own password; otherwise it shares the
      // keystore password (the common case for an `openssl pkcs12 -export` bundle).
      val keyPassword = auth.keyPassword?.value?.toCharArray() ?: storePassword
      // `keyAlias == null` selects the sole key entry.
      val alias =
        auth.keyAlias
          ?: keyStore.aliases().toList().filter { keyStore.isKeyEntry(it) }.let { keys ->
            require(keys.size == 1) {
              "clientAuth keystore must contain exactly one key entry when keyAlias is unset " +
                "(found ${keys.size})"
            }
            keys.single()
          }
      require(keyStore.isKeyEntry(alias)) { "clientAuth keystore has no key entry '$alias'" }
      val keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
          .apply { init(keyStore, keyPassword) }
          .keyManagers
          .filterIsInstance<X509ExtendedKeyManager>()
          .map { FixedAliasKeyManager(it, alias) }
      return SSLContext.getInstance("TLS").apply { init(keyManagers.toTypedArray(), null, null) }
    }

    private fun loadKeyStore(
      auth: ClientAuthConfig,
      password: CharArray,
    ): KeyStore {
      val bytes =
        try {
          Base64.getDecoder().decode(auth.keystoreBase64?.value?.trim().orEmpty())
        } catch (e: IllegalArgumentException) {
          throw IllegalArgumentException("clientAuth.keystoreBase64 is not valid base64", e)
        }
      return KeyStore.getInstance(auth.keystoreType).apply {
        ByteArrayInputStream(bytes).use { load(it, password) }
      }
    }
  }

  /**
   * Always offers [alias] as our client certificate. JSSE's stock key manager only offers a cert
   * whose issuer appears in the server's CertificateRequest CA list — our certs are self-signed, so
   * a server that sends a CA list would get no cert at all. CIO sent its cert unconditionally; keep
   * that behaviour and let the server decide.
   */
  private class FixedAliasKeyManager(
    private val delegate: X509ExtendedKeyManager,
    private val alias: String,
  ) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(
      keyType: Array<out String>?,
      issuers: Array<out Principal>?,
      socket: Socket?,
    ): String = alias

    override fun chooseEngineClientAlias(
      keyType: Array<out String>?,
      issuers: Array<out Principal>?,
      engine: SSLEngine?,
    ): String = alias

    override fun getClientAliases(
      keyType: String?,
      issuers: Array<out Principal>?,
    ): Array<String> = arrayOf(alias)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = delegate.getCertificateChain(alias)

    override fun getPrivateKey(alias: String?): PrivateKey? = delegate.getPrivateKey(alias)

    override fun chooseServerAlias(
      keyType: String?,
      issuers: Array<out Principal>?,
      socket: Socket?,
    ): String? = null

    override fun getServerAliases(
      keyType: String?,
      issuers: Array<out Principal>?,
    ): Array<String>? = null
  }
}
