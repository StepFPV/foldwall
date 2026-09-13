package com.stepdesign.foldwall

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    // Re-read on every resume: the user leaves this screen to set the wallpaper and
    // comes straight back, and a stale "not active yet" line would be wrong.
    private val wallpaperActive = mutableStateOf(false)

    // Recorded by the wallpaper engine while it is on screen, so it only grows while the
    // user is away from this screen. Re-read on resume rather than observed live.
    private val observedRange = mutableStateOf<ClosedFloatingPointRange<Float>?>(null)

    // The one step that fails silently: granted in Settings, then the user comes back and
    // has to press again. Re-read on resume so the button can say so.
    private val canOverlay = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = FoldWallColors) {
                Surface(
                    color = FoldWallColors.background,
                    contentColor = FoldWallColors.onBackground,
                ) {
                    FoldWallScreen(wallpaperActive.value, observedRange.value, canOverlay.value)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wallpaperActive.value =
            WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName
        observedRange.value = FoldObserved.read(this)
        canOverlay.value = Settings.canDrawOverlays(this)
    }
}

private val FoldWallColors = darkColorScheme(
    primary = Color(0xFF8FD3FF),
    onPrimary = Color(0xFF06222F),
    secondary = Color(0xFFB9A7FF),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF141821),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1C2130),
    onSurfaceVariant = Color(0xFFA9B3C6),
    outline = Color(0xFF394054),
)

private val SWATCHES = listOf(
    0xFF7FC6FF, 0xFF43F5D0, 0xFFB8FF6E, 0xFFFFD166, 0xFFFF8A5B,
    0xFFFF5D8F, 0xFFB13AD8, 0xFF6C5CE7, 0xFF3A6FD8, 0xFFFFFFFF,
).map { it.toInt() }

private val BACKGROUND_SWATCHES = listOf(
    0xFF101A2E, 0xFF2E1030, 0xFF06231F, 0xFF2B1206, 0xFF1A1A1A,
    0xFF3A0A22, 0xFF0A2540, 0xFF241436, 0xFF00363A, 0xFF000000,
).map { it.toInt() }

