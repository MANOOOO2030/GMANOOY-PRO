package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.auth.AuthManager
import com.example.ui.ChatScreen
import com.example.ui.LoginScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val context = LocalContext.current
      val authManager = remember { AuthManager(context) }
      var isDarkTheme by remember { mutableStateOf(authManager.isDarkTheme) }

      // We pass the setter down if needed, but the ChatScreen ViewModel can also handle updating this or we inject a callback down.
      // Easiest is to pass through a composition local, or let ChatScreen update AuthManager and then notify.
      // Let's just create a quick flow/callback so it updates immediately.
      
      MyApplicationTheme(isDarkTheme = isDarkTheme) {
         
         // We do not have a persistent skipped state besides isGuestMode.
         // Let's assume if they don't have token and guest mode is false, show login.
         // By default AuthManager initializes isGuestMode=true, which might skip login immediately.
         // Let's introduce a proper flag or let isGuestMode handle it.
         // Wait, AuthManager starts isGuestMode = true by default. If we want them to see login on first launch:
         // Actually, if we just modify AuthManager to return false for isGuestMode by default, 
         // wait, isGuestMode=false means premium. Let's make isGuestMode=false and token=null the default, 
         // which means "Not logged in, Not Guest".
         
         var currentScreen by remember {
             mutableStateOf(
                 if (!authManager.isGuestMode && authManager.googleOAuthToken == null) "login"
                 else "chat"
             )
         }
         
         if (currentScreen == "login") {
             LoginScreen(
                 onLoginSuccess = { token, email ->
                     authManager.googleOAuthToken = token
                     authManager.googleUserEmail = email
                     authManager.isGuestMode = false
                     currentScreen = "chat"
                 },
                 onSkip = {
                     authManager.isGuestMode = true
                     currentScreen = "chat"
                 }
             )
         } else {
             ChatScreen(
                 onLogout = {
                     authManager.logout()
                     // We should reset AuthManager state to require login
                     authManager.googleOAuthToken = null
                     authManager.isGuestMode = false // force login screen since it's the "none" state
                     currentScreen = "login"
                 },
                 onThemeChange = { dark -> 
                     isDarkTheme = dark
                     authManager.isDarkTheme = dark
                 }
             )
         }
      }
    }
  }
}

