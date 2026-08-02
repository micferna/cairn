package app.cairn.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.cairn.R
import app.cairn.data.Archive
import app.cairn.data.Settings
import app.cairn.domain.LedgerKind
import app.cairn.ui.CairnViewModel
import app.cairn.ui.Fmt
import app.cairn.ui.components.KeyValue
import app.cairn.ui.components.Panel
import app.cairn.ui.components.Pill
import app.cairn.ui.components.SectionLabel
import app.cairn.ui.theme.Stone

@Composable
fun PrivacyScreen(
    vm: CairnViewModel,
    data: CairnViewModel.Snapshot,
    settings: Settings.Snapshot,
    onRequestLocationPermission: () -> Unit,
) {
    val context = LocalContext.current

    // On interroge le système sur nos propres permissions et on les affiche
    // brutes. L'application n'a pas à être crue sur parole : elle se dénonce.
    val declared = remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }
    val hasInternetPermission = declared.any {
        it == Manifest.permission.INTERNET || it == Manifest.permission.ACCESS_NETWORK_STATE
    }

    var pendingWipe by remember { mutableStateOf(false) }
    var exportPayload by remember { mutableStateOf<String?>(null) }
    var importReport by remember { mutableStateOf<Archive.ImportResult?>(null) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (json == null) {
            importReport = Archive.ImportResult(0, 0, "Fichier illisible.")
        } else {
            vm.importJson(json) { importReport = it }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = exportPayload
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(payload.toByteArray())
                }
            }
        }
        exportPayload = null
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 18.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(
                    "Confidentialité",
                    color = Stone.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                )
                Text("vérifiable, pas déclarative", color = Stone.Faint, fontSize = 11.sp)
            }
        }
        item { NetworkGuaranteePanel(hasInternetPermission) }
        item { PermissionsPanel(declared) }
        item { UpdatesPanel() }
        item { StoredDataPanel(data) }
        item { GoalPanel(vm, settings) }
        item { TogglesPanel(vm, settings, onRequestLocationPermission) }
        item {
            DataActionsPanel(
                onExport = {
                    vm.export { json ->
                        exportPayload = json
                        saveLauncher.launch("cairn-export.json")
                    }
                },
                onWipe = { pendingWipe = true },
                onImport = { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
        }
        ledgerSection(data)
    }

    ImportReportDialog(importReport) { importReport = null }
    if (pendingWipe) {
        WipeDialog(
            sessionCount = data.sessionCount,
            onDismiss = { pendingWipe = false },
            onWipeData = { vm.wipe(includingLedger = false); pendingWipe = false },
            onWipeAll = { vm.wipe(includingLedger = true); pendingWipe = false },
        )
    }
}

@Composable
private fun ImportReportDialog(report: Archive.ImportResult?, onDismiss: () -> Unit) {
    if (report == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Stone.Raised,
        title = {
            Text(
                if (report.error != null) "Import impossible" else "Import terminé",
                color = Stone.Ink,
            )
        },
        text = {
            Text(
                report.error ?: buildString {
                    append("${Fmt.int(report.imported)} déplacements ajoutés.")
                    if (report.skipped > 0) {
                        append("\n${Fmt.int(report.skipped)} étaient déjà présents ")
                        append("et ont été ignorés.")
                    }
                },
                color = Stone.Muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer", color = Stone.Ochre) }
        },
    )
}

@Composable
private fun WipeDialog(
    sessionCount: Long,
    onDismiss: () -> Unit,
    onWipeData: () -> Unit,
    onWipeAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Stone.Raised,
        title = { Text("Tout effacer ?", color = Stone.Ink) },
        text = {
            Text(
                "${Fmt.int(sessionCount)} déplacements seront détruits, et la base " +
                    "compactée pour qu'il n'en reste rien sur le disque. " +
                    "Cette action est irréversible : il n'existe aucune copie ailleurs.\n\n" +
                    "Par défaut le registre de transparence est conservé — il gardera la " +
                    "trace de cette suppression. Vous pouvez aussi le remettre à zéro et " +
                    "revenir à l'état d'installation.",
                color = Stone.Muted,
            )
        },
        confirmButton = {
            // Le registre survit : il garde la preuve de la suppression.
            TextButton(onClick = onWipeData) { Text("Effacer les données", color = Stone.Alert) }
        },
        dismissButton = {
            TextButton(onClick = onWipeAll) { Text("Tout, registre compris", color = Stone.Alert) }
        },
    )
}

