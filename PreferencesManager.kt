package com.fmcall.serval.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.fmcall.serval.data.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("fmcall_prefs")

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_USER_ID       = stringPreferencesKey("user_id")
        val KEY_MESH_ID       = stringPreferencesKey("mesh_id")
        val KEY_DISPLAY_NAME  = stringPreferencesKey("display_name")
        val KEY_PRIVATE_KEY   = stringPreferencesKey("private_key")
        val KEY_PUBLIC_KEY    = stringPreferencesKey("public_key")
        val KEY_MESH_CHANNEL  = intPreferencesKey("mesh_channel")
        val KEY_WIFI_MESH     = booleanPreferencesKey("wifi_mesh")
        val KEY_BT_MESH       = booleanPreferencesKey("bt_mesh")
        val KEY_RELAY_MODE    = booleanPreferencesKey("relay_mode")
        val KEY_LANGUAGE      = stringPreferencesKey("language")
    }

    suspend fun saveUserProfile(profile: UserProfile) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_ID]      = profile.userId
            prefs[KEY_MESH_ID]      = profile.meshId
            prefs[KEY_DISPLAY_NAME] = profile.displayName
            prefs[KEY_PRIVATE_KEY]  = profile.privateKey
            prefs[KEY_PUBLIC_KEY]   = profile.publicKey
            prefs[KEY_MESH_CHANNEL] = profile.meshChannel
            prefs[KEY_WIFI_MESH]    = profile.wifiMeshEnabled
            prefs[KEY_BT_MESH]      = profile.bluetoothMeshEnabled
            prefs[KEY_RELAY_MODE]   = profile.relayModeEnabled
            prefs[KEY_LANGUAGE]     = profile.language
        }
    }

    suspend fun getUserProfile(): UserProfile? {
        val prefs = context.dataStore.data.first()
        val meshId = prefs[KEY_MESH_ID] ?: return null
        return UserProfile(
            userId              = prefs[KEY_USER_ID]      ?: "",
            meshId              = meshId,
            displayName         = prefs[KEY_DISPLAY_NAME] ?: "Utilisateur",
            privateKey          = prefs[KEY_PRIVATE_KEY]  ?: "",
            publicKey           = prefs[KEY_PUBLIC_KEY]   ?: "",
            meshChannel         = prefs[KEY_MESH_CHANNEL] ?: 6,
            wifiMeshEnabled     = prefs[KEY_WIFI_MESH]    ?: true,
            bluetoothMeshEnabled= prefs[KEY_BT_MESH]      ?: true,
            relayModeEnabled    = prefs[KEY_RELAY_MODE]   ?: false,
            language            = prefs[KEY_LANGUAGE]     ?: "fr"
        )
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
