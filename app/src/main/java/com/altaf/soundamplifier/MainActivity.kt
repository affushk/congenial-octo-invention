package com.altaf.soundamplifier

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var pendingStart = false

    private var running by mutableStateOf(false)
    private var level by mutableFloatStateOf(0f)
    private var gain by mutableFloatStateOf(2.2f)
    private var micSensitivity by mutableFloatStateOf(1.5f)
    private var outputBoost by mutableFloatStateOf(1200f)
    private var balance by mutableFloatStateOf(0f)
    private var noiseReduction by mutableStateOf(true)
    private var voiceFocus by mutableStateOf(true)
    private var selectedPreset by mutableStateOf("Conversation")
    private val eq = List(5) { mutableFloatStateOf(0f) }
    private var message by mutableStateOf("Connect headphones, then tap Start.")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

            if (pendingStart && micGranted) {
                pendingStart = false
                startAmplifier()
            } else if (!micGranted) {
                pendingStart = false
                message = "Microphone permission is required for live amplification."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AltafTheme {
                var showSplash by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(2200)
                    showSplash = false
                }

                if (showSplash) {
                    AltafSplash()
                } else {
                    AmplifierScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        SoundAmplifierService.listener = { active, meter, text ->
            runOnUiThread {
                running = active
                level = meter
                message = text
            }
        }

        running = SoundAmplifierService.isAmplifying
        level = SoundAmplifierService.currentLevel
        message = if (running) {
            SoundAmplifierService.lastMessage
        } else {
            "Connect headphones, then tap Start."
        }
    }

    override fun onStop() {
        SoundAmplifierService.listener = null
        super.onStop()
    }

    private fun requestStart() {
        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (micGranted) {
            startAmplifier()
            return
        }

        pendingStart = true
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun serviceIntent(actionName: String): Intent {
        return Intent(this, SoundAmplifierService::class.java).apply {
            action = actionName
            putExtra(SoundAmplifierService.EXTRA_GAIN, gain)
            putExtra(SoundAmplifierService.EXTRA_MIC_SENSITIVITY, micSensitivity)
            putExtra(SoundAmplifierService.EXTRA_OUTPUT_BOOST, outputBoost.toInt())
            putExtra(SoundAmplifierService.EXTRA_BALANCE, balance)
            putExtra(SoundAmplifierService.EXTRA_NOISE_REDUCTION, noiseReduction)
            putExtra(SoundAmplifierService.EXTRA_VOICE_FOCUS, voiceFocus)
            putExtra(
                SoundAmplifierService.EXTRA_EQ,
                FloatArray(eq.size) { index -> eq[index].floatValue }
            )
        }
    }

    private fun startAmplifier() {
        ContextCompat.startForegroundService(
            this,
            serviceIntent(SoundAmplifierService.ACTION_START)
        )

        running = true
        message = "Starting background amplification…"
    }

    private fun stopAmplifier() {
        startService(
            Intent(this, SoundAmplifierService::class.java).apply {
                action = SoundAmplifierService.ACTION_STOP
            }
        )

        running = false
        level = 0f
        message = "Amplifier stopped."
    }

    private fun pushSettings() {
        if (!SoundAmplifierService.isAmplifying) return

        startService(
            serviceIntent(SoundAmplifierService.ACTION_UPDATE)
        )
    }

    private fun applyPreset(name: String) {
        selectedPreset = name

        when (name) {
            "Conversation" -> {
                gain = 2.2f
                micSensitivity = 1.5f
                outputBoost = 1200f
                noiseReduction = true
                voiceFocus = true
                setEq(floatArrayOf(-0.10f, 0.06f, 0.20f, 0.30f, 0.18f))
            }
            "TV" -> {
                gain = 2.6f
                micSensitivity = 1.7f
                outputBoost = 1400f
                noiseReduction = true
                voiceFocus = false
                setEq(floatArrayOf(0.04f, 0.10f, 0.18f, 0.22f, 0.12f))
            }
            "Outdoor" -> {
                gain = 2.0f
                micSensitivity = 1.45f
                outputBoost = 1000f
                noiseReduction = true
                voiceFocus = true
                setEq(floatArrayOf(-0.20f, -0.06f, 0.14f, 0.24f, 0.12f))
            }
            "Quiet Room" -> {
                gain = 1.8f
                micSensitivity = 1.3f
                outputBoost = 800f
                noiseReduction = false
                voiceFocus = false
                setEq(floatArrayOf(0f, 0f, 0.06f, 0.10f, 0f))
            }
        }

        pushSettings()
    }

    private fun setEq(values: FloatArray) {
        values.forEachIndexed { index, value ->
            eq[index].floatValue = value
        }
    }

    private fun safeReset() {
        gain = 1.2f
        micSensitivity = 1f
        outputBoost = 0f
        balance = 0f
        noiseReduction = true
        voiceFocus = true
        selectedPreset = "Safe"

        eq.forEach { state ->
            state.floatValue = 0f
        }

        pushSettings()
        message = if (running) {
            "Safe defaults restored. Background amplification is active."
        } else {
            "Safe defaults restored."
        }
    }

    @Composable
    private fun AltafSplash() {
        var started by remember { mutableStateOf(false) }

        val alpha by animateFloatAsState(
            targetValue = if (started) 1f else 0f,
            animationSpec = tween(850, easing = FastOutSlowInEasing),
            label = "splashAlpha"
        )
        val scale by animateFloatAsState(
            targetValue = if (started) 1f else 0.78f,
            animationSpec = tween(950, easing = FastOutSlowInEasing),
            label = "splashScale"
        )

        LaunchedEffect(Unit) {
            started = true
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .alpha(alpha)
                    .scale(scale)
            ) {
                Box(
                    modifier = Modifier.size(138.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val lime = Color(0xFF9BE564)
                        val center = Offset(size.width / 2f, size.height / 2f)

                        drawCircle(
                            color = Color(0xFF101010),
                            radius = size.minDimension * 0.45f,
                            center = center
                        )
                        drawCircle(
                            color = lime,
                            radius = size.minDimension * 0.38f,
                            center = center,
                            style = Stroke(width = 5.dp.toPx())
                        )
                        drawArc(
                            color = lime,
                            startAngle = -62f,
                            sweepAngle = 124f,
                            useCenter = false,
                            topLeft = Offset(size.width * 0.58f, size.height * 0.31f),
                            size = androidx.compose.ui.geometry.Size(size.width * 0.22f, size.height * 0.38f),
                            style = Stroke(width = 5.dp.toPx())
                        )
                        drawArc(
                            color = lime,
                            startAngle = -64f,
                            sweepAngle = 128f,
                            useCenter = false,
                            topLeft = Offset(size.width * 0.48f, size.height * 0.22f),
                            size = androidx.compose.ui.geometry.Size(size.width * 0.42f, size.height * 0.56f),
                            style = Stroke(width = 4.dp.toPx())
                        )
                    }

                    Text(
                        text = "A",
                        color = Color(0xFF9BE564),
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Altaf Designer",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "SOUND AMPLIFIER",
                    color = Color(0xFF9BE564),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun AmplifierScreen() {
        val animatedLevel by animateFloatAsState(level.coerceIn(0f, 1f), label = "level")
        val startColor by animateColorAsState(
            if (running) Color(0xFFFF6B6B) else Color(0xFF9BE564),
            label = "startColor"
        )
        var route by remember { mutableStateOf(currentAudioRoute()) }

        LaunchedEffect(Unit) {
            while (true) {
                route = currentAudioRoute()
                delay(1500)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "ALTAF DESIGNER",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Sound Amplifier",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = message,
                    color = Color(0xFFB8B8B8),
                    fontSize = 14.sp
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)),
                    shape = RoundedCornerShape(26.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (running) "LIVE" else "READY",
                            color = if (running) Color(0xFF9BE564) else Color(0xFF8C8C8C),
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(14.dp))

                        Button(
                            onClick = {
                                if (running) stopAmplifier() else requestStart()
                            },
                            modifier = Modifier.size(154.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = startColor,
                                contentColor = Color.Black
                            )
                        ) {
                            Text(
                                text = if (running) "STOP" else "START",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Spacer(Modifier.height(18.dp))

                        LinearProgressIndicator(
                            progress = { animatedLevel },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(99.dp)),
                            color = if (animatedLevel > 0.82f) {
                                Color(0xFFFFB35C)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            trackColor = Color(0xFF242424)
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "Live microphone level",
                            color = Color(0xFF8D8D8D),
                            fontSize = 12.sp
                        )
                    }
                }

                SectionCard(title = "Audio output") {
                    Text(
                        text = route,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Use headphones or earbuds. Phone-speaker playback can cause loud feedback.",
                        color = Color(0xFFAAAAAA),
                        fontSize = 13.sp
                    )
                }

                SectionCard(title = "Amplification") {
                    SettingSlider(
                        label = "Gain",
                        valueText = String.format("%.1fx", gain),
                        value = gain,
                        range = 1f..8f,
                        onValueChange = {
                            gain = it
                            pushSettings()
                        }
                    )

                    SettingSlider(
                        label = "Mic Sensitivity",
                        valueText = String.format("%.1fx", micSensitivity),
                        value = micSensitivity,
                        range = 1f..3f,
                        onValueChange = {
                            micSensitivity = it
                            pushSettings()
                        }
                    )

                    SettingSlider(
                        label = "Output Boost",
                        valueText = String.format("%.0f dB", outputBoost / 100f),
                        value = outputBoost,
                        range = 0f..1800f,
                        onValueChange = {
                            outputBoost = it
                            pushSettings()
                        }
                    )

                    if (gain >= 4f || outputBoost >= 1500f) {
                        Text(
                            text = "Strong boost is active. Start with low headphone volume and increase slowly.",
                            color = Color(0xFFFFB35C),
                            fontSize = 12.sp
                        )
                    }

                    SettingSlider(
                        label = "Left / Right balance",
                        valueText = when {
                            balance < -0.08f -> "Left " + String.format("%.0f%%", -balance * 100)
                            balance > 0.08f -> "Right " + String.format("%.0f%%", balance * 100)
                            else -> "Center"
                        },
                        value = balance,
                        range = -1f..1f,
                        onValueChange = {
                            balance = it
                            pushSettings()
                        }
                    )
                }

                SectionCard(title = "Smart processing") {
                    ToggleRow(
                        title = "Noise Reduction",
                        subtitle = "Reduce steady background noise when supported by the phone.",
                        checked = noiseReduction,
                        onCheckedChange = {
                            noiseReduction = it
                            pushSettings()
                        }
                    )

                    ToggleRow(
                        title = "Voice Focus",
                        subtitle = "Prioritize speech clarity using the device's voice processing.",
                        checked = voiceFocus,
                        onCheckedChange = {
                            voiceFocus = it
                            pushSettings()
                        }
                    )
                }

                SectionCard(title = "Presets") {
                    val presets = listOf("Conversation", "TV", "Outdoor", "Quiet Room")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.take(2).forEach { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = { applyPreset(preset) },
                                label = { Text(preset) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.drop(2).forEach { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = { applyPreset(preset) },
                                label = { Text(preset) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                SectionCard(title = "5-band Equalizer") {
                    val labels = listOf("100 Hz", "400 Hz", "1 kHz", "4 kHz", "10 kHz")
                    labels.forEachIndexed { index, label ->
                        SettingSlider(
                            label = label,
                            valueText = String.format("%+.0f%%", eq[index].floatValue * 100),
                            value = eq[index].floatValue,
                            range = -1f..1f,
                            onValueChange = {
                                eq[index].floatValue = it
                                pushSettings()
                            }
                        )
                    }
                }

                Button(
                    onClick = { safeReset() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF202020),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Reset to safe defaults")
                }

                Text(
                    text = "Personal listening tool, not a medical device. Higher boost can become loud quickly.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = Color(0xFF777777),
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(14.dp))
            }
        }
    }

    @Composable
    private fun SectionCard(
        title: String,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                content()
            }
        }
    }

    @Composable
    private fun SettingSlider(
        label: String,
        valueText: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        onValueChange: (Float) -> Unit
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, color = Color(0xFFD8D8D8), fontSize = 14.sp)
                Text(
                    valueText,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range
            )
        }
    }

    @Composable
    private fun ToggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color(0xFF8E8E8E), fontSize = 12.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }

    private fun currentAudioRoute(): String {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return try {
            val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            val priority = outputs.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: outputs.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: outputs.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }

            when (priority?.type) {
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset detected"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth headphones detected"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset detected"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones detected"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset detected"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset detected"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker detected — connect headphones"
                else -> "Audio output device detected"
            }
        } catch (_: SecurityException) {
            "Audio output connected"
        }
    }
}

private val AltafColors = darkColorScheme(
    primary = Color(0xFF9BE564),
    secondary = Color(0xFF5FD8FF),
    background = Color.Black,
    surface = Color(0xFF101010),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
private fun AltafTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AltafColors,
        content = content
    )
}
