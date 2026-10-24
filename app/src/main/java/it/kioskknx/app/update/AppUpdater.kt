package it.kioskknx.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import it.kioskknx.app.util.HttpActions
import it.kioskknx.app.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Aggiornamento dell'APK da URL. Con Device Owner l'installazione è silenziosa. */
object AppUpdater {
    private const val TAG = "Update"

    fun installFromUrlAsync(context: Context, url: String, onStatus: ((String) -> Unit)? = null) {
        HttpActions.scope.launch {
            val msg = try {
                installFromUrl(context.applicationContext, url)
            } catch (e: Exception) {
                L.e(TAG, "Aggiornamento fallito", e)
                "Errore: ${e.message}"
            }
            onStatus?.let { withContext(Dispatchers.Main) { it(msg) } }
        }
    }

    private fun installFromUrl(context: Context, url: String): String {
        L.i(TAG, "Download aggiornamento da $url")
        val (u, user, pass) = HttpActions.splitUserInfo(url, "", "")
        val file = File(context.cacheDir, "update.apk")
        HttpActions.clientFor(user, pass, streaming = true).newCall(Request.Builder().url(u).build()).execute().use { r ->
            if (!r.isSuccessful) return "Download fallito (HTTP ${r.code})"
            r.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        L.i(TAG, "APK scaricato (${file.length() / 1024} KB), installazione...")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("kioskknx", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, 3, Intent(context, UpdateReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
        return "Installazione avviata"
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                L.i("Update", "Serve conferma dell'utente per installare")
            }
            PackageInstaller.STATUS_SUCCESS -> L.i("Update", "Aggiornamento installato")
            else -> L.w("Update", "Installazione fallita: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
