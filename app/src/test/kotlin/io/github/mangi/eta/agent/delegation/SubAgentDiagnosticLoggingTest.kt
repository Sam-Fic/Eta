package io.github.mangi.eta.agent.delegation

import android.app.Application
import android.content.Context
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.core.FileLogSink
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SubAgentDiagnosticLoggingTest {
    /** Drive the same settings-switch -> file-logger binding shape as EtaApp, without starting runtime work. */
    private suspend fun withLogging(block: suspend (suspend (Boolean) -> Unit) -> Unit) = coroutineScope {
        val context = RuntimeEnvironment.getApplication()
        SettingsDataStore.init(context)
        // Robolectric reuses Kotlin singletons but replaces Application/filesDir per test.
        // Tear down the previous logger before binding this test's real application directory.
        AppFileLogger.setEnabled(false)
        AppFileLogger.clear()
        val loggerClass = AppFileLogger::class.java
        val installed = loggerClass.getDeclaredField("installed").apply { isAccessible = true }
            .get(null) as AtomicBoolean
        if (installed.getAndSet(false)) {
            val previousHandler = loggerClass.getDeclaredField("previousCrashHandler").apply {
                isAccessible = true
            }.get(null) as Thread.UncaughtExceptionHandler?
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        AppFileLogger.install(context)
        val before = SettingsDataStore.settings()
        // 与 EtaApp 相同的接线形状（开关流 -> AppFileLogger），但开关值由测试持有的
        // StateFlow 承载，不再订阅 SettingsDataStore.fileLoggingEnabledFlow()：
        // 它的 data 流在 CI 高负载下出现过 30s 不投递（本用例曾因此超时），
        // 而本类被测对象是 AppFileLogger 的写入门控与 logcat 生命周期，不是 DataStore
        // 的冷启动投递。设置落库本身仍在 setLogging 里按真实存储往返校验。
        val settingsEnabled = MutableStateFlow(before.fileLoggingEnabled)
        val binding = launch(start = CoroutineStart.UNDISPATCHED) {
            settingsEnabled.collect { enabled -> AppFileLogger.setEnabled(enabled) }
        }
        val setLogging: suspend (Boolean) -> Unit = { value ->
            withTimeout(30_000) { SettingsDataStore.setFileLoggingEnabled(value) }
            // 真实存储往返：写入后直接读快照，不依赖 data 流何时广播。
            assertEquals(value, withTimeout(30_000) {
                SettingsDataStore.settings().fileLoggingEnabled
            })
            settingsEnabled.value = value
            // 投递是同一事件循环上的下一跳：用有界等待确认已应用，而不是假设调度顺序。
            // 若值本就相同，StateFlow 不会重复发射，但收集器在订阅时已应用过当前值。
            withTimeout(30_000) {
                while (AppFileLogger.isEnabled() != value) yield()
            }
        }
        try {
            setLogging(false)
            AppFileLogger.clear()
            ShadowLog.clear()
            block(setLogging)
        } finally {
            binding.cancelAndJoin()
            AppFileLogger.setEnabled(false)
            AppFileLogger.clear()
            SettingsDataStore.updateSettings { before }
        }
    }

    @Test fun globalSwitchAloneControlsActualFileAndLogcatOutputForAllLegacyKeyValues() = runBlocking {
        withLogging { setLogging ->
            val context = RuntimeEnvironment.getApplication()
            val preferences = context.getSharedPreferences("diagnostic-legacy-${UUID.randomUUID()}", Context.MODE_PRIVATE)
            val repo = ConversationSubAgentPreferences(preferences)
            listOf<Any?>(null, false, true, "obsolete", JSONObject.NULL).forEachIndexed { index, legacy ->
                val archive = JSONObject().put("version", 1).put("enabled", true)
                    .put("agents", JSONArray()).put("parallel_limits", JSONArray())
                if (legacy != null) archive.put("diagnostics_enabled", legacy)
                val owner = SubAgentConfigKey.Conversation("legacy-$index")
                assertTrue(repo.importOwner(owner, archive.toString()))
                assertTrue(repo.snapshot(owner).enabled)
                assertFalse(JSONObject(repo.export(owner)).has("diagnostics_enabled"))
                // Create while disabled, then keep the same object through all toggles, as retained tasks do.
                val diagnostics = SubAgentDiagnostics("private-run-$index")
                val disabled = "disabled_$index"
                val enabled = "enabled_$index"
                val stopped = "stopped_$index"
                val resumed = "resumed_$index"
                val existing = appLogText()
                diagnostics.mark(disabled)
                assertEquals(existing, appLogText())
                assertFalse(logcatDiagnostics().contains(disabled))

                setLogging(true)
                diagnostics.mark(enabled, providerId = "private-provider", model = "private-model",
                    failure = IllegalStateException("HTTP 429 private-key private-body"))
                assertTrue(appLogText().contains("\"stage\":\"$enabled\""))
                assertTrue(logcatDiagnostics().contains("\"stage\":\"$enabled\""))
                val recorded = appLogText()
                setLogging(false)
                diagnostics.mark(stopped)
                assertEquals(recorded, appLogText())
                assertFalse(logcatDiagnostics().contains(stopped))
                assertTrue(AppFileLogger.hasLogs()) // Disabled retains previously recorded diagnostics.

                setLogging(true)
                diagnostics.mark(resumed)
                assertTrue(appLogText().contains("\"stage\":\"$resumed\""))
                setLogging(false)
                for (secret in listOf("private-run-$index", "private-provider", "private-model", "private-key", "private-body")) {
                    assertFalse(appLogText().contains(secret))
                    assertFalse(logcatDiagnostics().contains(secret))
                }
            }
        }
    }

    @Test fun childDiagnosticsUseExistingRotationExportAndClearPaths() = runBlocking {
        withLogging { setLogging ->
            val diagnostics = SubAgentDiagnostics("private-parent")
            setLogging(true)
            diagnostics.mark("before_clear", agentId = "private-agent")
            // Force the shared app file to rotate; no separate child file or export/clear path exists.
            AppFileLogger.info("x".repeat(FileLogSink.DEFAULT_MAX_BYTES.toInt()))
            diagnostics.mark("after_rotation")
            AppFileLogger.flush()
            val loggerDirectory = AppFileLogger::class.java.getDeclaredField("logsDir").apply {
                isAccessible = true
            }.get(null) as File
            val rotationEvidence = "expected=${logsDir()}, actual=$loggerDirectory, " +
                "files=" + loggerDirectory.listFiles().orEmpty().joinToString { "${it.name}:${it.length()}" } +
                ", errors=" + ShadowLog.getLogsForTag(ModuleConfig.TAG).filter {
                    it.msg.startsWith("diagnostic append failed:")
                }.joinToString { it.msg }
            assertEquals(rotationEvidence, logsDir().canonicalFile, loggerDirectory.canonicalFile)
            assertTrue(rotationEvidence, File(logsDir(), "eta-app.1.log").isFile)
            setLogging(false)
            val zip = ByteArrayOutputStream()
            assertTrue(AppFileLogger.export(zip) >= 2)
            val entries = ZipInputStream(ByteArrayInputStream(zip.toByteArray())).use { input ->
                val result = linkedMapOf<String, String>()
                while (true) {
                    val entry = input.nextEntry ?: break
                    result[entry.name] = input.readBytes().toString(Charsets.UTF_8)
                }
                result
            }
            val text = entries.values.joinToString("\n")
            assertTrue(text.contains("\"stage\":\"before_clear\""))
            assertTrue(text.contains("\"stage\":\"after_rotation\""))
            assertFalse(text.contains("private-parent"))
            assertFalse(text.contains("private-agent"))
            assertTrue(entries.keys.any { it == "eta-app.1.log" || it == "eta-app.2.log" })

            AppFileLogger.clear()
            assertFalse(AppFileLogger.isEnabled())
            assertFalse(AppFileLogger.hasLogs())
            diagnostics.mark("after_disabled_clear")
            assertFalse(AppFileLogger.hasLogs())
            assertFalse(logcatDiagnostics().contains("after_disabled_clear"))

            setLogging(true)
            diagnostics.mark("before_enabled_clear")
            AppFileLogger.clear()
            assertTrue(AppFileLogger.isEnabled())
            assertFalse(appLogText().contains("before_enabled_clear"))
            assertFalse(appLogText().contains("before_clear"))
            diagnostics.mark("after_enabled_clear")
            assertTrue(appLogText().contains("after_enabled_clear"))
        }
    }

    @Test fun enabledWritesDoNotWaitForTheLifecycleLock() = runBlocking {
        withLogging { setLogging ->
            setLogging(true)
            assertCompletesWhileLockHeld(loggerLock("lock")) {
                AppFileLogger.info("ordinary_without_lifecycle_wait")
                AppFileLogger.diagnosticInfo("SubAgentDiag diagnostic_without_lifecycle_wait")
            }
            assertTrue(appLogText().contains("ordinary_without_lifecycle_wait"))
            assertTrue(appLogText().contains("diagnostic_without_lifecycle_wait"))
            assertTrue(logcatDiagnostics().contains("diagnostic_without_lifecycle_wait"))
        }
    }

    @Test fun disabledWritesReturnWithoutAcquiringTheWriteGate() = runBlocking {
        withLogging { _ ->
            // withLogging already consumed the initial disabled delivery. The distinct
            // settings flow does not emit again when false is written a second time.
            assertFalse(AppFileLogger.isEnabled())
            assertCompletesWhileLockHeld(loggerLock("writeLock")) {
                AppFileLogger.info("disabled_ordinary_gate")
                AppFileLogger.diagnosticInfo("SubAgentDiag disabled_diagnostic_gate")
            }
            assertFalse(AppFileLogger.hasLogs())
            assertFalse(logcatDiagnostics().contains("disabled_diagnostic_gate"))
        }
    }

    @Test fun writesReturnWhileDisableWaitsForLogcatShutdown() = runBlocking {
        withLogging { setLogging ->
            setLogging(true)
            AppFileLogger.info("before_blocked_disable")
            val before = appLogText()
            assertWritesReturnDuringShutdown(clear = false)
            assertFalse(AppFileLogger.isEnabled())
            assertEquals(before, appLogText())
        }
    }

    @Test fun writesReturnWhileClearWaitsAndOldSinkCannotReappear() = runBlocking {
        withLogging { setLogging ->
            setLogging(true)
            AppFileLogger.info("before_blocked_clear")
            val oldSink = loggerField("appSink") as FileLogSink
            assertWritesReturnDuringShutdown(clear = true)
            assertTrue(AppFileLogger.isEnabled())
            oldSink.append("stale_sink_after_clear")
            AppFileLogger.info("fresh_after_clear")
            val text = appLogText()
            assertFalse(text.contains("before_blocked_clear"))
            assertFalse(text.contains("stale_sink_after_clear"))
            assertFalse(text.contains("during_blocked_shutdown"))
            assertTrue(text.contains("fresh_after_clear"))
        }
    }

    // The test thread performs the writes (Robolectric's main thread). A timeout is
    // returned to JUnit, not thrown and lost on the lock-holder thread.
    private fun assertCompletesWhileLockHeld(lock: ReentrantLock, block: () -> Unit) {
        val acquired = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val holder = FutureTask<Boolean> {
            lock.withLock {
                acquired.countDown()
                completed.await(5, TimeUnit.SECONDS)
            }
        }
        Thread(holder, "logger-lock-holder").apply { isDaemon = true }.start()
        try {
            assertTrue("holder acquired lock", acquired.await(5, TimeUnit.SECONDS))
            block()
        } finally {
            completed.countDown()
        }
        assertTrue("logging must finish before lifecycle/gate lock is released", holder.get(10, TimeUnit.SECONDS))
    }

    private fun assertWritesReturnDuringShutdown(clear: Boolean) {
        val process = BlockingShutdownProcess()
        @Suppress("UNCHECKED_CAST")
        val processRef = loggerField("logcatProcess") as AtomicReference<Process?>
        val previous = processRef.getAndSet(process)
        previous?.destroyForcibly()
        val shutdown = FutureTask<Unit> {
            if (clear) AppFileLogger.clear() else AppFileLogger.setEnabled(false)
        }
        val worker = Thread(shutdown, "logger-lifecycle-test").apply { isDaemon = true }
        worker.start()
        try {
            assertTrue("shutdown entered process wait", process.entered.await(5, TimeUnit.SECONDS))
            assertFalse(AppFileLogger.isEnabled())
            AppFileLogger.info("during_blocked_shutdown_ordinary")
            AppFileLogger.diagnosticInfo("SubAgentDiag during_blocked_shutdown_diagnostic")
            assertFalse("shutdown must still be blocked when writes return", process.timedOut.get())
            assertFalse(appLogText().contains("during_blocked_shutdown"))
            assertFalse(logcatDiagnostics().contains("during_blocked_shutdown"))
        } finally {
            process.release.countDown()
            shutdown.get(10, TimeUnit.SECONDS)
            worker.join(1_000)
        }
    }

    private class BlockingShutdownProcess : Process() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timedOut = AtomicBoolean(false)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun destroy() = Unit
        override fun exitValue() = 0
        override fun waitFor(): Int { waitFor(5, TimeUnit.SECONDS); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            entered.countDown()
            // An explicit latch holds shutdown open; the guard bounds failures even
            // when testing the old blocking implementation. No sleeps or polling.
            if (!release.await(5, TimeUnit.SECONDS)) timedOut.set(true)
            return true
        }
    }

    private fun loggerField(name: String): Any? = AppFileLogger::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(null)

    private fun loggerLock(name: String) = loggerField(name) as ReentrantLock

    private fun logsDir() = File(RuntimeEnvironment.getApplication().filesDir, AppFileLogger.DIRECTORY_NAME)
    private fun appLogText(): String = logsDir().listFiles().orEmpty()
        .filter { it.name.startsWith("eta-app") && it.isFile }
        .joinToString("\n") { it.readText() }
    private fun logcatDiagnostics(): String = ShadowLog.getLogsForTag(ModuleConfig.TAG)
        .filter { it.msg.startsWith("SubAgentDiag ") }.joinToString("\n") { it.msg }
}