@Composable
private fun Toggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                color = if (enabled) Stone.Ink else Stone.Faint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Stone.Faint, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Stone.Void,
                checkedTrackColor = Stone.Ochre,
                uncheckedThumbColor = Stone.Faint,
                uncheckedTrackColor = Stone.Surface,
                uncheckedBorderColor = Stone.Hairline,
            ),
        )
    }
}

private fun LedgerKind.tint() = when (this) {
    LedgerKind.WRITE -> Stone.Ochre
    LedgerKind.SENSOR_OPEN -> Stone.Lichen
    LedgerKind.SENSOR_CLOSE -> Stone.Faint
    LedgerKind.DISCARD -> Stone.Faint
    LedgerKind.DELETE -> Stone.Alert
    LedgerKind.EXPORT -> Stone.Lichen
}

/** Le registre, sorti du corps de l'écran pour qu'il reste lisible. */
private fun androidx.compose.foundation.lazy.LazyListScope.ledgerSection(
    data: CairnViewModel.Snapshot,
) {
    item { LedgerHeaderPanel(data) }
    items(data.ledger) { entry -> LedgerRow(entry) }
    if (data.ledger.isEmpty()) {
        item {
            Text(
                "Le registre est vide : rien n'a encore été écrit.",
                color = Stone.Faint, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun NetworkGuaranteePanel(hasInternetPermission: Boolean) {
    Panel(accent = if (hasInternetPermission) Stone.Alert else Stone.Lichen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                if (hasInternetPermission) "RÉSEAU POSSIBLE" else "AUCUN ACCÈS RÉSEAU",
                if (hasInternetPermission) Stone.Alert else Stone.Lichen,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (hasInternetPermission) {
                "Attention : cette version déclare une permission réseau. " +
                    "Ce n'est pas la configuration attendue de Cairn."
            } else {
                "Cairn ne déclare pas la permission INTERNET. Le noyau refuse à ce " +
                    "processus l'ouverture de toute connexion : l'application ne peut " +
                    "rien transmettre, même si son code était compromis, même si une " +
                    "bibliothèque tierce essayait."
            },
            color = Stone.Ink, fontSize = 14.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Ce n'est pas une promesse contractuelle qu'on pourrait changer dans une " +
                "mise à jour des conditions d'utilisation. C'est le système " +
                "d'exploitation qui l'applique, et la liste ci-dessous vient de lui.",
            color = Stone.Faint, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.tagline),
            color = if (hasInternetPermission) Stone.Alert else Stone.Lichen,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PermissionsPanel(declared: List<String>) {
    val context = LocalContext.current
    Panel {
        SectionLabel("Permissions déclarées par cette application")
        Spacer(Modifier.height(4.dp))
        Text(
            "Lues à l'instant auprès du système, pas écrites en dur.",
            color = Stone.Faint, fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        declared.sorted().forEach { perm ->
            val granted = ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    perm.removePrefix("android.permission."),
                    color = Stone.Muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (granted) "accordée" else "refusée",
                    color = if (granted) Stone.Lichen else Stone.Faint,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Absentes et jamais demandées : INTERNET, ACCESS_NETWORK_STATE, " +
                "ACCESS_BACKGROUND_LOCATION, READ_CONTACTS, RECORD_AUDIO.",
            color = Stone.Faint, fontSize = 11.sp, lineHeight = 16.sp,
        )
    }
}

@Composable
private fun UpdatesPanel() {
    Panel {
        SectionLabel("Mises à jour")
        Spacer(Modifier.height(10.dp))
        KeyValue("Version installée", app.cairn.BuildConfig.VERSION_NAME)
        KeyValue("Code de version", app.cairn.BuildConfig.VERSION_CODE.toString())
        Spacer(Modifier.height(12.dp))
        Text(
            "Cairn ne peut pas vérifier lui-même s'il existe une nouvelle version : " +
                "cela demanderait un accès réseau, et donc de renoncer à la garantie " +
                "ci-dessus.",
            color = Stone.Ink, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "C'est le gestionnaire de paquets qui s'en charge : installez Cairn via " +
                "Obtainium ou F-Droid, et c'est lui — qui a le réseau, pas nous — qui " +
                "vous notifiera à chaque publication et proposera l'installation.\n\n" +
                "Le résultat est identique pour vous : une notification, un bouton. " +
                "La différence, c'est que l'application qui compte vos pas n'a toujours " +
                "aucun moyen de parler à l'extérieur.",
            color = Stone.Muted, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "github.com/micferna/cairn",
            color = Stone.Ochre, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StoredDataPanel(data: CairnViewModel.Snapshot) {
    Panel {
        SectionLabel("Ce que Cairn détient sur vous")
        Spacer(Modifier.height(10.dp))
        KeyValue("Taille du fichier de données", Fmt.bytes(data.dbBytes))
        KeyValue("Déplacements enregistrés", Fmt.int(data.sessionCount))
        KeyValue("Coordonnées géographiques", "0", Stone.Lichen)
        KeyValue("Identifiants d'appareil", "0", Stone.Lichen)
        KeyValue("Compte utilisateur", "aucun", Stone.Lichen)
        KeyValue("Bibliothèques d'analyse tierces", "aucune", Stone.Lichen)
        KeyValue("Sauvegarde cloud automatique", "désactivée", Stone.Lichen)
        Spacer(Modifier.height(12.dp))
        Text(
            "L'export ci-dessous produit l'intégralité de ce fichier, en clair. " +
                "S'il vous paraît inoffensif, c'est que la promesse est tenue.",
            color = Stone.Faint, fontSize = 11.sp, lineHeight = 16.sp,
        )
    }
}

/**
 * L'objectif quotidien et son pas de réglage.
 *
 * 7 000 par défaut plutôt que les 10 000 du folklore : ce chiffre vient d'une
 * campagne publicitaire japonaise des années 1960, pas d'une étude.
 */
@Composable
private fun GoalPanel(vm: CairnViewModel, settings: Settings.Snapshot) {
    Panel {
        SectionLabel("Objectif quotidien")
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (settings.dailyGoal == 0) "Désactivé"
                else "${Fmt.int(settings.dailyGoal)} pas",
                color = Stone.Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            )
            Row {
                OutlinedButton(
                    onClick = { vm.settings.setDailyGoal(settings.dailyGoal - GOAL_STEP) },
                    enabled = settings.dailyGoal > 0,
                ) { Text("−", color = Stone.Ink) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { vm.settings.setDailyGoal(settings.dailyGoal + GOAL_STEP) },
                ) { Text("+", color = Stone.Ink) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Sert à la série de jours et au widget. Aucun classement, aucune comparaison " +
                "avec d'autres : vous contre vous.",
            color = Stone.Faint, fontSize = 11.sp, lineHeight = 16.sp,
        )
    }
}

private const val GOAL_STEP = 500

@Composable
private fun TogglesPanel(
    vm: CairnViewModel,
    settings: Settings.Snapshot,
    onRequestLocationPermission: () -> Unit,
) {
    Panel {
        SectionLabel("Ce que vous autorisez")
        Spacer(Modifier.height(4.dp))
        Text(
            "Tout est fermé par défaut. Vous ouvrez, en sachant ce que vous échangez.",
            color = Stone.Faint, fontSize = 11.sp,
        )
        Spacer(Modifier.height(14.dp))

        Toggle(
            title = "Compter les pas en continu",
            subtitle = "Relève le compteur du podomètre matériel à chaque ouverture de " +
                "l'application. Le capteur compte de lui-même dans son coprocesseur, " +
                "que Cairn tourne ou non : aucun service en arrière-plan, aucune " +
                "consommation, aucune information de lieu. Sans ça, seules les " +
                "sessions démarrées à la main sont comptées.",
            checked = settings.passiveSteps,
            onChange = { vm.settings.setPassiveSteps(it) },
        )
        Spacer(Modifier.height(16.dp))
        Toggle(
            title = "Mesurer les véhicules avec le GPS",
            subtitle = "Sans ça, seuls la marche et la course sont mesurés — mais " +
                "aucune permission de localisation n'est jamais demandée. " +
                "Avec, les coordonnées vivent en mémoire vive le temps d'un calcul " +
                "d'écart, puis sont écrasées.",
            checked = settings.useGps,
            onChange = { on ->
                vm.settings.setUseGps(on)
                if (on) onRequestLocationPermission()
            },
        )
        Spacer(Modifier.height(16.dp))
        Toggle(
            title = "Conserver la forme des parcours",
            subtitle = "Le dessin du trajet, pivoté au hasard, sans échelle ni origine. " +
                "Nécessite le GPS ci-dessus.",
            checked = settings.keepShapes,
            enabled = settings.useGps,
            onChange = { vm.settings.setKeepShapes(it) },
        )
        Spacer(Modifier.height(16.dp))
        Toggle(
            title = "Arrondir les horaires",
            subtitle = "« Court tous les mardis à 6h32 » est une signature aussi " +
                "identifiante qu'une adresse. On ne garde que l'heure.",
            checked = settings.quantizeTime,
            onChange = { vm.settings.setQuantizeTime(it) },
        )
    }
}

@Composable
private fun DataActionsPanel(onExport: () -> Unit, onWipe: () -> Unit, onImport: () -> Unit) {
    Panel {
        SectionLabel("Vos données vous appartiennent")
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text("Exporter tout en JSON lisible", color = Stone.Ink)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("Réimporter un export", color = Stone.Ink)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "La sauvegarde automatique étant désactivée par construction, l'export est " +
                "le seul moyen d'emmener votre historique sur un nouveau téléphone. " +
                "L'import est additif : réimporter deux fois le même fichier ne duplique rien.",
            color = Stone.Faint, fontSize = 11.sp, lineHeight = 16.sp,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onWipe,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Stone.Alert.copy(alpha = WIPE_TINT_ALPHA),
                contentColor = Stone.Alert,
            ),
        ) {
            Text("Tout effacer définitivement")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "L'effacement est immédiat, sans corbeille et sans copie ailleurs — " +
                "il n'y a pas d'ailleurs.",
            color = Stone.Faint, fontSize = 11.sp,
        )
    }
}

@Composable
private fun LedgerHeaderPanel(data: CairnViewModel.Snapshot) {
    Panel {
        SectionLabel("Registre de transparence")
        Spacer(Modifier.height(4.dp))
        Text(
            "Chaque écriture sur le disque et chaque capteur ouvert laisse une trace " +
                "ici. ${Fmt.int(data.ledgerCount)} lignes, " +
                "${Fmt.bytes(data.bytesWritten)} déclarés écrits.",
            color = Stone.Muted, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun LedgerRow(entry: app.cairn.domain.LedgerEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Stone.Surface)
            .border(1.dp, Stone.Hairline, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(entry.kind.tint())
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    entry.kind.label,
                    color = entry.kind.tint(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    Fmt.clockMs(entry.atMs),
                    color = Stone.Faint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (entry.bytes > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "+${Fmt.bytes(entry.bytes.toLong())}",
                        color = Stone.Faint, fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(entry.detail, color = Stone.Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

private const val WIPE_TINT_ALPHA = 0.16f
