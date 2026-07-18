package nl.vdzon.softwarefactory.softwarefactory_dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native hulp voor het "App-updates"-scherm (Flutter kan de package-installer niet zelf
 * aanroepen). Blootgesteld via een MethodChannel in [MainActivity]. Welke versie de nieuwste is
 * (GitHub Releases) wordt aan de Dart-kant bepaald (zie `lib/app_updates.dart`) — dit gaat alleen
 * over "installeer dit bestand". Zelfde recept als robberts-assistent's
 * `nl.vdzon.robberts_assistent.UpdateInstaller`.
 */
class UpdateInstaller(private val context: Context) {

    /** Of deze app toestemming heeft om APK's te installeren (Android 8+, per-app-toestemming). */
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opent het systeeminstellingenscherm waar de gebruiker "installeren van deze app toestaan" aanzet. */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Downloadt [downloadUrl] naar de cache-map en start de systeem-package-installer voor
     * [fileName]. Gooit een [java.io.IOException] bij een netwerk-/schrijffout — de caller (Dart,
     * via de MethodChannel) vertaalt dat naar een foutmelding in de UI.
     */
    fun downloadAndInstall(downloadUrl: String, fileName: String) {
        val target = File(context.cacheDir, fileName)
        val connection = URL(downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", target)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }
}
