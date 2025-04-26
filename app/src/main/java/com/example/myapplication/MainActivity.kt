@file:kotlin.OptIn(ExperimentalPermissionsApi::class)
// MainActivity.kt

package com.example.myapplication

import androidx.compose.ui.viewinterop.AndroidView
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.accompanist.permissions.isGranted
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                QRScannerScreen(onSubmit = { decoded, inOut, agent, manager, other ->
                    // fire off POST
                    lifecycleScope.launch(Dispatchers.IO) {
                        sendDataToGoogleScript(decoded, inOut, agent, manager, other)
                    }
                })
            }
        }
    }
}


@Composable
fun QRScannerScreen(onSubmit: (String, String, String, String, String) -> Unit) {
    // permissions
    val camPerm = rememberPermissionState(android.Manifest.permission.CAMERA)

    // scanner state
    var lastDecoded by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf(false) }

    // form fields
    var inOut by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var manager by remember { mutableStateOf("") }
    var other by remember { mutableStateOf("") }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (!camPerm.status.isGranted) {
                if (camPerm.status.shouldShowRationale) {
                    Text("Camera needed to scan QR codes")
                }
                Button({ camPerm.launchPermissionRequest() }) {
                    Text("Grant camera")
                }
                return@Column
            }
            // PreviewView for CameraX
            AndroidView(factory = { ctx ->
                PreviewView(ctx).apply {
                    val provider = ProcessCameraProvider.getInstance(ctx).get()
                    val preview = androidx.camera.core.Preview.Builder().build()
                    preview.surfaceProvider = surfaceProvider

                    val analyzer = ImageAnalysis.Builder().build().also {
                        it.setAnalyzer(ctx.mainExecutor, QRAnalyzer(onResult = { raw ->
                            // binary → text
                            if (isValidBinary(raw)) {
                                lastDecoded = binaryToText(raw)
                                showForm = true
                                errorMsg = null
                            } else {
                                errorMsg = "Invalid binary code"
                            }
                        }))
                    }
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, analyzer
                    )
                }
            }, modifier = Modifier.weight(1f))

            Spacer(Modifier.height(8.dp))
            errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (showForm) {
                OutlinedTextField(
                    value = inOut, onValueChange = { inOut = it },
                    label = { Text("In or Out") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = agent, onValueChange = { agent = it },
                    label = { Text("Agent Name") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = manager, onValueChange = { manager = it },
                    label = { Text("Manager Name") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = other, onValueChange = { other = it },
                    label = { Text("Other Equipment") }, modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    onSubmit(
                        lastDecoded, inOut.ifBlank { "N/A" },
                        agent.ifBlank { "N/A" },
                        manager.ifBlank { "N/A" },
                        other.ifBlank { "N/A" })
                    showForm = false
                }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Submit Scan")
                }
            }
        }
    }
}
// analyzer that reads barcodes and calls back the raw bits string
class QRAnalyzer(
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()


    override fun analyze(image: ImageProxy) {
        image.image?.let { mediaImage ->
            val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    barcodes.firstOrNull()?.rawValue?.let { onResult(it) }
                }
                .addOnCompleteListener { image.close() }
        } ?: image.close()
    }
}

// simple binary validators in Kotlin:
fun isValidBinary(s: String): Boolean {
    val clean = s.replace("\\s+".toRegex(), "")
    return clean.matches(Regex("[01]+")) && clean.length % 8 == 0
}
fun binaryToText(s: String): String {
    return s.trim().chunked(8)
        .map { it.toInt(2).toChar() }
        .joinToString("")
}

// networking: POST your JSON to the Script URL
fun sendDataToGoogleScript(
    decodedText: String,
    inOut:       String,
    agent:       String,
    manager:     String,
    other:       String
) {
    try {
        var infoLog = JSONObject().apply {
            put("decodedText", decodedText)
            put("inOut", inOut)
            put("agent" , agent)
            put("manager", manager)
            put("otherEquip", other)
        }.toString()

        Log.d("info dumped : ", infoLog)
        val url = URL("https://script.google.com/macros/s/AKfycbw2H-2hTNHzaPhLtxSbM7azhPFOZkE2tw1POKbXWvNpOBFiqFPBgXbQN9NHI97esFQ4/exec")
        with(url.openConnection() as HttpURLConnection) {
            requestMethod = "POST"
            setRequestProperty("Content-Type","application/json")
            doOutput = true
            outputStream.use { it.write(
                infoLog.toByteArray()
            ) }
            Log.d("POST","resp=${responseCode}")
        }
    } catch (e: Exception) {
        Log.e("POST","failed", e)
    }
}