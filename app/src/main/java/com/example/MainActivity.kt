package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import com.example.auth.AuthManager
import com.example.ui.ChatScreen
import com.example.ui.LoginScreen
import com.example.ui.theme.MyApplicationTheme

val LanguageTrigger = mutableStateOf("")

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val authManagerInstance = AuthManager(this)
    val lang = authManagerInstance.language
    val initialLangCode = if (lang == "System Default (Auto)" || lang == "System Default") {
        if (java.util.Locale.getDefault().language == "ar") "ar" else "en"
    } else {
        if (lang == "العربية" || lang == "Arabic") "ar" else if (lang == "French") "fr" else "en"
    }
    
    LanguageTrigger.value = initialLangCode

    enableEdgeToEdge()
    setContent {
      val context = LocalContext.current
      val langCode by LanguageTrigger

      val updatedConfig = remember(langCode) {
          val locale = java.util.Locale(langCode)
          java.util.Locale.setDefault(locale)
          val config = android.content.res.Configuration(context.resources.configuration)
          config.setLocale(locale)
          context.createConfigurationContext(config) // updates resources contextually
          context.resources.updateConfiguration(config, context.resources.displayMetrics)
          config
      }

      CompositionLocalProvider(
          LocalConfiguration provides updatedConfig,
          androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
      ) {
          val authManager = remember { AuthManager(context) }
          var isDarkTheme by remember { mutableStateOf(authManager.isDarkTheme) }
          
          MyApplicationTheme(isDarkTheme = isDarkTheme) {
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
                         authManager.googleOAuthToken = null
                         authManager.isGuestMode = false
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
}

