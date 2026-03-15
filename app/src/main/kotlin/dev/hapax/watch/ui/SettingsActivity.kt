package dev.hapax.watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.ToggleButton
import dev.hapax.watch.sensor.SensorService
import dev.hapax.watch.ui.theme.HapaxWatchTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HapaxWatchTheme {
                SettingsScreen()
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val serverIpKey = stringPreferencesKey("server_ip")
    val serviceEnabledKey = booleanPreferencesKey("service_enabled")

    var serverIp by remember {
        mutableStateOf(
            runBlocking {
                context.dataStore.data.map { it[serverIpKey] ?: "" }.first()
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

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(text = "Hapax Watch")
        }
        item {
            Text(text = "Server: ${serverIp.ifEmpty { "(not set)" }}")
        }
        item {
            ToggleButton(
                checked = serviceEnabled,
                onCheckedChange = { enabled ->
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
            ) {
                Text(if (serviceEnabled) "Service ON" else "Service OFF")
            }
        }
    }
}
