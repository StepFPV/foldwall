package com.stepdesign.foldwall

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
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
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = FoldWallColors) {
                Surface(
                    color = FoldWallColors.background,
                    contentColor = FoldWallColors.onBackground,
                ) {
                    FoldWallScreen()
                }
            }
        }
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
private fun FoldWallScreen() {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 44.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(context)

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
private fun Header(context: Context) {
    val active = remember {
        WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
    }
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
    onChange: (FoldSettings) -> Unit,
) {
    SectionCard("Calibrazione cerniera") {
        Text(
            "L'intervallo di angoli che un live wallpaper riceve dipende da quando il " +
                "sistema gli passa il pannello. Attiva la diagnostica, guarda i gradi " +
                "reali mentre pieghi, poi stringi qui l'intervallo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