@Composable
private fun FoldWallScreen(
    wallpaperActive: Boolean,
    observed: ClosedFloatingPointRange<Float>?,
    canOverlay: Boolean,
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(FoldSettings.load(context)) }
    var openness by remember { mutableFloatStateOf(1f) }
    var autoSweep by remember { mutableStateOf(true) }
    var followHinge by remember { mutableStateOf(false) }
    var rawAngle by remember { mutableFloatStateOf(Float.NaN) }

    fun update(next: FoldSettings) {
        settings = next
        FoldSettings.save(context, next)
    }

    // One source for both the readout and "follow the hinge". The 0.4° gate keeps a
    // chatty sensor from recomposing the whole screen on every event.
    val hinge = remember {
        HingeSource(context) { angle ->
            if (rawAngle.isNaN() || abs(angle - rawAngle) > 0.4f) rawAngle = angle
        }
    }
    DisposableEffect(hinge) {
        hinge.start()
        onDispose { hinge.stop() }
    }

    // The service can also stop itself — the user revokes capture from the system chip,
    // or another app takes the projection — so the switch follows the service, not a pref.
    var overlayRunning by remember { mutableStateOf(OverlayFoldService.isRunning) }
    DisposableEffect(Unit) {
        val listener: (Boolean) -> Unit = { overlayRunning = it }
        OverlayFoldService.addListener(listener)
        onDispose { OverlayFoldService.removeListener(listener) }
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            OverlayFoldService.start(context, result.resultCode, data)
        } else {
            Toast.makeText(context, "Registrazione schermo non autorizzata", Toast.LENGTH_SHORT)
                .show()
        }
    }

    fun askForCapture() {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(context, "Cattura schermo non disponibile", Toast.LENGTH_SHORT).show()
            return
        }
        // From API 34 the consent dialog also offers "a single app". That would fill the
        // full-screen overlay with one app's window, so pin the choice to the display.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay(),
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    // Chained rather than fired together: two system dialogs stacked on top of each other
    // is how a user ends up dismissing the one that mattered.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { askForCapture() }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    fun toggleOverlay(wanted: Boolean) {
        if (!wanted) {
            OverlayFoldService.stop(context)
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName),
                ),
            )
            return
        }
        val notificationsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (notificationsGranted) {
            askForCapture()
        } else {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(wallpaperActive)

        if (FoldSettings.migrated) {
            Text(
                "Le tue impostazioni venivano da una versione precedente e tenevano " +
                    "l'aspetto di allora: effetto \"Piega\", con la valle d'ombra in mezzo " +
                    "allo schermo, e una sfocatura troppo debole per vedersi. Le ho " +
                    "riportate all'aspetto Duo. La tua immagine e i tuoi colori di sfondo " +
                    "sono rimasti quelli.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(10.dp),
                    )
                    .padding(12.dp),
            )
        }

        OverlaySection(
            running = overlayRunning,
            canOverlay = canOverlay,
            onToggle = ::toggleOverlay,
            onTest = { OverlayFoldService.test(context) },
        )

        LiveBlurSection(canOverlay = canOverlay)

        HingeProbeSection()

        SensorSweepSection()

        MagCurveSection()

        PreviewCard(
            settings = settings,
            openness = openness,
            autoSweep = autoSweep,
            followAngle = if (followHinge) rawAngle else Float.NaN,
        )

        AngleControls(
            openness = openness,
            autoSweep = autoSweep,
            followHinge = followHinge,
            hingeAvailable = hinge.available,
            rawAngle = rawAngle,
            onOpenness = {
                openness = it
                autoSweep = false
                followHinge = false
            },
            onAutoSweep = {
                autoSweep = it
                if (it) followHinge = false
            },
            onFollowHinge = {
                followHinge = it
                if (it) autoSweep = false
            },
        )

        PresetSection(onPreset = { update(it.apply(settings)) })

        EffectSection(settings = settings, onChange = ::update)

        TuningSection(settings = settings, onChange = ::update)

        ColorSection(settings = settings, onChange = ::update)

        ImageSection(settings = settings, onChange = ::update)

        CalibrationSection(
            settings = settings,
            rawAngle = rawAngle,
            hingeAvailable = hinge.available,
            observed = observed,
            onChange = ::update,
        )

        ActionSection(
            onApply = { applyWallpaper(context) },
            onReset = { update(FoldSettings(imagePath = settings.imagePath)) },
        )
    }
}

// --- sections --------------------------------------------------------------------

