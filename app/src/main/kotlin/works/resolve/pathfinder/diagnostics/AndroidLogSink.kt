package works.resolve.pathfinder.diagnostics

import android.util.Log

/** Debug-build Logcat backend for sanitized [DiagnosticEntry] values. */
internal object AndroidLogSink : DiagnosticSink {
    override fun record(entry: DiagnosticEntry) {
        val priority = when (entry.event.level) {
            DiagnosticLevel.INFO -> Log.INFO
            DiagnosticLevel.WARN -> Log.WARN
            DiagnosticLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, TAG, entry.message())
    }

    private const val TAG = "Pathfinder"
}
