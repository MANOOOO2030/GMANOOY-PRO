package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.ui.ChatScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.update.UpdateInfo
import com.example.update.UpdateManager

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
         var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
         val context = LocalContext.current
         val updateManager = remember { UpdateManager(context) }

         LaunchedEffect(Unit) {
             updateInfo = updateManager.checkForUpdate()
         }

         updateInfo?.let { info ->
             if (info.hasUpdate) {
                 AlertDialog(
                     onDismissRequest = { updateInfo = null },
                     title = { Text("Update Available") },
                     text = { Text("A new version (${info.latestVersion}) is available. Do you want to download and install it?") },
                     confirmButton = {
                         TextButton(onClick = {
                             updateManager.downloadAndInstall(info)
                             updateInfo = null
                         }) {
                             Text("Update")
                         }
                     },
                     dismissButton = {
                         TextButton(onClick = { updateInfo = null }) {
                             Text("Later")
                         }
                     }
                 )
             }
         }
         
         ChatScreen()
      }
    }
  }
}

