package app.cairn

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cairn.sensing.TrackingService
import app.cairn.ui.CairnViewModel
import app.cairn.ui.screens.JourneyScreen
import app.cairn.ui.screens.ModesScreen
import app.cairn.ui.screens.PrivacyScreen
import app.cairn.ui.screens.ShapesScreen
import app.cairn.ui.screens.TodayScreen
import app.cairn.ui.theme.CairnTheme
import app.cairn.ui.theme.Stone

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CairnTheme { CairnRoot() } }
    }
}

private enum class Tab(val label: String, val glyph: String) {
    TODAY("Jour", "◐"),
    JOURNEY("Parcours", "▲"),
    MODES("Modes", "▤"),
    SHAPES("Formes", "◌"),
    PRIVACY("Privé", "✦"),
}

@Composable
private fun CairnRoot() {
    val vm: CairnViewModel = viewModel()
    val data by vm.data.collectAsState()
    val settings by vm.settings.state.collectAsState()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(Tab.TODAY) }

    val sensorPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { TrackingService.start(context) }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) vm.settings.setUseGps(false) }

    // Le compteur matériel a continué pendant que l'application était fermée :
    // on le relève à chaque retour au premier plan pour rattraper les pas
    // manqués. C'est ce qui fait que Cairn compte les journées ordinaires.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vm.reload()
                vm.syncPassiveSteps()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = Stone.Void,
        bottomBar = { BottomBar(tab) { tab = it } },
    ) { padding ->
        // On applique les deux insets calculés par le Scaffold : la barre d'état
        // en haut, la barre de navigation + notre barre d'onglets en bas.
        // Sans le second, le dernier bloc de chaque écran passe sous les onglets.
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                )
        ) {
            when (tab) {
                Tab.TODAY -> TodayScreen(
                    today = data.today,
                    week = data.week,
                    streak = data.streak,
                    goal = settings.dailyGoal,
                    onShare = {
                        vm.shareToday(context, data)
                    },
                    onStart = {
                        val perms = buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                add(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            if (settings.useGps) add(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        if (perms.isEmpty()) TrackingService.start(context)
                        else sensorPermissions.launch(perms.toTypedArray())
                    },
                    onStop = { TrackingService.stop(context) },
                )
                Tab.JOURNEY -> JourneyScreen(
                    data = data,
                    onCorrectMode = { id, mode -> vm.correctMode(id, mode) },
                )
                Tab.MODES -> ModesScreen(data)
                Tab.SHAPES -> ShapesScreen(
                    data = data,
                    keepShapes = settings.keepShapes,
                    onShareShape = { session -> vm.shareShape(context, session) },
                )
                Tab.PRIVACY -> PrivacyScreen(
                    vm = vm,
                    data = data,
                    settings = settings,
                    onRequestLocationPermission = {
                        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                )
            }
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Stone.Hairline))
        Row(
            Modifier
                .fillMaxWidth()
                .background(Stone.Void)
                .navigationBarsPadding()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { t ->
                val selected = t == current
                val interaction = remember { MutableInteractionSource() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = interaction,
                        ) { onSelect(t) }
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                        .alpha(if (selected) 1f else 0.45f),
                ) {
                    Text(
                        t.glyph,
                        color = if (selected) Stone.Ochre else Stone.Muted,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t.label,
                        color = if (selected) Stone.Ink else Stone.Muted,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(if (selected) 16.dp else 0.dp)
                            .background(if (selected) Stone.Ochre else Color.Transparent)
                    )
                }
            }
        }
    }
}
