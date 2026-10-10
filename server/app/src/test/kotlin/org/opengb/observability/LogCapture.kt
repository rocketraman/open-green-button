package org.opengb.observability

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.message.StringMapMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs [block] and returns what [loggerName] emitted meanwhile, one map per event: the structured
 * fields of a map message, or `message` → text for a plain one.
 *
 * What reaches the log is the whole subject of the privacy tests, and it is invisible to an HTTP
 * assertion — hence reading it off the logger itself rather than inferring it from a response.
 */
fun captureLogs(
  loggerName: String,
  block: () -> Unit,
): List<Map<String, String>> {
  val events = CopyOnWriteArrayList<Map<String, String>>()
  val appender =
    object : AbstractAppender("capture-${System.nanoTime()}", null, null, true, Property.EMPTY_ARRAY) {
      override fun append(event: LogEvent) {
        // Copied at once: log4j may reuse the message object after append returns.
        val message = event.message
        events +=
          if (message is StringMapMessage) HashMap(message.data) else mapOf("message" to message.formattedMessage)
      }
    }
  appender.start()
  // The tests never call boot(), so nothing has raised these loggers above log4j's ERROR default.
  Configurator.setLevel(loggerName, Level.INFO)
  val logger = LogManager.getLogger(loggerName) as Logger
  logger.addAppender(appender)
  try {
    block()
  } finally {
    logger.removeAppender(appender)
    appender.stop()
  }
  return events
}