@Composable
private fun Header(active: Boolean) {
    Column {
        Text(
            "FoldWall",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (active) {
                "Attivo come sfondo animato."
            } else {
                "Sfondo animato guidato dall'angolo della cerniera."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewCard(
    settings: FoldSettings,
    openness: Float,
    autoSweep: Boolean,
    followAngle: Float,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        AndroidView(
            factory = { ctx -> FoldPreviewView(ctx) },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(20.dp)),
            update = { view ->
                view.settings = settings
                view.autoSweep = autoSweep
                // The sweep drives itself; feeding state back would recompose the whole
                // screen once per frame for nothing.
                if (!autoSweep) {
                    view.openness = if (followAngle.isNaN()) {
                        openness
                    } else {
                        settings.opennessFor(followAngle)
                    }
                }
            },
        )
    }
}

@Composable
private fun AngleControls(
    openness: Float,
    autoSweep: Boolean,
    followHinge: Boolean,
    hingeAvailable: Boolean,
    rawAngle: Float,
    onOpenness: (Float) -> Unit,
    onAutoSweep: (Boolean) -> Unit,
    onFollowHinge: (Boolean) -> Unit,
) {
    SectionCard("Apertura") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("chiuso", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = openness,
                onValueChange = onOpenness,
                enabled = !autoSweep && !followHinge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text("aperto", style = MaterialTheme.typography.labelSmall)
        }
        ToggleRow(label = "Anteprima automatica", checked = autoSweep, onChange = onAutoSweep)
        ToggleRow(
            label = if (hingeAvailable) "Segui la cerniera vera" else "Cerniera non disponibile",
            checked = followHinge,
            enabled = hingeAvailable,
            onChange = onFollowHinge,
        )
        Text(
            text = if (!hingeAvailable) {
                "Questo dispositivo non espone TYPE_HINGE_ANGLE: usa il cursore qui sopra."
            } else if (rawAngle.isNaN()) {
                "Sensore presente, nessuna lettura ancora ricevuta."
            } else {
                "Angolo attuale " + String.format(Locale.US, "%.1f", rawAngle) + "°"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSection(onPreset: (FoldPreset) -> Unit) {
    SectionCard("Preset") {
        WrapRow {
            FoldPreset.entries.forEach { preset ->
                FilterChip(
                    selected = false,
                    onClick = { onPreset(preset) },
                    label = { Text(preset.label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffectSection(settings: FoldSettings, onChange: (FoldSettings) -> Unit) {
    SectionCard("Effetto") {
        WrapRow {
            FoldEffect.entries.forEach { effect ->
                FilterChip(
                    selected = settings.effect == effect,
                    onClick = { onChange(settings.copy(effect = effect)) },
                    label = { Text(effect.label) },
                )
            }
        }
        Text(
            settings.effect.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            label = "Inverti (effetto da aperto)",
            checked = settings.invert,
            onChange = { onChange(settings.copy(invert = it)) },
        )
    }
}

@Composable
private fun TuningSection(settings: FoldSettings, onChange: (FoldSettings) -> Unit) {
    SectionCard("Regolazioni") {
        PercentSlider("Intensità", settings.amount) { onChange(settings.copy(amount = it)) }
        LabeledSlider(
            label = "Sfocatura massima",
            value = settings.maxBlur,
            range = 0f..90f,
            valueText = settings.maxBlur.roundToInt().toString() + " px",
        ) { onChange(settings.copy(maxBlur = it)) }
        PercentSlider("Scurimento", settings.dim) { onChange(settings.copy(dim = it)) }
        PercentSlider("Desaturazione", settings.desat) { onChange(settings.copy(desat = it)) }
        PercentSlider("Aberrazione cromatica", settings.chroma) {
            onChange(settings.copy(chroma = it))
        }
        LabeledSlider(
            label = "Larghezza piega",
            value = settings.creaseWidth,
            range = 0.02f..0.5f,
            valueText = (settings.creaseWidth * 100f).roundToInt().toString() + "%",
        ) { onChange(settings.copy(creaseWidth = it)) }
        LabeledSlider(
            label = "Velocità di risposta",
            value = settings.smoothing,
            range = 3f..40f,
            valueText = settings.smoothing.roundToInt().toString(),
        ) { onChange(settings.copy(smoothing = it)) }
    }
}

@Composable
private fun ColorSection(settings: FoldSettings, onChange: (FoldSettings) -> Unit) {
    SectionCard("Colore") {
        PercentSlider("Tinta", settings.tintAmount) { onChange(settings.copy(tintAmount = it)) }
        SwatchRow(SWATCHES, settings.tintColor) { onChange(settings.copy(tintColor = it)) }

        Spacer(Modifier.height(6.dp))
        PercentSlider("Bagliore sulla piega", settings.glowAmount) {
            onChange(settings.copy(glowAmount = it))
        }
        SwatchRow(SWATCHES, settings.glowColor) { onChange(settings.copy(glowColor = it)) }

        if (settings.imagePath == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Sfondo generato (nessuna immagine scelta)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwatchRow(BACKGROUND_SWATCHES, settings.bgTop) { onChange(settings.copy(bgTop = it)) }
            SwatchRow(BACKGROUND_SWATCHES, settings.bgBottom) {
                onChange(settings.copy(bgBottom = it))
            }
        }
    }
}

@Composable
private fun ImageSection(settings: FoldSettings, onChange: (FoldSettings) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            val path = importImage(context, uri)
            if (path != null) {
                onChange(settings.copy(imagePath = path))
            } else {
                Toast.makeText(context, "Immagine non leggibile", Toast.LENGTH_SHORT).show()
            }
        }
    }

    SectionCard("Immagine") {
        Text(
            settings.imagePath?.let { File(it).name } ?: "Nessuna: uso lo sfondo generato",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            ) { Text("Scegli immagine") }
            if (settings.imagePath != null) {
                OutlinedButton(onClick = { onChange(settings.copy(imagePath = null)) }) {
                    Text("Rimuovi")
                }
            }
        }
    }
}

@Composable
private fun CalibrationSection(
    settings: FoldSettings,
    rawAngle: Float,
    hingeAvailable: Boolean,
    observed: ClosedFloatingPointRange<Float>?,
    onChange: (FoldSettings) -> Unit,
) {
    SectionCard("Calibrazione cerniera") {
        Text(
            "Il pannello interno si accende solo verso i 90°, e prima di allora lo sfondo " +
                "non riceve niente: con l'intervallo largo l'effetto è già mezzo finito " +
                "quando lo vedi. Imposta lo sfondo, apri e chiudi qualche volta, poi torna " +
                "qui: FoldWall registra da solo l'intervallo che il tuo telefono gli dà.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ObservedRangeRow(
            observed = observed,
            settings = settings,
            onApply = { range ->
                onChange(settings.copy(angleMin = range.start, angleMax = range.endInclusive))
            },
        )
        LabeledSlider(
            label = "Angolo minimo (tutto effetto)",
            value = settings.angleMin,
            range = 0f..180f,
            valueText = settings.angleMin.roundToInt().toString() + "°",
        ) { onChange(settings.copy(angleMin = it)) }
        LabeledSlider(
            label = "Angolo massimo (nessun effetto)",
            value = settings.angleMax,
            range = 0f..180f,
            valueText = settings.angleMax.roundToInt().toString() + "°",
        ) { onChange(settings.copy(angleMax = it)) }
        Text(
            text = if (!hingeAvailable) {
                "Sensore assente."
            } else if (rawAngle.isNaN()) {
                "In attesa del primo evento del sensore."
            } else {
                "Adesso: " + String.format(Locale.US, "%.1f", rawAngle) + "° -> apertura " +
                    String.format(Locale.US, "%.2f", settings.opennessFor(rawAngle))
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        ToggleRow(
            label = "Diagnostica sullo sfondo",
            checked = settings.debug,
            onChange = { onChange(settings.copy(debug = it)) },
        )
    }
}

@Composable
private fun ObservedRangeRow(
    observed: ClosedFloatingPointRange<Float>?,
    settings: FoldSettings,
    onApply: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    val context = LocalContext.current
    if (observed == null) {
        Text(
            "Nessun intervallo registrato ancora. Serve che FoldWall sia lo sfondo attivo " +
                "e che tu apra e chiuda il telefono un paio di volte.",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val low = observed.start.roundToInt()
    val high = observed.endInclusive.roundToInt()
    val alreadyApplied = abs(settings.angleMin - observed.start) < 1f &&
        abs(settings.angleMax - observed.endInclusive) < 1f
    Text(
        "Il tuo telefono ti dà da " + low + "° a " + high + "°.",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { onApply(observed) },
            enabled = !alreadyApplied,
            modifier = Modifier.weight(1f),
        ) {
            Text(if (alreadyApplied) "Intervallo già applicato" else "Usa questo intervallo")
        }
        OutlinedButton(onClick = { FoldObserved.clear(context) }) { Text("Azzera") }
    }
}

@Composable
private fun OverlaySection(
    running: Boolean,
    canOverlay: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    SectionCard("Effetto su tutto lo schermo") {
        Text(
            "Quando pieghi, tutto lo schermo va fuori fuoco e si scurisce \u2014 le app, " +
                "le icone, tutto \u2014 e torna nitido quando ti fermi. Non c'entra niente " +
                "con lo sfondo: puoi tenere il tuo.\n\n" +
                "Nasce dall'animazione di apertura dell'iPhone Duo, ma non la riproduce: " +
                "qui la sfocatura \u00e8 uniforme su tutto il fotogramma, e non \u00e8 detto " +
                "che Apple la faccia cos\u00ec.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (running) {
            Button(onClick = { onToggle(false) }, modifier = Modifier.fillMaxWidth()) {
                Text("Spegni")
            }
            OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                Text("Prova a tempo (2,6 s, senza piegare)")
            }
            Text(
                "Questa prova \u00e8 a tempo fisso apposta, serve solo a vedere se l'effetto " +
                    "arriva a schermo con il telefono fermo. Piegando davvero \u00e8 diverso: " +
                    "la sfocatura segue l'angolo, se ti fermi a met\u00e0 resta a met\u00e0, e " +
                    "sparisce appena torni piatto. Restando fermo se ne va comunque dopo " +
                    "un paio di secondi: \u00e8 una fotografia congelata, il telefono deve " +
                    "tornare utilizzabile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OverlayDiagnostics()
        } else if (!canOverlay) {
            Button(onClick = { onToggle(true) }, modifier = Modifier.fillMaxWidth()) {
                Text("1. Concedi \"Mostra sopra altre app\"")
            }
            Text(
                "Android apre le impostazioni. Dai il permesso, poi torna qui: comparir\u00e0 " +
                    "il pulsante per accendere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(onClick = { onToggle(true) }, modifier = Modifier.fillMaxWidth()) {
                Text("2. Accendi l'effetto a schermo intero")
            }
            Text(
                "Android chieder\u00e0 il consenso alla registrazione schermo: scegli " +
                    "\"Schermo intero\" e premi Avvia. Se non vedi quella richiesta, " +
                    "l'effetto non \u00e8 acceso.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "Cosa comporta:\n" +
                "\u2022 Resta acceso l'indicatore di registrazione, e una notifica di FoldWall.\n" +
                "\u2022 Mentre l'effetto \u00e8 a video i tocchi non passano: tocca lo schermo per " +
                "farlo sparire subito.\n" +
                "\u2022 Le app che vietano gli screenshot (banca, video protetti, password " +
                "manager) vengono catturate nere.\n" +
                "\u2022 La barra di stato resta sopra l'effetto.\n" +
                "\u2022 Si spegne da sola se chiudi l'app dai recenti o riavvii.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One question, one button: does this phone's compositor blur the live screen on request?
 *
 * Everything else in the app blurs a screenshot. If the answer here is yes, the effect can be
 * rebuilt without capturing anything at all — see [LiveBlurProbe] for why that is better.
 */
@Composable
private fun LiveBlurSection(canOverlay: Boolean) {
    val context = LocalContext.current
    var result by remember { mutableStateOf("") }

    SectionCard("Prova: sfocare il display, non una fotografia") {
        Text(
            "L'effetto qui sopra congela una fotografia dello schermo e sfoca quella. " +
                "Questa prova chiede invece al sistema di sfocare lo schermo vero, mentre " +
                "continua a vivere: niente cattura, quindi nessun consenso alla " +
                "registrazione, nessun pallino di registrazione, e le app che vietano gli " +
                "screenshot si sfocano invece di diventare nere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Funziona sull'emulatore. Se One UI la concede non lo so, e il modo di " +
                "saperlo è provarla: premi, e guarda se lo schermo va fuori fuoco — " +
                "icone e app comprese, non solo lo sfondo. Durante la prova puoi premere " +
                "Home: la sfocatura resta, così la vedi sulla schermata principale.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { LiveBlurProbe.run(context) { result = it } },
            enabled = canOverlay,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Prova sfocatura live (6 s)")
        }
        if (!canOverlay) {
            Text(
                "Serve prima il permesso \"Mostra sopra altre app\" della sezione qui sopra.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.isNotEmpty()) {
            Text(
                result,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Records what the hinge sensor really delivers, and hands it over as a report.
 *
 * The question it answers is whether this device gives a continuous angle or only the three
 * postures — see [HingeProbe]. It is a measurement, so it shows the raw readings rather than a
 * conclusion, and the conclusion it does draw is the one the numbers support.
 */
@Composable
private fun HingeProbeSection() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val available = remember { HingeProbe.available(context) }
    val recording = HingeProbe.recording
    val samples = HingeProbe.count()
    val values = HingeProbe.distinct().sorted()

    SectionCard("Misura del sensore cerniera") {
        Text(
            "Due sviluppatori sostengono che sui Samsung il sensore pubblico non dia " +
                "l'angolo ma solo tre valori — 0, 90 e 180 — e che quello vero sia " +
                "riservato alle app di Samsung. Se è così, nessuna app può far seguire " +
                "l'effetto all'apertura, e non è un problema di messa a punto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Registra ogni singolo evento che il sensore manda, senza filtrarlo: il valore " +
                "in gradi e l'istante esatto in cui arriva. Da lì si vede che sensore è, " +
                "ogni quanto parla e a che scatti si muove. Avvia, apri e chiudi il " +
                "telefono piano due o tre volte fermandoti a metà, poi ferma: la " +
                "registrazione continua anche mentre il telefono è piegato e lo schermo " +
                "grande è spento.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!available) {
            Text(
                "Questo dispositivo non espone affatto il sensore cerniera pubblico. " +
                    "È già una risposta, e vale la pena riferirla.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Button(
            onClick = {
                copied = false
                if (recording) HingeProbe.stop() else HingeProbe.start(context)
            },
            enabled = available,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (recording) "Ferma la registrazione" else "Avvia la registrazione")
        }

        if (recording) {
            Text(
                "Sto registrando. Piega e apri, con calma.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (samples > 0) {
            Text(
                buildString {
                    append("eventi ").append(samples)
                    append(" in ").append(HingeProbe.elapsedMs() / 1000).append("s")
                    append("   valori diversi ").append(values.size).append('\n')
                    if (values.isNotEmpty()) {
                        append("da ").append(String.format(Locale.US, "%.1f", values.first()))
                        append("° a ").append(String.format(Locale.US, "%.1f", values.last()))
                        append("°   scalino minimo ")
                        val step = HingeProbe.smallestStep()
                        append(if (step.isNaN()) "n/d" else String.format(Locale.US, "%.2f", step))
                        append('\n')
                        val gap = HingeProbe.medianGapMs()
                        append("un evento ogni ")
                        append(if (gap.isNaN()) "n/d" else String.format(Locale.US, "%.0f", gap))
                        append(" ms mentre si muove\n")
                        append(values.joinToString("  ") {
                            String.format(Locale.US, "%.1f", it)
                        })
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (HingeProbe.postureOnly()) {
                    "Finora tutte le letture cadono su 0, 90 o 180: il sensore sta dando " +
                        "la posizione, non l'angolo. Se è ancora così dopo qualche piega " +
                        "lenta, i due sviluppatori hanno ragione."
                } else {
                    "Ci sono letture in mezzo alle posizioni fisse: questo telefono " +
                        "l'angolo vero ce l'ha, e la loro misura non vale per il tuo modello."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedButton(
                onClick = {
                    val text = HingeProbe.summary(context)
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("FoldWall hinge summary", text))
                    copied = cm != null
                    saved = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copia il riassunto")
            }
            if (copied) {
                Text(
                    "Copiato. Dentro c'è il modello, la versione di Android e One UI, tutto " +
                        "quello che il sensore dichiara di sé, i valori visti e ogni quanto " +
                        "arrivano. Incollamelo qui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(
                onClick = {
                    saved = HingeProbe.saveToDownloads(context) ?: ""
                    copied = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salva il log completo (ogni evento)")
            }
            val savedName = saved
            if (savedName != null) {
                Text(
                    if (savedName.isEmpty()) {
                        "Non sono riuscito a scrivere il file. Il riassunto qui sopra " +
                            "si copia comunque."
                    } else {
                        "Salvato in " + HingeProbe.downloadsLabel() + " come " + savedName +
                            ". Una riga per evento: millisecondi dall'avvio, gradi, " +
                            "distanza dall'evento precedente, orologio del sensore."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Listens to every sensor at once through a fold, to find out whether anything besides the
 * hinge sensor carries usable information about how far the phone is open — see [SensorSweep].
 */
@Composable
private fun SensorSweepSection() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val running = SensorSweep.running
    val movers = SensorSweep.movers()

    SectionCard("Cerca un'alternativa al sensore cerniera") {
        Text(
            "Il sensore cerniera dà tre valori, quindi l'angolo da lì non si ricava. " +
                "Accelerometro e giroscopio da soli non bastano: il telefono ha una sola " +
                "centralina inerziale, dentro una delle due metà, e misura come si muove " +
                "il telefono nello spazio, non come si apre la cerniera. Se tieni ferma " +
                "quella metà e apri l'altra, non vede nulla.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Ma a bordo c'è altro: i magneti della cerniera, la luce, la prossimità, i " +
                "sensori Samsung del folding. Invece di indovinare quale serva, questa " +
                "prova li ascolta tutti insieme mentre pieghi e dice quali si sono mossi, " +
                "e di quanto. Prova anche a registrarsi sui sensori Samsung per nome: " +
                "vederli in elenco non vuol dire poterli leggere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                copied = false
                saved = null
                if (running) SensorSweep.stop() else SensorSweep.start(context)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Ferma la ricerca" else "Ascolta tutti i sensori")
        }

        if (running) {
            Text(
                "In ascolto su " + SensorSweep.listening() + " sensori. Apri e chiudi " +
                    "piano, due o tre volte, poi ferma.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (SensorSweep.elapsedMs() > 0 && SensorSweep.listening() > 0) {
            Text(
                buildString {
                    append("durata ").append(SensorSweep.elapsedMs() / 1000).append("s")
                    append("   cambi di postura ").append(SensorSweep.hingeChanges()).append('\n')
                    if (movers.isEmpty()) {
                        append("nessun sensore si è ancora mosso")
                    } else {
                        append("si sono mossi di più:\n")
                        append(movers.joinToString("\n"))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(
                        ClipData.newPlainText("FoldWall sweep", SensorSweep.report(context)),
                    )
                    copied = cm != null
                    saved = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copia il risultato")
            }
            if (copied) {
                Text(
                    "Copiato. Incollamelo qui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(
                onClick = {
                    saved = SensorSweep.saveToDownloads(context) ?: ""
                    copied = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salva il risultato in Download")
            }
            val savedName = saved
            if (savedName != null) {
                Text(
                    if (savedName.isEmpty()) "Non sono riuscito a scrivere il file."
                    else "Salvato come " + savedName + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Records the magnetic field against time through a slow fold, so the shape of the curve can
 * be read rather than guessed at from its extremes — see [MagCurve].
 */
@Composable
private fun MagCurveSection() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val running = MagCurve.running

    SectionCard("Curva del magnete della cerniera") {
        Text(
            "La ricerca ha trovato che il magnetometro oscilla di 146 µT chiudendo il " +
                "telefono: tre volte il campo terrestre, quindi è il magnete della " +
                "cerniera, non rumore. Resta da sapere la cosa che decide tutto: se il " +
                "campo cresce in modo regolare mentre chiudi, l'angolo si può ricavare. " +
                "Se scatta di colpo vicino alla chiusura, dice solo \"quasi chiuso\" e " +
                "non serve.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Registra il campo istante per istante. Appoggia il telefono, avvia, poi " +
                "chiudi e riapri MOLTO piano, fermandoti due o tre secondi a ogni tappa: " +
                "aperto, tre quarti, metà, un quarto, chiuso, e ritorno. Ripeti il giro " +
                "due o tre volte: le pause diventano gradini nella curva, e ripetere " +
                "serve a vedere se i gradini cascano sempre allo stesso posto.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                copied = false
                saved = null
                if (running) MagCurve.stop() else MagCurve.start(context)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Ferma" else "Registra la curva")
        }

        if (running) {
            val m = MagCurve.magnitudeNow()
            val h = MagCurve.hingeNow()
            Text(
                buildString {
                    append("campo ")
                    append(if (m.isNaN()) "—" else String.format(Locale.US, "%.1f", m))
                    append(" µT     cerniera ")
                    append(if (h.isNaN()) "—" else String.format(Locale.US, "%.0f", h))
                    append("°\n")
                    append(MagCurve.count()).append(" righe in ")
                    append(MagCurve.elapsedMs() / 1000).append("s")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Guarda questo numero mentre chiudi: se scorre, siamo a cavallo. " +
                    "Se resta fermo e poi salta, no.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!running && MagCurve.count() > 0) {
            Text(
                MagCurve.count().toString() + " righe registrate in " +
                    (MagCurve.elapsedMs() / 1000) + "s.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(
                        ClipData.newPlainText("FoldWall mag curve", MagCurve.summary(context)),
                    )
                    copied = cm != null
                    saved = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copia la curva (versione corta)")
            }
            if (copied) {
                Text(
                    "Copiato. Incollamelo qui: è la versione assottigliata, basta per " +
                        "vedere la forma.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(
                onClick = {
                    saved = MagCurve.saveToDownloads(context) ?: ""
                    copied = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salva la curva completa (.csv)")
            }
            val savedName = saved
            if (savedName != null) {
                Text(
                    if (savedName.isEmpty()) "Non sono riuscito a scrivere il file."
                    else "Salvato in Download come " + savedName + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Live read-out of what the service is doing, so "non succede niente" can be diagnosed. */
@Composable
private fun OverlayDiagnostics() {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            tick++
        }
    }
    val events = OverlayFoldService.seenHingeEvents
    val angle = OverlayFoldService.seenHingeAngle
    val openness = OverlayFoldService.seenOpenness
    val status = OverlayFoldService.status
    val geometry = OverlayFoldService.seenGeometry
    @Suppress("UNUSED_EXPRESSION")
    tick
    Text(
        text = buildString {
            append("stato    ").append(status.ifEmpty { "-" }).append('\n')
            append("cerniera ").append(events).append(" eventi")
            if (!angle.isNaN()) {
                append("  ultimo ").append(String.format(Locale.US, "%.1f", angle)).append('\u00b0')
            }
            if (!openness.isNaN()) {
                append('\n').append("apertura ")
                    .append(String.format(Locale.US, "%.2f", openness))
                    .append("   sfocatura ")
                    .append((72f * (1f - openness)).toInt()).append(" px")
            }
            if (geometry.isNotEmpty()) {
                append('\n').append("schermo  ").append(geometry)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (events == 0L) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
    if (events == 0L) {
        Text(
            "Nessun evento cerniera ricevuto: il sensore non sta arrivando al servizio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionSection(onApply: () -> Unit, onReset: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onApply, modifier = Modifier.weight(1f)) {
            Text("Imposta come sfondo")
        }
        OutlinedButton(onClick = onReset) { Text("Reset") }
    }
}

// --- building blocks -------------------------------------------------------------

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.5.sp,
            )
            content()
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun PercentSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    LabeledSlider(
        label = label,
        value = value,
        range = 0f..1f,
        valueText = (value * 100f).roundToInt().toString() + "%",
        onChange = onChange,
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SwatchRow(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        colors.forEach { argb ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (argb == selected) 3.dp else 1.dp,
                        color = if (argb == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { onPick(argb) },
            )
        }
    }
}

/** Simple flow layout: chips wrap onto as many rows as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrapRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

// --- plumbing ---------------------------------------------------------------------

private fun importImage(context: Context, uri: Uri): String? = try {
    // Copied into filesDir under a fresh name: the renderer caches by path, so reusing
    // one filename would leave the old picture on screen.
    val dest = File(context.filesDir, "wallpaper_" + System.currentTimeMillis() + ".img")
    context.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
    if (dest.length() == 0L) {
        dest.delete()
        null
    } else {
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith("wallpaper_") && it.name != dest.name }
            ?.forEach { it.delete() }
        dest.absolutePath
    }
} catch (e: Exception) {
    Log.e("FoldWall", "import failed", e)
    null
}

private fun applyWallpaper(context: Context) {
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
        putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, FoldWallpaperService::class.java),
        )
    }
    try {
        context.startActivity(direct)
        return
    } catch (_: ActivityNotFoundException) {
        // Some launchers block the direct preview; fall through to the chooser.
    }
    try {
        context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "Apri Impostazioni > Sfondo > Sfondi animati e scegli FoldWall",
            Toast.LENGTH_LONG,
        ).show()
    }
}
