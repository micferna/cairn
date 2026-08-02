package app.cairn.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import app.cairn.data.CairnRepository
import app.cairn.domain.LedgerKind
import java.io.File

/**
 * Remise de l'image au système, sans le moindre octet de réseau côté Cairn.
 *
 * Le PNG est écrit dans le cache, exposé par un `FileProvider` non exporté, et
 * confié au sélecteur de partage d'Android. C'est l'application choisie par
 * l'utilisateur — messagerie, réseau social, mail, Bluetooth — qui possède la
 * permission INTERNET et effectue l'envoi.
 *
 * Cette séparation est la même que pour les mises à jour : quand une capacité
 * exige le réseau, on la délègue à un composant qui l'a déjà, plutôt que de
 * l'accorder à l'application qui compte vos pas.
 */
object Sharing {

    private const val DIR = "shared"
    private const val AUTHORITY_SUFFIX = ".shares"
    private const val QUALITY = 100

    /**
     * Écrit la carte et ouvre le sélecteur. Une seule image est conservée à la
     * fois : la précédente est écrasée, rien ne s'accumule dans le cache.
     */
    fun shareCard(context: Context, bitmap: Bitmap, subject: String) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, "cairn.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, QUALITY, it) }

        val uri = FileProvider.getUriForFile(
            context, context.packageName + AUTHORITY_SUFFIX, file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, subject)
            putExtra(Intent.EXTRA_TEXT, "$subject — mesuré avec Cairn, sans aucune donnée de localisation.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Partager").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )

        CairnRepository.get(context).ledger.record(
            LedgerKind.EXPORT,
            "Carte partagée : image générée dans le cache et remise au système. " +
                "Elle ne contient que des chiffres et une forme déjà anonymisée.",
            file.length().toInt(),
        )
    }
}
