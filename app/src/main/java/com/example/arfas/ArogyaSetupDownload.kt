package com.example.arfas

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Setup downloads through Android's DownloadManager: they continue in the background and after the app is
 * closed, resume by themselves when the signal comes back, show a notification, and can be limited to Wi-Fi.
 * Files go to the app's own folder (no storage permission); the download id is remembered so the wizard
 * picks up the same download after a restart.
 */
object ArogyaSetupDownload {
    private const val TAG = "ArogyaDemo"
    private const val PREFS = "arogya_setup"

    data class State(val running: Boolean, val ok: Boolean, val failed: Boolean, val done: Long, val total: Long, val reason: String, val waitingForWifi: Boolean)

    private fun dm(c: Context) = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    fun file(c: Context, name: String): File = File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: c.filesDir, name)
    fun savedId(c: Context, name: String): Long = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("dl_$name", -1L)
    /** Which model source the remembered download uses: 0 = primary (Cloudflare), 1 = fallback (Hugging Face). */
    fun source(c: Context, name: String): Int = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("src_$name", 0)
    fun setSource(c: Context, name: String, src: Int) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("src_$name", src).apply()
    private fun saveId(c: Context, name: String, id: Long) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("dl_$name", id).apply()

    /** Starts (or restarts) a download; returns its id. */
    fun start(c: Context, url: String, name: String, title: String, wifiOnly: Boolean): Long {
        savedId(c, name).takeIf { it >= 0 }?.let { runCatching { dm(c).remove(it) } }
        file(c, name).delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("Pramaan setup")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        val id = dm(c).enqueue(req)
        saveId(c, name, id)
        if (url == ArogyaSetupPlan.MODELS_URL_PRIMARY) setSource(c, name, 0)
        else if (url == ArogyaSetupPlan.MODELS_URL_FALLBACK) setSource(c, name, 1)
        Log.i(TAG, "AROGYA_SETUP_DOWNLOAD start name=$name wifiOnly=$wifiOnly id=$id")
        return id
    }

    fun cancel(c: Context, name: String) {
        savedId(c, name).takeIf { it >= 0 }?.let { runCatching { dm(c).remove(it) } }
        saveId(c, name, -1L); file(c, name).delete()
        Log.i(TAG, "AROGYA_SETUP_DOWNLOAD cancel name=$name")
    }

    /** Current state of the remembered download for [name], or null when there is none. */
    fun query(c: Context, name: String): State? {
        val id = savedId(c, name).takeIf { it >= 0 } ?: return null
        return runCatching {
            dm(c).query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                if (!cur.moveToFirst()) return@use null
                val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reasonCode = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val waitingWifi = status == DownloadManager.STATUS_PAUSED && reasonCode == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                val reason = when {
                    status == DownloadManager.STATUS_FAILED -> when (reasonCode) {
                        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough free space"
                        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "the server refused the download (HTTP)"
                        DownloadManager.ERROR_CANNOT_RESUME -> "the download could not resume"
                        else -> "download error $reasonCode"
                    }
                    status == DownloadManager.STATUS_PAUSED -> when (reasonCode) {
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "waiting for Wi-Fi"
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for internet"
                        DownloadManager.PAUSED_WAITING_TO_RETRY -> "connection dropped — retrying"
                        else -> "paused"
                    }
                    else -> ""
                }
                State(running = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED,
                    ok = status == DownloadManager.STATUS_SUCCESSFUL, failed = status == DownloadManager.STATUS_FAILED,
                    done = done.coerceAtLeast(0), total = total, reason = reason, waitingForWifi = waitingWifi)
            }
        }.getOrNull()
    }

    /** The finished download as a content:// link (carries its file name, like a picked file). */
    fun contentUri(c: Context, name: String): Uri? = savedId(c, name).takeIf { it >= 0 }?.let { runCatching { dm(c).getUriForDownloadedFile(it) }.getOrNull() }

    /** Download finished and installed: forget it and free the space. */
    fun finish(c: Context, name: String) {
        savedId(c, name).takeIf { it >= 0 }?.let { runCatching { dm(c).remove(it) } }
        saveId(c, name, -1L); file(c, name).delete()
    }
}
