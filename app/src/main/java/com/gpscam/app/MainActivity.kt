package com.gpscam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
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
import androidx.compose.ui.graphics.ImageBitmap
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

    // Saved manual overrides (leave blank for automatic GPS mode)
    var title by remember { mutableStateOf(prefs.getString("title", "") ?: "") }
    var fixedAddress by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    var note by remember { mutableStateOf(prefs.getString("note", "") ?: "") }
    var lockedLat by remember { mutableStateOf(prefs.getString("locked_lat", null)?.toDoubleOrNull()) }
    var lockedLng by remember { mutableStateOf(prefs.getString("locked_lng", null)?.toDoubleOrNull()) }

    // Auto-fetched live values
    var autoTitle by remember { mutableStateOf("") }
    var autoAddress by remember { mutableStateOf("Fetching location...") }
    var liveLat by remember { mutableStateOf<Double?>(null) }
    var liveLng by remember { mutableStateOf<Double?>(null) }
    var lastAutoLat by remember { mutableStateOf<Double?>(null) }
    var lastAutoLng by remember { mutableStateOf<Double?>(null) }

    var mapVersion by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    // Aspect ratio & last saved photo viewer
    var aspectRatio by remember { mutableStateOf(prefs.getString("aspect_ratio", "4:3") ?: "4:3") }
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var lastSavedThumb by remember { mutableStateOf<ImageBitmap?>(null) }

    val activeLat = lockedLat ?: liveLat
    val activeLng = lockedLng ?: liveLng
    val activeTitle = title.ifBlank { autoTitle }
    val activeAddress = fixedAddress.ifBlank { autoAddress }

    // Camera states
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember { PreviewView(context) }

    fun loadThumbnail(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val bmp = try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }?.asImageBitmap()
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                lastSavedUri = uri
                lastSavedThumb = bmp
            }
        }
    }

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

                val savedUri = if (copied) {
                    ImageStamper.stampAndSave(
                        context,
                        tempFile,
                        StampData(activeTitle, activeAddress, activeLat, activeLng, note),
                        mapThumbFile,
                        aspectRatio = null
                    )
                } else null

                withContext(Dispatchers.Main) {
                    isSaving = false
                    if (savedUri != null) {
                        loadThumbnail(savedUri)
                        Toast.makeText(context, "Geo-tagged photo saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to stamp photo", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Live GPS updates -> auto-fetch Header, Address, and Map Tile
    DisposableEffect(hasPermissions, lockedLat, lockedLng) {
        if (!hasPermissions) return@DisposableEffect onDispose {}
        val client = LocationServices.getFusedLocationProviderClient(context)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                val loc = res.lastLocation ?: return
                liveLat = loc.latitude
                liveLng = loc.longitude

                if (lockedLat != null && lockedLng != null) return

                val movedEnough = if (lastAutoLat == null || lastAutoLng == null) {
                    true
                } else {
                    val dist = FloatArray(1)
                    Location.distanceBetween(lastAutoLat!!, lastAutoLng!!, loc.latitude, loc.longitude, dist)
                    dist[0] > 50f
                }

                if (movedEnough) {
                    lastAutoLat = loc.latitude
                    lastAutoLng = loc.longitude
                    scope.launch(Dispatchers.IO) {
                        val geo = MapTileHelper.fetchGeoDetails(context, loc.latitude, loc.longitude)
                        val tileOk = MapTileHelper.downloadMapTile(loc.latitude, loc.longitude, mapThumbFile)
                        withContext(Dispatchers.Main) {
                            if (geo != null) {
                                autoTitle = geo.header
                                autoAddress = geo.fullAddress
                            }
                            if (tileOk) mapVersion++
                        }
                    }
                }
            }
        }
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(callback) }
    }

    // Bind CameraX
    LaunchedEffect(hasPermissions, lensFacing, flashOn, aspectRatio) {
        if (!hasPermissions) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        val camRatio = if (aspectRatio == "16:9") AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3

        @Suppress("DEPRECATION")
        val preview = Preview.Builder()
            .setTargetAspectRatio(camRatio)
            .build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        @Suppress("DEPRECATION")
        val capture = ImageCapture.Builder()
            .setTargetAspectRatio(camRatio)
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
        // Camera Preview
        val previewModifier = when (aspectRatio) {
            "1:1" -> Modifier.fillMaxWidth().aspectRatio(1f).align(Alignment.Center)
            "4:3" -> Modifier.fillMaxWidth().aspectRatio(3f / 4f).align(Alignment.Center)
            else -> Modifier.fillMaxSize()
        }
        AndroidView(factory = { previewView }, modifier = previewModifier)

        // Top Bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { flashOn = !flashOn },
                modifier = Modifier.background(Color(0x990F172A), CircleShape)
            ) {
                Icon(if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flash", tint = Color.White)
            }

            // Aspect Ratio Toggle Pill
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC0F172A))
                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(20.dp))
                    .clickable {
                        aspectRatio = when (aspectRatio) {
                            "4:3" -> "16:9"
                            "16:9" -> "1:1"
                            else -> "4:3"
                        }
                        prefs.edit().putString("aspect_ratio", aspectRatio).apply()
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(aspectRatio, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                    if (activeTitle.isNotBlank()) {
                        Text(activeTitle, color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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

            // Controls Row: Gallery Upload (Left) + Shutter (Center) + View Last Saved Photo (Right)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                                            val savedUri = ImageStamper.stampAndSave(
                                                context,
                                                tempFile,
                                                StampData(activeTitle, activeAddress, activeLat, activeLng, note),
                                                mapThumbFile,
                                                aspectRatio = aspectRatio
                                            )
                                            withContext(Dispatchers.Main) {
                                                isSaving = false
                                                if (savedUri != null) {
                                                    loadThumbnail(savedUri)
                                                    Toast.makeText(context, "Saved to Pictures/GPSCamera", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                                                }
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

                // View Last Saved Photo Button (Right)
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0F172A))
                        .border(1.5.dp, if (lastSavedUri != null) Color.White else Color(0x6694A3B8), CircleShape)
                        .clickable(enabled = lastSavedUri != null) {
                            lastSavedUri?.let { uri ->
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "image/jpeg")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "No gallery app found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (lastSavedThumb != null) {
                        Image(
                            bitmap = lastSavedThumb!!,
                            contentDescription = "View Last Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.Image, "Last Photo", tint = Color(0xFF94A3B8))
                    }
                }
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
                        label = { Text("Header (blank = auto)") },
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
                                val geo = MapTileHelper.fetchGeoDetails(context, parsedLat, parsedLng)
                                val tileOk = MapTileHelper.downloadMapTile(parsedLat, parsedLng, mapThumbFile)
                                withContext(Dispatchers.Main) {
                                    isFetching = false
                                    lockedLat = parsedLat
                                    lockedLng = parsedLng
                                    if (geo != null) {
                                        title = geo.header
                                        fixedAddress = geo.fullAddress
                                    }
                                    if (tileOk) mapVersion++
                                    Toast.makeText(
                                        context,
                                        if (tileOk) "Header, Address & Map updated" else "Address updated (map failed)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isFetching) "Fetching..." else "Fetch Header, Address & Map")
                    }
                    OutlinedTextField(
                        value = fixedAddress,
                        onValueChange = { fixedAddress = it },
                        label = { Text("Address (blank = auto)") },
                        maxLines = 3
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Footer Note (optional)") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { mapPicker.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pick Map", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                lockedLat = null
                                lockedLng = null
                                title = ""
                                fixedAddress = ""
                                lastAutoLat = null
                                lastAutoLng = null
                                prefs.edit()
                                    .remove("locked_lat")
                                    .remove("locked_lng")
                                    .remove("title")
                                    .remove("address")
                                    .apply()
                                Toast.makeText(context, "Switched to Auto Live GPS", Toast.LENGTH_SHORT).show()
                                showSettings = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset Auto", fontSize = 12.sp)
                        }
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
