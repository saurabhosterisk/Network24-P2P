package com.network24.player.core.compat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.network24.player.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Network24 needs Android 7.0 (API 24) - the in-app VPN (WireGuard) does
 * not run below it. minSdk is 21 only so older devices can install the app
 * and be told plainly, instead of Android's bare "App not installed". On
 * those devices the app shows this screen and nothing else runs; it points
 * them to N24 Dark Edition, the older app that still supports them, and can
 * download and install it right here.
 */
object UnsupportedDeviceGate {

    const val MIN_SUPPORTED_SDK = 24

    private const val DARK_EDITION_URL = "https://network24.biz/app/N24_Dark_Edition.apk"
    private const val DARK_EDITION_FILE = "N24_Dark_Edition.apk"

    val isUnsupported: Boolean
        get() = Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var downloading = false

    /** Replaces the activity's content with the fixed "device not supported" screen. */
    fun show(activity: Activity) {
        activity.setContentView(R.layout.activity_unsupported_device)
        activity.findViewById<TextView>(R.id.unsupportedDetail).text =
            "This device runs Android ${Build.VERSION.RELEASE}. " +
                "The new Network24 app needs Android 7.0 or newer."
        activity.findViewById<View>(R.id.unsupportedChip).background.mutate().setTint(
            // ContextCompat: this screen is exactly where API < 23 runs.
            ContextCompat.getColor(activity, R.color.primary).let {
                Color.argb(0x33, Color.red(it), Color.green(it), Color.blue(it))
            }
        )
        activity.findViewById<View>(R.id.unsupportedExit).setOnClickListener { activity.finishAffinity() }
        activity.findViewById<View>(R.id.unsupportedInstall).apply {
            setOnClickListener { startInstall(activity) }
            requestFocus()
        }
    }

    private fun startInstall(activity: Activity) {
        if (downloading) return
        downloading = true
        val button = activity.findViewById<TextView>(R.id.unsupportedInstall)
        val box = activity.findViewById<View>(R.id.unsupportedProgressBox)
        val bar = activity.findViewById<LinearProgressIndicator>(R.id.unsupportedProgress)
        val label = activity.findViewById<TextView>(R.id.unsupportedProgressText)
        button.isEnabled = false
        button.text = "DOWNLOADING…"
        box.visibility = View.VISIBLE
        bar.isIndeterminate = true
        label.text = "Starting download…"

        Thread {
            try {
                val file = download(activity) { done, total ->
                    main.post {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = (done * 100 / total).toInt()
                            label.text = "${done / 1_048_576} MB of ${total / 1_048_576} MB"
                        } else {
                            label.text = "${done / 1_048_576} MB downloaded"
                        }
                    }
                }
                main.post {
                    downloading = false
                    button.isEnabled = true
                    button.text = "INSTALL N24 DARK EDITION"
                    label.text = "Downloaded. Tap Install on the next screen."
                    openInstaller(activity, file)
                }
            } catch (e: Exception) {
                main.post {
                    downloading = false
                    button.isEnabled = true
                    button.text = "TRY AGAIN"
                    box.visibility = View.GONE
                    Toast.makeText(
                        activity,
                        "The download didn't work. Check your internet, or use the Downloader code 39645.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * Older Android does not trust the certificate network24.biz uses (Let's
     * Encrypt, ISRG Root X1/X2 arrived in Android 7.1.1), so a normal HTTPS
     * download fails there. If it does, try again trusting those two roots,
     * shipped in res/raw.
     */
    private fun download(context: Context, onProgress: (Long, Long) -> Unit): File {
        val dir = context.getExternalFilesDir(null) ?: context.cacheDir
        val file = File(dir, DARK_EDITION_FILE)
        return try {
            fetch(null, file, onProgress)
        } catch (e: SSLException) {
            fetch(letsEncryptSocketFactory(context), file, onProgress)
        }
    }

    private fun fetch(factory: SSLSocketFactory?, file: File, onProgress: (Long, Long) -> Unit): File {
        val connection = URL(DARK_EDITION_URL).openConnection() as HttpURLConnection
        if (factory != null && connection is HttpsURLConnection) {
            connection.sslSocketFactory = factory
        }
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLength.toLong()
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastReport >= 512 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            if (total > 0 && done != total) throw IllegalStateException("incomplete download")
            // The package installer reads the file itself on old Android.
            file.setReadable(true, false)
            return file
        } finally {
            connection.disconnect()
        }
    }

    private fun letsEncryptSocketFactory(context: Context): SSLSocketFactory {
        val certificates = CertificateFactory.getInstance("X.509")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, res ->
            context.resources.openRawResource(res).use {
                keyStore.setCertificateEntry("isrg_$i", certificates.generateCertificate(it))
            }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }

    private fun openInstaller(activity: Activity, file: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(activity, activity.packageName + ".provider", file)
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "This device can't open the installer. Use the Downloader code 39645.", Toast.LENGTH_LONG).show()
        }
    }
}
