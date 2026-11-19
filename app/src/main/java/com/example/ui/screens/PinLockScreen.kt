package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.PinState

@Composable
fun PinLockScreen(
    pinState: PinState,
    onUnlock: (String) -> Unit,
    onConfigureNewPin: (String) -> Unit
) {
    var digits by remember { mutableStateOf("") }
    var firstEntryForSetup by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val isSetupMode = !pinState.isPinConfigured
    val maxDigits = 6

    fun submitCurrentDigits() {
        if (digits.length < 4) {
            localError = "Ingresa al menos 4 dígitos."
            return
        }
        if (isSetupMode) {
            val first = firstEntryForSetup
            if (first == null) {
                firstEntryForSetup = digits
                digits = ""
                localError = null
            } else if (first == digits) {
                onConfigureNewPin(digits)
            } else {
                localError = "Los PIN no coinciden. Inténtalo de nuevo."
                firstEntryForSetup = null
                digits = ""
            }
        } else {
            onUnlock(digits)
            digits = ""
        }
    }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSetupMode) Icons.Default.Security else Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Text(
                    text = when {
                        isSetupMode && firstEntryForSetup == null -> "Crea tu PIN de administrador"
                        isSetupMode -> "Confirma tu PIN de administrador"
                        else -> "Mi Inventario"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = when {
                        isSetupMode && firstEntryForSetup == null ->
                            "Elige un PIN de 4 a 6 dígitos para proteger tu inventario. Se almacenará cifrado con Android Keystore."
                        isSetupMode ->
                            "Vuelve a ingresar el mismo PIN para confirmar."
                        else ->
                            "Ingresa tu PIN de administrador para acceder."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // PIN indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    repeat(maxDigits) { index ->
                        val filled = index < digits.length
                        Box(
                            modifier = Modifier
                                .size(if (filled) 16.dp else 14.dp)
                                .clip(CircleShape)
                                .background(
                                    if (filled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }

                val activeError = localError ?: pinState.errorMessage
                if (activeError != null) {
                    Text(
                        text = activeError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                // Numeric keypad
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9")
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { digit ->
                                FilledTonalButton(
                                    onClick = {
                                        if (digits.length < maxDigits) {
                                            digits += digit
                                            localError = null
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(60.dp)
                                        .testTag("pin_key_$digit"),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(
                                        text = digit,
                                        fontFamily = JetBrainsMonoFamily,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = {
                                digits = ""
                                firstEntryForSetup = null
                                localError = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                        ) {
                            Text("Limpiar")
                        }
                        FilledTonalButton(
                            onClick = {
                                if (digits.length < maxDigits) {
                                    digits += "0"
                                    localError = null
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                                .testTag("pin_key_0"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = "0",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                if (digits.isNotEmpty()) {
                                    digits = digits.dropLast(1)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Borrar dígito"
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { submitCurrentDigits() },
                    enabled = digits.length >= 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("pin_submit_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = when {
                            isSetupMode && firstEntryForSetup == null -> "Continuar"
                            isSetupMode -> "Guardar PIN"
                            else -> "Desbloquear"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
