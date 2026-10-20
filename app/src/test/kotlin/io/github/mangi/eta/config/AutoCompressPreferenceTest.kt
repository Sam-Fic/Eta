package io.github.mangi.eta.config

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AutoCompressPreferenceTest {
    private val key = Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED

    private fun preferences(): TrackingPreferences = TrackingPreferences(
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "auto-compress-${UUID.randomUUID()}", Context.MODE_PRIVATE,
        ),
    )

    private fun drainMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun missingKeyAndUnavailableStorageUseTheCanonicalFalseDefault() {
        val prefs = preferences()
        val preference = AutoCompressPreference(prefs)
        assertEquals(false, Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key))
        assertFalse(preference.enabled)
        assertFalse(AutoCompressPreference.read(prefs))
        assertFalse(prefs.contains(key)) // Reading/observing must not seed or migrate a value.
        val missing = AutoCompressPreference(null)
        val observed = mutableListOf<Boolean>()
        val observation = missing.observe(observed::add)
        missing.setEnabled(true)
        drainMain()
        assertFalse(missing.isWritable)
        assertFalse(missing.enabled)
        assertEquals(listOf(false), observed)
        observation.close()
    }

    @Test fun menuAndSettingsWritesBothReachTheOtherObserverAndNextExecutionRead() {
        val prefs = preferences()
        val menu = AutoCompressPreference(prefs)
        val settings = AutoCompressPreference(prefs)
        val menuValues = mutableListOf<Boolean>()
        val settingsValues = mutableListOf<Boolean>()
        val menuObservation = menu.observe(menuValues::add)
        val settingsObservation = settings.observe(settingsValues::add)
        drainMain()

        // Menu ON -> settings ON, then settings OFF -> menu OFF.
        menu.setEnabled(true)
        assertTrue(AutoCompressPreference.read(prefs))
        drainMain()
        assertTrue(menuValues.last())
        assertTrue(settingsValues.last())
        settings.setEnabled(false)
        assertFalse(AutoCompressPreference.read(prefs))
        drainMain()
        assertFalse(menuValues.last())
        assertFalse(settingsValues.last())

        // Exercise the inverse direction as well.
        settings.setEnabled(true)
        assertTrue(AutoCompressPreference.read(prefs))
        drainMain()
        assertTrue(menuValues.last())
        menu.setEnabled(false)
        assertFalse(AutoCompressPreference.read(prefs))
        drainMain()
        assertEquals(listOf(false, true, false, true, false), menuValues)
        assertEquals(menuValues, settingsValues)
        menuObservation.close()
        settingsObservation.close()
        assertEquals(0, prefs.listeners.size)
    }

    @Test fun externalWriteRemovalAndClearRefreshBothObservers() {
        val prefs = preferences()
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        val one = AutoCompressPreference(prefs).observe(first::add)
        val two = AutoCompressPreference(prefs).observe(second::add)
        prefs.edit().putBoolean(key, true).commit()
        drainMain()
        assertTrue(first.last())
        assertTrue(second.last())
        prefs.edit().remove(key).commit()
        drainMain()
        assertFalse(first.last())
        assertFalse(second.last())
        prefs.edit().putBoolean(key, true).commit()
        drainMain()
        prefs.edit().clear().commit()
        // Android R+ can report clear as a null key, including backup/import replacement.
        prefs.notifyChanged(null)
        drainMain()
        assertFalse(first.last())
        assertFalse(second.last())
        assertFalse(AutoCompressPreference.read(prefs))
        one.close()
        two.close()
    }

    @Test fun backgroundNotificationsPublishOnMainAndReadLatestInsteadOfCapturedValues() {
        val prefs = preferences()
        val callbackLoopers = mutableListOf<Looper?>()
        val values = mutableListOf<Boolean>()
        val observation = AutoCompressPreference(prefs).observe {
            values.add(it)
            callbackLoopers.add(Looper.myLooper())
        }
        drainMain()
        onWorker {
            prefs.edit().putBoolean(key, true).commit()
            prefs.notifyChanged(key) // Also cover a preference adapter notifying on its writer thread.
            prefs.edit().putBoolean(key, false).commit()
            prefs.notifyChanged(key)
        }
        assertEquals(listOf(false), values)
        assertFalse(AutoCompressPreference.read(prefs))
        drainMain()
        assertTrue(callbackLoopers.all { it == Looper.getMainLooper() })
        assertTrue(values.all { !it })
        observation.close()
    }

    @Test fun registrationRereadsStorageAndReentryDoesNotRestoreAStaleSnapshot() {
        val prefs = preferences()
        val preference = AutoCompressPreference(prefs)
        assertFalse(preference.enabled) // Value at first composition.
        prefs.afterRegister = { prefs.edit().putBoolean(key, true).commit() }
        val first = mutableListOf<Boolean>()
        val firstObservation = preference.observe(first::add)
        drainMain()
        assertTrue(first.last())
        firstObservation.close()
        prefs.afterRegister = null
        prefs.edit().putBoolean(key, false).commit()
        drainMain()
        assertTrue(first.last()) // Disposed screen is no longer updated.
        val reopened = AutoCompressPreference(prefs)
        assertFalse(reopened.enabled)
        val second = mutableListOf<Boolean>()
        val secondObservation = reopened.observe(second::add)
        drainMain()
        assertEquals(listOf(false), second)
        secondObservation.close()
        assertEquals(0, prefs.listeners.size)
    }

    @Test fun closeUnregistersAndSuppressesQueuedAndLateCallbacks() {
        val prefs = preferences()
        val values = mutableListOf<Boolean>()
        val observation = AutoCompressPreference(prefs).observe(values::add)
        drainMain()
        assertEquals(1, prefs.listeners.size)
        val oldListener = prefs.listeners.single()
        onWorker { oldListener.onSharedPreferenceChanged(prefs, key) }
        observation.close()
        observation.close() // Idempotent disposal/owner shutdown.
        assertEquals(0, prefs.listeners.size)
        assertEquals(1, prefs.unregisterCount)
        prefs.edit().putBoolean(key, true).commit()
        onWorker { oldListener.onSharedPreferenceChanged(prefs, key) }
        drainMain()
        assertEquals(listOf(false), values)
    }

    @Test fun unrelatedKeysAndDifferentPreferenceScopesCannotChangeTheSwitch() {
        val prefs = preferences()
        val otherPrefs = preferences()
        val values = mutableListOf<Boolean>()
        val observation = AutoCompressPreference(prefs).observe(values::add)
        drainMain()
        otherPrefs.edit().putBoolean(key, true).commit()
        prefs.edit()
            .putBoolean(Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED, true)
            .putString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, "test-endpoint")
            .putString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, "compression-provider")
            .commit()
        val unrelated = prefs.all.filterKeys { it != key }
        drainMain()
        assertEquals(listOf(false), values)
        AutoCompressPreference(prefs).setEnabled(true)
        AutoCompressPreference(prefs).setEnabled(false)
        assertEquals(unrelated, prefs.all.filterKeys { it != key })
        assertTrue(AutoCompressPreference.read(otherPrefs))
        assertFalse(AutoCompressPreference.read(prefs))
        observation.close()
    }

    private fun onWorker(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                block()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        worker.join()
        failure.get()?.let { throw it }
    }

    private class TrackingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        var unregisterCount = 0
        var afterRegister: (() -> Unit)? = null

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners.add(listener)
            delegate.registerOnSharedPreferenceChangeListener(listener)
            afterRegister?.invoke()
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            unregisterCount++
            listeners.remove(listener)
            delegate.unregisterOnSharedPreferenceChangeListener(listener)
        }

        fun notifyChanged(key: String?) {
            listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
        }
    }
}
