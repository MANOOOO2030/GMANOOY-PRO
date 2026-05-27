package com.example.auth

import android.content.Context
import android.content.SharedPreferences

class AuthManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    var isGuestMode: Boolean
        get() = prefs.getBoolean("is_guest_mode", false)
        set(value) = prefs.edit().putBoolean("is_guest_mode", value).apply()

    var googleOAuthToken: String?
        get() = prefs.getString("google_oauth_token", null)
        set(value) = prefs.edit().putString("google_oauth_token", value).apply()

    var googleUserEmail: String?
        get() = prefs.getString("google_user_email", null)
        set(value) = prefs.edit().putString("google_user_email", value).apply()
        
    var guestMessageCount: Int
        get() = prefs.getInt("guest_message_count", 0)
        private set(value) = prefs.edit().putInt("guest_message_count", value).apply()

    fun incrementGuestMessageCount(): Int {
        val currentCount = guestMessageCount + 1
        guestMessageCount = currentCount
        return currentCount
    }
    
    fun resetGuestQuota() {
        guestMessageCount = 0
    }

    fun hasReachedGuestQuota(limit: Int = 5): Boolean {
        return isGuestMode && guestMessageCount >= limit
    }
    
    var isDarkTheme: Boolean
        get() = prefs.getBoolean("is_dark_theme", true)
        set(value) = prefs.edit().putBoolean("is_dark_theme", value).apply()

    var language: String
        get() = prefs.getString("language", "System Default (Auto)") ?: "System Default (Auto)"
        set(value) = prefs.edit().putString("language", value).apply()

    var selectedVoice: String
        get() = prefs.getString("selected_voice", "Aoede") ?: "Aoede"
        set(value) = prefs.edit().putString("selected_voice", value).apply()

    fun logout() {
        isGuestMode = true
        googleOAuthToken = null
        googleUserEmail = null
    }

    fun factoryReset() {
        prefs.edit().clear().apply()
    }
}
