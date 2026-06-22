package com.orbit.remote.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orbit.remote.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "orbit_settings")

/**
 * Persists the device identity (so the same id/code survives reinstall-less restarts),
 * the trusted controllers whitelist and the signaling server URL.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val CODE = stringPreferencesKey("code")
        val SIGNALING_URL = stringPreferencesKey("signaling_url")
        val TRUSTED = stringSetPreferencesKey("trusted_controllers")
    }

    val deviceId: Flow<String?> = context.dataStore.data.map { it[Keys.DEVICE_ID] }
    val code: Flow<String?> = context.dataStore.data.map { it[Keys.CODE] }

    val signalingUrl: Flow<String> = context.dataStore.data.map {
        it[Keys.SIGNALING_URL] ?: BuildConfig.DEFAULT_SIGNALING_URL
    }

    val trustedControllers: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.TRUSTED] ?: emptySet()
    }

    suspend fun saveIdentity(deviceId: String, code: String) {
        context.dataStore.edit {
            it[Keys.DEVICE_ID] = deviceId
            it[Keys.CODE] = code
        }
    }

    suspend fun setSignalingUrl(url: String) {
        context.dataStore.edit { it[Keys.SIGNALING_URL] = url }
    }

    suspend fun addTrustedController(fingerprint: String) {
        context.dataStore.edit {
            it[Keys.TRUSTED] = (it[Keys.TRUSTED] ?: emptySet()) + fingerprint
        }
    }

    suspend fun isTrusted(fingerprint: String): Boolean =
        trustedControllers.first().contains(fingerprint)
}
