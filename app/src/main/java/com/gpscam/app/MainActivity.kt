package com.gpscam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF38BDF8),
                surface = Color(0xFF0F172A),
                background = Color(0xFF020617)
            )) {
                GpsCameraScreen()
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun GpsCameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("gps_cam_prefs", Context.MODE_PRIVATE) }
    val mapThumbFile = remember { File(context.filesDir, "map_thumb.png") }

    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermissions = result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    // Saved settings (including optional locked Lat/Long)
    var title by remember { mutableStateOf(prefs.getString("title", "") ?: "") }
    var fixedAddress by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    var note by remember { mutableStateOf(prefs.getString("note", "") ?: "") }
    var lockedLat by remember { mutableStateOf(prefs.getString("locked_lat", null)?.toDoubleOrNull()) }
    var lockedLng by remember { mutableStateOf(prefs.getString("locked_lng", null)?.toDoubleOrNull()) }

    var autoAddress by remember { mutableStateOf("Fetching address...") }
    var liveLat by remember { mutableStateOf<Double?>(null) }
    var liveLng by remember { mutableStateOf<Double?>(null) }
    var mapVersion by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    val activeLat = lockedLat ?: liveLat
    val activeLng = lockedLng ?: liveLng
    val activeAddress = fixedAddress.ifBlank { autoAddress }

    // Camera states
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember { PreviewView(context) }

    // Live clock
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("dd/MM/yyyy hh:mm:ss a", Locale.getDefault())
        while (true) {
            currentTime = fmt.format(Date())
            delay(1000)
        }
    }

    // Pick custom map thumbnail
    val mapPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    mapThumbFile.outputStream().use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) { mapVersion++ }
            }
        }
    }

    // Pick an existing gallery photo to geo-tag
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && !isSaving) {
            isSaving = true
            scope.launch(Dispatchers.IO) {
                val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
                val copied = try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                } catch (_: Exception) {
                    false
                }

                val ok = if (copied) {
                    ImageStamper.stampAndSave(
                        context,
                        tempFile,
                        StampData(title, activeAddress, activeLat, activeLng, note),
                        mapThumbFile
                    )
                } else false

                withContext(Dispatchers.Main) {
                    isSaving = false
                    Toast.makeText(
                        context,
                        if (ok) "Geo-tagged photo saved to Pictures/GPSCamera" else "Failed to stamp photo",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Live GPS updates
    DisposableEffect(hasPermissions) {
        if (!hasPermissions) return@DisposableEffect onDispose {}
        val client = LocationServices.getFusedLocationProviderClient(context)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                val loc = res.lastLocation ?: return
                liveLat = loc.latitude
                liveLng = loc.longitude
                if (fixedAddress.isBlank() && lockedLat == null) {
                    scope.launch(Dispatchers.IO) {
                        val addr = MapTileHelper.fetchAddress(context, loc.latitude, loc.longitude)
                        if (!addr.isNullOrBlank()) {
                            withContext(Dispatchers.Main) { autoAddress = addr }
                        }
                    }
                }
            }
        }
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(callback) }
    }

    // Bind CameraX
    LaunchedEffect(hasPermissions, lensFacing, flashOn) {
        if (!hasPermissions) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()
        imageCapture = capture
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                preview,
                capture
            )
        } catch (_: Exception) {}
    }

    if (!hasPermissions) {
        Box(Modifier.fillMaxSize().background(Color(0xFF020617)), contentAlignment = Alignment.Center) {
            Button(onClick = {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
            }) { Text("Grant Camera & GPS Permissions") }
        }
        return
    }

    val mapBitmap = remember(mapVersion) {
        if (mapThumbFile.exists()) BitmapFactory.decodeFile(mapThumbFile.absolutePath)?.asImageBitmap() else null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Top Bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { flashOn = !flashOn },
                modifier = Modifier.background(Color(0x990F172A), CircleShape)
            ) {
                Icon(if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flash", tint = Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    },
                    modifier = Modifier.background(Color(0x990F172A), CircleShape)
                ) {
                    Icon(Icons.Default.Cameraswitch, "Flip", tint = Color.White)
                }
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.background(Color(0x990F172A), CircleShape)
                ) {
                    Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                }
            }
        }

        // Bottom Live Overlay + Controls
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Live Stamp Preview Card
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xD90F172A))
                    .border(1.dp, Color(0x8864748B), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .clickable { showSettings = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (mapBitmap != null) {
                        Image(mapBitmap, "Map", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("TAP\nMAP", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (title.isNotBlank()) {
                        Text(title, color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(activeAddress, color = Color.White, fontSize = 12.sp, maxLines = 2)
                    Text(
                        if (activeLat != null && activeLng != null)
                            String.format(Locale.US, "Lat %.6f°  Long %.6f°", activeLat, activeLng)
                        else "Waiting for GPS...",
                        color = Color(0xFFCBD5E1), fontSize = 11.sp
                    )
                    Text(currentTime, color = Color(0xFFCBD5E1), fontSize = 11.sp)
                    if (note.isNotBlank()) {
                        Text(note, color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Controls Row: Gallery Upload + Shutter
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Gallery Upload Button (Left)
                IconButton(
                    enabled = !isSaving,
                    onClick = { photoPicker.launch("image/*") },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                        .size(52.dp)
                        .background(Color(0xCC0F172A), CircleShape)
                        .border(1.5.dp, Color(0xFF38BDF8), CircleShape)
                ) {
                    Icon(Icons.Default.PhotoLibrary, "Geo-tag Gallery Photo", tint = Color.White)
                }

                // Shutter Button (Center)
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (isSaving) Color(0xFF38BDF8) else Color.White)
                        .clickable(enabled = !isSaving) {
                            val capture = imageCapture ?: return@clickable
                            isSaving = true
                            val tempFile = File(context.cacheDir, "raw_${System.currentTimeMillis()}.jpg")
                            val opts = ImageCapture.OutputFileOptions.Builder(tempFile).build()
                            capture.takePicture(
                                opts,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        scope.launch(Dispatchers.IO) {
                                            val ok = ImageStamper.stampAndSave(
                                                context,
                                                tempFile,
                                                StampData(title, activeAddress, activeLat, activeLng, note),
                                                mapThumbFile
                                            )
                                            withContext(Dispatchers.Main) {
                                                isSaving = false
                                                Toast.makeText(
                                                    context,
                                                    if (ok) "Saved to Pictures/GPSCamera" else "Save failed",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        isSaving = false
                                        Toast.makeText(context, "Capture error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                )
            }
        }
    }

    // Settings Dialog
    if (showSettings) {
        var latInput by remember { mutableStateOf(activeLat?.toString() ?: "") }
        var lngInput by remember { mutableStateOf(activeLng?.toString() ?: "") }
        var isFetching by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = Color(0xFF0F172A),
            title = { Text("Location & Stamp Settings", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Header / Place Name") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = latInput,
                            onValueChange = { latInput = it },
                            label = { Text("Latitude") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = lngInput,
                            onValueChange = { lngInput = it },
                            label = { Text("Longitude") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        enabled = !isFetching,
                        onClick = {
                            val parsedLat = latInput.trim().toDoubleOrNull()
                            val parsedLng = lngInput.trim().toDoubleOrNull()
                            if (parsedLat == null || parsedLng == null) {
                                Toast.makeText(context, "Enter valid latitude & longitude", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isFetching = true
                            scope.launch(Dispatchers.IO) {
                                val fetchedAddr = MapTileHelper.fetchAddress(context, parsedLat, parsedLng)
                                val tileOk = MapTileHelper.downloadMapTile(parsedLat, parsedLng, mapThumbFile)
                                withContext(Dispatchers.Main) {
                                    isFetching = false
                                    lockedLat = parsedLat
                                    lockedLng = parsedLng
                                    if (!fetchedAddr.isNullOrBlank()) fixedAddress = fetchedAddr
                                    if (tileOk) mapVersion++
                                    Toast.makeText(
                                        context,
                                        if (tileOk) "Address & Map updated" else "Address updated (map failed)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isFetching) "Fetching..." else "Fetch Address & Map from Lat/Long")
                    }
                    OutlinedTextField(
                        value = fixedAddress,
                        onValueChange = { fixedAddress = it },
                        label = { Text("Address (auto-filled or edit)") },
                        maxLines = 3
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Footer Note (optional)") },
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = { mapPicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Or Pick Custom Map Image")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val editor = prefs.edit()
                        .putString("title", title)
                        .putString("address", fixedAddress)
                        .putString("note", note)
                    if (lockedLat != null && lockedLng != null) {
                        editor.putString("locked_lat", lockedLat.toString())
                        editor.putString("locked_lng", lockedLng.toString())
                    }
                    editor.apply()
                    showSettings = false
                }) { Text("Save") }
            }
        )
    }
}
