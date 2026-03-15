package dev.hapax.watch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import dev.hapax.watch.sensor.SensorService
import dev.hapax.watch.ui.theme.HapaxWatchTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
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

    val serverIpKey = stringPreferencesKey("server_ip")
    val manualIpKey = stringPreferencesKey("manual_ip")
    val serviceEnabledKey = booleanPreferencesKey("service_enabled")

    var serverIp by remember {
        mutableStateOf(
            runBlocking {
                context.dataStore.data.map { it[serverIpKey] ?: "" }.first()
            }
        )
    }

    var manualIp by remember {
        mutableStateOf(
            runBlocking {
                context.dataStore.data.map { it[manualIpKey] ?: "" }.first()
            }
        )
    }

    var serviceEnabled by remember {
        mutableStateOf(
            runBlocking {
                context.dataStore.data.map { it[serviceEnabledKey] ?: false }.first()
            }
        )
    }

    // Connection status display
    var connectionStatus by remember { mutableStateOf("disconnected") }
    var mdnsAddress by remember { mutableStateOf<String?>(null) }

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
        item {
            Text(text = "Server: ${serverIp.ifEmpty { "(not set)" }}")
        }
        // Show mDNS discovered address if found
        if (mdnsAddress != null) {
            item {
                Text(
                    text = "mDNS: $mdnsAddress",
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
                            text = "10.0.0.1:8042",
                            color = Color(0xFF808080),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                    innerTextField()
                },
            )
        }
        item {
            Button(
                onClick = {
                    val enabled = !serviceEnabled
                    serviceEnabled = enabled
                    scope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[serviceEnabledKey] = enabled
                        }
                    }
                    val intent = Intent(context, SensorService::class.java)
                    if (enabled) {
                        context.startForegroundService(intent)
                    } else {
                        context.stopService(intent)
                    }
                },
                label = { Text(if (serviceEnabled) "Service ON" else "Service OFF") },
            )
        }
    }
}
