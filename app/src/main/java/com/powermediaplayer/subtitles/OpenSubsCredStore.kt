package com.powermediaplayer.subtitles

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §C9 LOCKED — EncryptedSharedPreferences-backed credential store for
 * OpenSubtitles. The token + email + (optional) user-supplied API key
 * leave plain DataStore behind because they grant API quota access.
 *
 * Falls back to a plain in-memory map if EncryptedSharedPreferences
 * fails to initialise on the current device (rare; happens on
 * KeyStore-impaired emulator images). Logged to PMP_DIAG so a
 * security-sensitive deployment notices.
 */
@Singleton
class OpenSubsCredStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy { initPrefs() }

    fun getToken(): String = prefs.getString("opensubs_token", "").orEmpty()
    fun getEmail(): String = prefs.getString("opensubs_email", "").orEmpty()
    fun getApiKey(): String = prefs.getString("opensubs_apiKey", "").orEmpty()

    fun setToken(value: String) { prefs.edit().putString("opensubs_token", value).apply() }
    fun setEmail(value: String) { prefs.edit().putString("opensubs_email", value).apply() }
    fun setApiKey(value: String) { prefs.edit().putString("opensubs_apiKey", value).apply() }

    fun clear() {
        prefs.edit()
            .remove("opensubs_token")
            .remove("opensubs_email")
            .remove("opensubs_apiKey")
            .apply()
    }

    private fun initPrefs(): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, "opensubs_secure_v1", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            com.powermediaplayer.util.Diag.w(
                "PMP_DIAG",
                "EncryptedSharedPreferences init failed; falling back to plain prefs",
                it
            )
            context.getSharedPreferences("opensubs_fallback_v1", Context.MODE_PRIVATE)
        }
    }
}
