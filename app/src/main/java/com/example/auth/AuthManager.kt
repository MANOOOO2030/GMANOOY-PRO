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
    
    fun logout() {
        isGuestMode = true
        googleOAuthToken = null
    }
}
