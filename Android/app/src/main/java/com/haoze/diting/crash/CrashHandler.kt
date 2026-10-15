package com.haoze.diting.crash

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Global uncaught-exception handler. Registered via
 * [Thread.setDefaultUncaughtExceptionHandler]; on a crash it collects detailed
 * diagnostics to disk, then safely hands the exception over to the system's
 * original handler.
 */
class CrashHandler private constructor(
    private val appContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    private val isHandling = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (!isHandling.compareAndSet(false, true)) {
            // Guard against an infinite loop if crash collection itself throws
            // another uncaught exception.
            defaultHandler?.uncaughtException(thread, throwable) ?: exitProcess(2)
            return
        }

        try {
            CrashBreadcrumbs.record(
                tag = "CRASH",
                message = "Uncaught exception in [${thread.name}]: ${throwable.javaClass.name}: ${throwable.message}",
                level = "FATAL"
            )

            // Fast path: capture only cheap diagnostics and persist them with a
            // synchronous fsync. This must complete before the process dies, and
            // is deliberately small so it still succeeds under OOM / a wedged
            // runtime, where the full report below would itself fail.
            val minimal = CrashCollector.collectMinimalReport(
                thread = thread,
                throwable = throwable,
                topActivityName = currentActivityName,
                isForeground = isAppForeground
            )
            val file = CrashLogManager.saveCrashReport(appContext, minimal)
            Log.e(TAG, "DITING Crash detected! Minimal report saved to: ${file?.absolutePath}")

            // Best-effort enrichment, run inline. It cannot be deferred to
            // another thread: the default handler tears the process down as soon
            // as this method returns, so a detached thread would be killed before
            // finishing. The minimal report written above stays in place if this
            // step fails, which keeps the "a report always lands" guarantee while
            // restoring the full diagnostics.
            val full = runCatching {
                CrashCollector.collectReport(
                    context = appContext,
                    thread = thread,
                    throwable = throwable,
                    topActivityName = currentActivityName,
                    isForeground = isAppForeground
                )
            }.getOrNull()
            if (full != null) {
                if (file != null) {
                    CrashLogManager.replaceCrashReport(appContext, file, full)
                    Log.i(TAG, "Enriched crash report at ${file.absolutePath}")
                } else {
                    CrashLogManager.saveCrashReport(appContext, full)
                }
            } else {
                Log.w(TAG, "Failed to collect the full crash report; keeping the minimal one")
            }

            // Crash bookkeeping (consecutive-crash count + threshold export) must
            // also run inline, for the same reason. It runs after the enrichment
            // so the auto-exported bundle carries the full report.
            CrashLogManager.onCrashOccurred(appContext)
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error while capturing crash report", e)
        } finally {
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                exitProcess(2)
            }
        }
    }

    companion object {
        private const val TAG = "CrashHandler"

        @Volatile
        var currentActivityName: String? = null

        @Volatile
        var isAppForeground: Boolean = false

        private var installed = false

        @Synchronized
        fun install(context: Context) {
            if (installed) return
            val appContext = context.applicationContext ?: context
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            val handler = CrashHandler(appContext, defaultHandler)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            installed = true
            Log.i(TAG, "Global UncaughtExceptionHandler installed successfully")
        }
    }
}
