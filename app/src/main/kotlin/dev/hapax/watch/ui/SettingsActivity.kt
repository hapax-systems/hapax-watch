package dev.hapax.watch.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.hapax.watch.data.dataStore
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import dev.hapax.watch.sensor.SensorService
import dev.hapax.watch.ui.theme.HapaxWatchTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class SettingsActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        Log.i(TAG, "Permissions result: $results, allGranted=$allGranted")
        if (!allGranted) {
            Log.w(TAG, "Some permissions denied — sensors may be unavailable")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
        setContent {
            HapaxWatchTheme {
                SettingsScreen()
            }
        }
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: $missing")
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            Log.i(TAG, "All permissions already granted")
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val manualIpKey = stringPreferencesKey("manual_ip")

    var manualIp by remember {
        mutableStateOf(
            runBlocking {
                context.dataStore.data.map { it[manualIpKey] ?: "" }.first()
            }
        )
    }

    // Live status from SharedPreferences
    var connectionStatus by remember { mutableStateOf("disconnected") }
    var serverUrl by remember { mutableStateOf("") }
    var lastFlushTime by remember { mutableStateOf(0L) }
    var bufferSize by remember { mutableStateOf(0) }
    var activeSensors by remember { mutableStateOf("") }
    var lastHr by remember { mutableStateOf(0f) }

    // Auto-refresh status every 3 seconds
    LaunchedEffect(Unit) {
        while (true) {
            val prefs = context.getSharedPreferences(
                SensorService.STATUS_PREFS, Context.MODE_PRIVATE
            )
            connectionStatus = prefs.getString("connection_status", "disconnected") ?: "disconnected"
            serverUrl = prefs.getString("server_url", "") ?: ""
            lastFlushTime = prefs.getLong("last_flush_time", 0L)
            bufferSize = prefs.getInt("buffer_size", 0)
            activeSensors = prefs.getString("active_sensors", "") ?: ""
            lastHr = prefs.getFloat("last_hr", 0f)
            delay(3000)
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(text = "Hapax Watch")
        }
        item {
            // Connection status with colored dot
            val (statusColor, statusLabel) = when (connectionStatus) {
                "connected" -> Color(0xFF55FF55) to "Connected"
                "buffering" -> Color(0xFFFFFF55) to "Buffering"
                else -> Color(0xFFFF5555) to "Disconnected"
            }
            Text(
                text = "\u25CF $statusLabel",
                color = statusColor,
            )
        }
        // Heart rate
        if (lastHr > 0) {
            item {
                Text(
                    text = "\u2665 ${lastHr.toInt()} bpm",
                    color = Color(0xFFFF6B6B),
                )
            }
        }
        item {
            Text(text = "Server: ${serverUrl.ifEmpty { "(none)" }}")
        }
        item {
            Text(text = "Buffer: $bufferSize readings")
        }
        if (lastFlushTime > 0) {
            item {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(lastFlushTime))
                Text(text = "Last flush: $timeStr")
            }
        }
        if (activeSensors.isNotEmpty()) {
            item {
                Text(
                    text = "Sensors: $activeSensors",
                    color = Color(0xFF55FFFF),
                )
            }
        }
        item {
            Text(text = "Manual IP:")
        }
        item {
            BasicTextField(
                value = manualIp,
                onValueChange = { value ->
                    manualIp = value
                    scope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[manualIpKey] = value
                        }
                    }
                },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (manualIp.isEmpty()) {
                        Text(
                            text = "ip:port",
                            color = Color(0xFF808080),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                    innerTextField()
                },
            )
        }
    }
}
