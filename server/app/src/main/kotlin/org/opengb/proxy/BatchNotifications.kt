package org.opengb.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hands a data-available notification to the `/proxy/usage` request that caused it.
 *
 * An asynchronous-batch custodian (Savage Data: Alectra, Hydro Ottawa) answers the subscription
 * batch URL with 202 and never serves the dataset from it. It serves the data from per-UsagePoint
 * resources beneath the subscription, and the only place it ever names those is a BatchList it
 * POSTs to `/notify/{utility}`. There is no listing endpoint to ask instead (it answers 404), so a
 * client that has never imported cannot learn its usage-point ids any other way.
 *
 * The custodian sends that notification WHILE it is still answering our GET — observed 300–400 ms
 * before the 202 arrives, on every poll. So the request registers what it [expect]s BEFORE it
 * calls upstream, the notify handler [deliver]s into whatever is registered, and on a 202 the
 * request collects what turned up. Registering after seeing the 202 would always be too late.
 *
 * This is NOT a notification store, and must not become one: nothing is kept for a later request.
 * An entry exists only for the lifetime of the request that registered it, and a notification
 * nobody is waiting for is dropped exactly as before. That keeps the server free of per-user
 * state between requests, and means scale-to-zero costs nothing — the request itself keeps the
 * machine up for as long as the entry lives.
 *
 * Limit: correlation is in-process. If the app ever runs more than one machine, a notification
 * can land on a different one from the request that is waiting for it; that request then reports
 * a plain 202 with no resources, which is the behaviour from before this existed.
 */
class BatchNotifications(
  /** How long a request that got a 202 waits for a notification that hasn't arrived yet. */
  private val wait: Duration = DEFAULT_WAIT,
) {
  private val expectations = ConcurrentHashMap.newKeySet<Expectation>()

  /** Register interest in resources notified beneath [subscriptionUri]. Close it when done. */
  fun expect(subscriptionUri: String): Expectation =
    Expectation("${subscriptionUri.trimEnd('/')}/").also { expectations += it }

  /** Offer the resource URLs of one notification to every request waiting on their subscription. */
  fun deliver(resourceUrls: Collection<String>) {
    for (expectation in expectations) expectation.offer(resourceUrls)
  }

  inner class Expectation internal constructor(
    private val prefix: String,
  ) : AutoCloseable {
    private val paths = CopyOnWriteArraySet<String>()
    private val arrived = CompletableDeferred<Unit>()

    internal fun offer(resourceUrls: Collection<String>) {
      val beneath =
        resourceUrls
          .filter { it.startsWith(prefix) }
          .map { it.removePrefix(prefix) }
          // /notify is unauthenticated, so this is caller-supplied text that we are about to hand
          // to a client as something to fetch. Only pass on what the proxy would itself accept
          // as a resource path — the same rule that keeps `resourcePath` from being an SSRF lever.
          .filter { isSafeResourcePath(it) }
      if (beneath.isNotEmpty()) {
        paths += beneath
        arrived.complete(Unit)
      }
    }

    /**
     * The resource paths notified so far, relative to the subscription (e.g. `UsagePoint/{id}`),
     * waiting briefly if none has arrived yet. Empty if the custodian sent nothing in time.
     */
    suspend fun await(): List<String> {
      withTimeoutOrNull(wait) { arrived.await() }
      return paths.toList()
    }

    override fun close() {
      expectations -= this
    }
  }

  private companion object {
    // The notification has beaten the 202 on every poll observed, so this is headroom for a slow
    // one — and the whole cost of a custodian that defers without ever notifying.
    val DEFAULT_WAIT = 2.seconds
  }
}

/**
 * Short, ESPI-shaped path segments only — letters, digits and hyphens, e.g. `UsagePoint` and
 * `UsagePoint/1c8dc9de-1c8f-5b47-9a35-4c98e6bd1ce1`.
 *
 * The rejections are the point: no scheme or authority (`:` and `//` are unmatched), no traversal
 * (`.` is unmatched, so `..` cannot appear), no absolute path (a leading `/` is unmatched), no
 * query or fragment (`?` and `#` are unmatched), and no percent-encoding (`%` is unmatched, so
 * `%2e%2e` can't smuggle traversal past this and get decoded downstream). Segment counts and
 * lengths are bounded so a caller can't build an unreasonable URL out of legal characters.
 */
private val RESOURCE_PATH_REGEX =
  Regex("""[A-Za-z][A-Za-z0-9]{0,31}(?:/[A-Za-z0-9][A-Za-z0-9-]{0,63}){0,3}""")

/** Whether [resourcePath] is a relative ESPI resource suffix that is safe to join onto a subscription URI. */
fun isSafeResourcePath(resourcePath: String): Boolean = RESOURCE_PATH_REGEX.matches(resourcePath)
