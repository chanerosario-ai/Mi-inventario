package com.example.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun BarcodeScannerViewport(
    modifier: Modifier = Modifier,
    cooldownMs: Long = 2500L,
    continuousMode: Boolean = false,
    onBarcodeDetected: (rawValue: String, formatLabel: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequestedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequestedOnce) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var torchEnabled by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var lastCode by remember { mutableStateOf<String?>(null) }
    var lastScanTime by remember { mutableLongStateOf(0L) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    if (!hasCameraPermission) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Permiso de cámara requerido",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Se necesita la cámara para leer códigos EAN-13, EAN-8, UPC-A, UPC-E, Code 128 y QR sin conexión. También puedes escribir o buscar el código manualmente abajo.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Permitir cámara")
                    }
                    OutlinedButton(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }) {
                        Text("Ajustes")
                    }
                }
            }
        }
        return
    }

    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_QR_CODE
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { barcodeScanner.close() }
            analyzerExecutor.shutdown()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().apply {
                            surfaceProvider = previewView.surfaceProvider
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                            processImageProxy(
                                barcodeScanner = barcodeScanner,
                                imageProxy = imageProxy,
                                onDetected = { raw, formatName ->
                                    val now = System.currentTimeMillis()
                                    val sameCodeWithinCooldown =
                                        raw == lastCode && (now - lastScanTime) < cooldownMs
                                    val singleShotRecent =
                                        !continuousMode && (now - lastScanTime) < 1200L && raw == lastCode
                                    if (!sameCodeWithinCooldown && !singleShotRecent) {
                                        lastCode = raw
                                        lastScanTime = now
                                        ContextCompat.getMainExecutor(ctx).execute {
                                            onBarcodeDetected(raw, formatName)
                                        }
                                    }
                                }
                            )
                        }

                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                        boundCamera = camera
                        cameraError = null
                    } catch (e: Exception) {
                        cameraError = "Cámara no disponible en este dispositivo (${e.localizedMessage ?: "error"})."
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // Aiming reticle overlay
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.75f)
                .height(140.dp)
                .background(Color.Transparent)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(2.dp, Color(0xFFFBBF24))
            ) {}
        }

        // Torch toggle button
        IconButton(
            onClick = {
                val next = !torchEnabled
                torchEnabled = next
                boundCamera?.cameraControl?.enableTorch(next)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
        ) {
            Icon(
                imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = if (torchEnabled) "Apagar linterna" else "Encender linterna",
                tint = if (torchEnabled) Color(0xFFFBBF24) else Color.White
            )
        }

        // Mode indicator pill
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(10.dp),
            color = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                text = if (continuousMode) "Modo continuo activo • EAN / UPC / Code128 / QR" else "Apunta al código de barras o QR",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (cameraError != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = cameraError!!,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: androidx.camera.core.ImageProxy,
    onDetected: (String, String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    barcodeScanner.process(image)
        .addOnSuccessListener { barcodes ->
            val first = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
            if (first != null) {
                val raw = first.rawValue!!.trim()
                val formatLabel = when (first.format) {
                    Barcode.FORMAT_EAN_13 -> "EAN-13"
                    Barcode.FORMAT_EAN_8 -> "EAN-8"
                    Barcode.FORMAT_UPC_A -> "UPC-A"
                    Barcode.FORMAT_UPC_E -> "UPC-E"
                    Barcode.FORMAT_CODE_128 -> "Code 128"
                    Barcode.FORMAT_QR_CODE -> "QR"
                    else -> "Código"
                }
                onDetected(raw, formatLabel)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

@Composable
fun InlineScannerDialog(
    title: String = "Escanear código",
    cooldownMs: Long = 2500L,
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(12.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar escáner")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                BarcodeScannerViewport(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    cooldownMs = cooldownMs,
                    continuousMode = false,
                    onBarcodeDetected = { raw, _ ->
                        onScanned(raw)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Cancelar")
                }
            }
        }
    }
}
