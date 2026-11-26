package com.radarviewer.ld2450.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radarviewer.ld2450.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(current: AppSettings, onSave: (AppSettings) -> Unit, onBack: () -> Unit) {
    var host by remember { mutableStateOf(current.mqttHost) }
    var port by remember { mutableStateOf(current.mqttPort.toString()) }
    var user by remember { mutableStateOf(current.mqttUser) }
    var password by remember { mutableStateOf(current.mqttPassword) }
    var showPassword by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf(current.deviceId) }
    var homeAddress by remember { mutableStateOf(current.homeAddress) }
    val portNumber = port.toIntOrNull()
    val portOk = portNumber != null && portNumber in 1..65535

    Scaffold(
        containerColor = RadarColors.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RadarColors.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Section("Remote viewing (EMQX)")
            Hint("Used when you're away from home. Enter the details of the phone user you created in EMQX " +
                "(Access Control → Authentication), not the ESP32's user.")
            Field("Broker address", host, { host = it }, "e.g. xxxxxxxx.ala.us-east-1.emqxsl.com")
            Field("Port", port, { port = it.filter(Char::isDigit) }, "8883 = encrypted (recommended)",
                keyboard = KeyboardType.Number, isError = !portOk)
            Field("Username", user, { user = it })
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Hint("Stored encrypted on this phone only.")

            Spacer(Modifier.size(20.dp))
            Section("Radar")
            Field("Device ID", deviceId, { deviceId = it.trim() }, "Filled in automatically, e.g. ld2450-35f4")
            Field("Home address (optional)", homeAddress, { homeAddress = it.trim() },
                "Leave blank to find it automatically, or enter the IP shown on the radar's screen",
                keyboard = KeyboardType.Uri)

            Spacer(Modifier.size(20.dp))
            Button(
                onClick = {
                    onSave(
                        current.copy(
                            mqttHost = host.trim(), mqttPort = portNumber ?: 8883, mqttUser = user.trim(),
                            mqttPassword = password, deviceId = deviceId, homeAddress = homeAddress,
                        )
                    )
                },
                enabled = portOk,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) =
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

@Composable
private fun Hint(text: String) =
    Text(text, color = RadarColors.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    supporting: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    supportingText = supporting?.let { { Text(it) } },
    singleLine = true,
    isError = isError,
    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
)
