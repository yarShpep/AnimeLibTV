package org.example.atvretranslation.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

object UpdateManager {
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/yarShpep/AnimeLibTV/main/update.json"
    private const val PREFS = "app_updates"
    private const val PENDING_APK = "pending_apk"
    private const val APK_NAME = "AnimeLibTV-update.apk"

    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = openConnection(MANIFEST_URL)
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "GitHub вернул HTTP ${connection.responseCode}"
            }
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val update = AppUpdate(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256").lowercase(),
                notes = json.optString("notes"),
            )
            update.takeIf { it.versionCode > installedVersionCode(context) }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndInstall(context: Context, update: AppUpdate) =
        withContext(Dispatchers.IO) {
            val directory = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val target = File(directory, APK_NAME)
            val temporary = File(directory, "$APK_NAME.part")
            temporary.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            val connection = openConnection(update.apkUrl)
            try {
                require(connection.responseCode in 200..299) {
                    "Скачивание APK: HTTP ${connection.responseCode}"
                }
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == update.sha256) { "SHA-256 обновления не совпал" }
            val archivePackage = context.packageManager
                .getPackageArchiveInfo(temporary.absolutePath, 0)
                ?.packageName
            require(archivePackage == context.packageName) { "APK имеет другой package name" }

            target.delete()
            require(temporary.renameTo(target)) { "Не удалось сохранить APK" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PENDING_APK, target.absolutePath).apply()
            promptInstallOrPermission(context, target)
        }

    fun installPendingIfAllowed(context: Context) {
        if (!canInstallPackages(context)) return
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = preferences.getString(PENDING_APK, null)?.let(::File) ?: return
        if (!file.isFile) {
            preferences.edit().remove(PENDING_APK).apply()
            return
        }
        preferences.edit().remove(PENDING_APK).apply()
        launchInstaller(context, file)
    }

    private fun promptInstallOrPermission(context: Context, apk: File) {
        if (canInstallPackages(context)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(PENDING_APK).apply()
            launchInstaller(context, apk)
            return
        }
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
            setRequestProperty("User-Agent", "AnimeLibTV-Updater")
        }
}
