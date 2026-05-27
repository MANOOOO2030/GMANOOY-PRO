package com.example.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean,
    val publishedAt: String,
    val downloadUrl: String
)

class UpdateManager(private val context: Context) {

    // IMPORTANT: Replace with your actual GitHub Owner and Public Repo Names
    private val githubOwner = "YOUR_GITHUB_OWNER"
    private val githubRepo = "YOUR_PUBLIC_REPO_NAME"
    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                
                val publishedAt = jsonObject.getString("published_at")
                val assets = jsonObject.getJSONArray("assets")
                
                var downloadUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                
                if (downloadUrl.isNotEmpty()) {
                    val localTimestamp = prefs.getString("last_update_timestamp", "") ?: ""
                    val isNewer = localTimestamp.isEmpty() || publishedAt > localTimestamp
                    return@withContext UpdateInfo(isNewer, publishedAt, downloadUrl)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    fun downloadAndInstall(updateInfo: UpdateInfo) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(updateInfo.downloadUrl)
        
        val apkFileName = "update_${System.currentTimeMillis()}.apk"
        
        val request = DownloadManager.Request(uri)
            .setTitle("Downloading Update")
            .setDescription("Downloading latest release")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, apkFileName)

        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    if (context != null) {
                        installApk(context, downloadId, downloadManager, updateInfo.publishedAt)
                        try {
                            context.unregisterReceiver(this)
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, downloadId: Long, downloadManager: DownloadManager, publishedAt: String) {
        try {
            val contentUri = downloadManager.getUriForDownloadedFile(downloadId)
            if (contentUri != null) {
                // Update the local timestamp
                prefs.edit().putString("last_update_timestamp", publishedAt).apply()
                
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
