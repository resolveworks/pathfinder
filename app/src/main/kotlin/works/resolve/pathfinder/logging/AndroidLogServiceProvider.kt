package works.resolve.pathfinder.logging

import android.util.Log
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

private const val API_VERSION = "2.0.18"

class AndroidLogServiceProvider : SLF4JServiceProvider {
    private val loggerFactory = AndroidLogLoggerFactory()
    private val markerFactory = BasicMarkerFactory()

    override fun getLoggerFactory(): ILoggerFactory = loggerFactory

    override fun getMarkerFactory(): IMarkerFactory = markerFactory

    override fun getMDCAdapter(): MDCAdapter = NOPMDCAdapter()

    override fun getRequestedApiVersion(): String = API_VERSION

    override fun initialize() {}
}

private class AndroidLogLoggerFactory : ILoggerFactory {
    private val loggers = java.util.concurrent.ConcurrentHashMap<String, Logger>()

    override fun getLogger(name: String): Logger =
        loggers.computeIfAbsent(name) { AndroidLogLogger(it) }
}

private class AndroidLogLogger(name: String) : LegacyAbstractLogger() {
    init {
        this.name = name
    }

    private val tag: String = name.substringAfterLast('.')

    override fun getFullyQualifiedCallerName(): String? = null

    // Debug/trace stay off even in debug builds: cbssh logs full exec
    // commands (tool arguments) at debug level.
    override fun isTraceEnabled(): Boolean = false

    override fun isDebugEnabled(): Boolean = false

    override fun isInfoEnabled(): Boolean = debugBuild

    override fun isWarnEnabled(): Boolean = true

    override fun isErrorEnabled(): Boolean = true

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?
    ) {
        try {
            val priority =
                when (level) {
                    Level.ERROR -> Log.ERROR
                    Level.WARN -> Log.WARN
                    Level.INFO -> Log.INFO
                    Level.DEBUG -> Log.DEBUG
                    Level.TRACE -> Log.VERBOSE
                }
            var message = MessageFormatter.basicArrayFormat(messagePattern, arguments)
            if (throwable != null) {
                message = message + '\n' + Log.getStackTraceString(throwable)
            }
            Log.println(priority, tag, message)
        } catch (_: Exception) {
            // Logging must never throw.
        }
    }
}

private val debugBuild = works.resolve.pathfinder.BuildConfig.DEBUG
