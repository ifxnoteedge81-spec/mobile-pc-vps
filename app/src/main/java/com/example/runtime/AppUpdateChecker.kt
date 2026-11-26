package com.example.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.BuildConfig
import com.example.security.NetworkAuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class InstalledAppIdentity(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val signingSha256: String
)

data class AppUpdateInfo(
    val isUpdateAvailable: Boolean,
    val latestVersion: String,
    val latestVersionCode: Long,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val changelog: List<String>
)

class AppUpdateChecker(
    private val context: Context,
    private val auditLogger: NetworkAuditLogger
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun getInstalledIdentity(): InstalledAppIdentity {
        val pkgName = context.packageName
        val (verName, verCode, sigSha) = runCatching {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, flags)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            val digest = certBytes?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString(":") { "%02X".format(it) }
            } ?: "Unknown"
            Triple(info.versionName ?: BuildConfig.VERSION_NAME, code, digest)
        }.getOrElse {
            Triple(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), "Verified")
        }
        return InstalledAppIdentity(
            applicationId = pkgName,
            versionName = verName,
            versionCode = verCode,
            signingSha256 = sigSha
        )
    }

    suspend fun checkLatestRelease(
        repoSlug: String = DEFAULT_REPO_SLUG
    ): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        val installed = getInstalledIdentity()
        // 1. Primary: Check version.json published directly on GitHub Releases latest
        val versionJsonUrl = "https://github.com/$repoSlug/releases/latest/download/version.json"
        val jsonResult = runCatching {
            val req = Request.Builder()
                .url(versionJsonUrl)
                .addHeader("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val raw = resp.body?.string().orEmpty()
                auditLogger.log("github.com", "App Update Check", "200 OK", raw.length.toLong())
                parseVersionJson(raw, installed.versionCode, installed.versionName, repoSlug)
            }
        }
        if (jsonResult.isSuccess) return@withContext jsonResult

        // 2. Fallback: GitHub Releases API (in case release only uploaded APK without version.json)
        runCatching {
            val apiUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
            val req = Request.Builder()
                .url(apiUrl)
                .addHeader("Accept", "application/vnd.github+json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("GitHub release check HTTP ${resp.code}")
                val raw = resp.body?.string().orEmpty()
                auditLogger.log("api.github.com", "Release Metadata Check", "200 OK", raw.length.toLong())
                parseGithubReleaseApi(raw, installed.versionCode, installed.versionName, repoSlug)
            }
        }
    }

    fun openInPlaceUpdateDownload(url: String): Result<Unit> = runCatching {
        val safeUrl = url.trim().takeIf { it.startsWith("https://") }
            ?: "https://github.com/$DEFAULT_REPO_SLUG/releases/latest"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        const val DEFAULT_REPO_SLUG = "ifxnoteedge81-spec/mobile-pc-vps"

        internal fun parseVersionJson(
            rawJson: String,
            installedVersionCode: Long,
            installedVersionName: String,
            repoSlug: String = DEFAULT_REPO_SLUG
        ): AppUpdateInfo {
            val obj = JSONObject(rawJson)
            val remoteVersion = obj.optString("version", "").ifBlank { "latest" }
            val remoteCode = obj.optLong("versionCode", 0L).takeIf { it > 0L }
                ?: extractRunNumber(remoteVersion)
            val downloadUrl = obj.optString("releaseDownloadUrl", "")
                .takeIf { it.startsWith("https://") }
                ?: "https://github.com/$repoSlug/releases/latest/download/personal-ai-computer.apk"
            val sha256 = obj.optString("sha256", "")
            val sizeBytes = obj.optLong("sizeBytes", 0L)
            val changelogArr = obj.optJSONArray("changelog")
            val changelog = buildList {
                if (changelogArr != null) {
                    for (i in 0 until changelogArr.length()) {
                        val line = changelogArr.optString(i).trim()
                        if (line.isNotEmpty()) add(line)
                    }
                }
            }
            val isNewer = isRemoteNewer(
                installedVersionCode = installedVersionCode,
                installedVersionName = installedVersionName,
                remoteVersionCode = remoteCode,
                remoteVersionLabel = remoteVersion
            )
            return AppUpdateInfo(
                isUpdateAvailable = isNewer,
                latestVersion = remoteVersion,
                latestVersionCode = remoteCode,
                downloadUrl = downloadUrl,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                changelog = changelog
            )
        }

        internal fun parseGithubReleaseApi(
            rawJson: String,
            installedVersionCode: Long,
            installedVersionName: String,
            repoSlug: String = DEFAULT_REPO_SLUG
        ): AppUpdateInfo {
            val obj = JSONObject(rawJson)
            val tag = obj.optString("tag_name", "latest")
            val name = obj.optString("name", tag)
            val assets = obj.optJSONArray("assets")
            var apkUrl = "https://github.com/$repoSlug/releases/latest"
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val assetName = a.optString("name")
                    if (assetName.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", apkUrl)
                        apkSize = a.optLong("size", 0L)
                        break
                    }
                }
            }
            val remoteCode = extractRunNumber(name).coerceAtLeast(extractRunNumber(tag))
            val bodyNotes = obj.optString("body", "")
                .lines()
                .map { it.trim().trimStart('-', '*', '•').trim() }
                .filter { it.isNotEmpty() }
                .take(4)
            val isNewer = isRemoteNewer(
                installedVersionCode = installedVersionCode,
                installedVersionName = installedVersionName,
                remoteVersionCode = remoteCode,
                remoteVersionLabel = name
            )
            return AppUpdateInfo(
                isUpdateAvailable = isNewer,
                latestVersion = name,
                latestVersionCode = remoteCode,
                downloadUrl = apkUrl,
                sha256 = "",
                sizeBytes = apkSize,
                changelog = bodyNotes
            )
        }

        internal fun isRemoteNewer(
            installedVersionCode: Long,
            installedVersionName: String,
            remoteVersionCode: Long,
            remoteVersionLabel: String
        ): Boolean {
            if (remoteVersionCode > 0L) {
                return remoteVersionCode > installedVersionCode
            }
            val installedPatch = installedVersionName.substringAfterLast('.', "").toLongOrNull() ?: 0L
            val remoteRun = extractRunNumber(remoteVersionLabel)
            return remoteRun > installedPatch && remoteRun > installedVersionCode
        }

        internal fun extractRunNumber(label: String): Long {
            val m = Regex("""(\d+)$""").find(label.trim())
            return m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }
    }
}
