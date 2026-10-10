package org.opengb.proxy

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SUBSCRIPTION = "https://dc.example/espi/1_1/resource/Batch/Subscription/42"

val BatchNotificationsTest by testSuite {
  test("a notification that arrived before the request asked is already there") {
    // The order seen in production: the custodian notifies before its 202 reaches us.
    val notifications = BatchNotifications(wait = 10.seconds)
    notifications.expect(SUBSCRIPTION).use { expectation ->
      notifications.deliver(listOf("$SUBSCRIPTION/UsagePoint/a1", "$SUBSCRIPTION/UsagePoint/b2"))
      assert(expectation.await() == listOf("UsagePoint/a1", "UsagePoint/b2"))
    }
  }

  test("a notification that arrives while the request is waiting releases it") {
    val notifications = BatchNotifications(wait = 10.seconds)
    notifications.expect(SUBSCRIPTION).use { expectation ->
      coroutineScope {
        val waiting = async { expectation.await() }
        delay(50.milliseconds)
        notifications.deliver(listOf("$SUBSCRIPTION/UsagePoint/a1"))
        assert(waiting.await() == listOf("UsagePoint/a1"))
      }
    }
  }

  test("nothing notified in time yields an empty list rather than hanging") {
    val notifications = BatchNotifications(wait = 50.milliseconds)
    notifications.expect(SUBSCRIPTION).use { assert(it.await().isEmpty()) }
  }

  test("every request waiting on a subscription hears the same notification") {
    val notifications = BatchNotifications(wait = 10.seconds)
    notifications.expect(SUBSCRIPTION).use { first ->
      notifications.expect("$SUBSCRIPTION/").use { second ->
        notifications.deliver(listOf("$SUBSCRIPTION/UsagePoint/a1"))
        assert(first.await() == listOf("UsagePoint/a1"))
        assert(second.await() == listOf("UsagePoint/a1"))
      }
    }
  }

  test("a subscription whose id merely starts the same is not confused with this one") {
    val notifications = BatchNotifications(wait = 50.milliseconds)
    notifications.expect(SUBSCRIPTION).use { expectation ->
      notifications.deliver(listOf("${SUBSCRIPTION}7/UsagePoint/a1"))
      assert(expectation.await().isEmpty())
    }
  }

  test("nothing is kept once the request is over") {
    // The property that keeps the server stateless between requests: a notification for a
    // subscription nobody is polling right now goes nowhere, even for the very next request.
    val notifications = BatchNotifications(wait = 50.milliseconds)
    notifications.expect(SUBSCRIPTION).close()
    notifications.deliver(listOf("$SUBSCRIPTION/UsagePoint/a1"))
    notifications.expect(SUBSCRIPTION).use { assert(it.await().isEmpty()) }
  }
}
