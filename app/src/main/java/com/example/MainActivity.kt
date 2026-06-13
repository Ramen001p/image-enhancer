package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.EnhancerViewModel
import com.example.ui.GeminiState
import com.example.processing.ImageProcessor
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    WorkspaceScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

enum class EditorTab {
    TUNE, REFINE, EXTRACT, RESOLUTION, GEMINI
}

@Composable
fun WorkspaceScreen(modifier: Modifier = Modifier) {
    val viewModel: EnhancerViewModel = viewModel()
    val context = LocalContext.current

    // --- State collections ---
    val original by viewModel.originalBitmap.collectAsState()
    val structural by viewModel.structuralBitmap.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val geminiState by viewModel.geminiState.collectAsState()

    val brightness by viewModel.brightness.collectAsState()
    val contrast by viewModel.contrast.collectAsState()
    val saturation by viewModel.saturation.collectAsState()
    val hue by viewModel.hue.collectAsState()

    val sharpness by viewModel.sharpness.collectAsState()
    val denoise by viewModel.denoise.collectAsState()

    val cropLeft by viewModel.cropLeft.collectAsState()
    val cropTop by viewModel.cropTop.collectAsState()
    val cropRight by viewModel.cropRight.collectAsState()
    val cropBottom by viewModel.cropBottom.collectAsState()
    val isCropModeActive by viewModel.isCropModeActive.collectAsState()

    // Active screen sub-tab selection
    var selectedTab by remember { mutableStateOf(EditorTab.TUNE) }

    // State for temporary before/after compare (holding compares shows original)
    var showOriginalCompare by remember { mutableStateOf(false) }

    // --- Primer / Load Default Almirah Photo on launch ---
    LaunchedEffect(Unit) {
        if (viewModel.originalBitmap.value == null) {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.almirah_art)
            if (bitmap != null) {
                viewModel.setPhoto(bitmap)
            }
        }
    }

    // --- System Photo Picker ---
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val selected = BitmapFactory.decodeStream(stream)
                    if (selected != null) {
                        viewModel.setPhoto(selected)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Color Matrix computed real-time values ---
    val colorMatrixArray = remember(brightness, contrast, saturation, hue) {
        ImageProcessor.createColorMatrix(brightness, contrast, saturation, hue)
    }
    val composeColorFilter = remember(colorMatrixArray) {
        ColorFilter.colorMatrix(ColorMatrix(colorMatrixArray))
    }

    // Studio workspace background (Dark slate aesthetic)
    Column(
        modifier = modifier
            .background(Color(0xFF090A0F))
            .fillMaxSize()
    ) {
        // 1. Header Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(Color(0xFFFF3B30), Color(0xFFFFCC00))
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "Studio Icon",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "AuraEnhance",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = "AI FINE TUNER",
                        color = Color(0xFFFFCC00),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Action Toolbar (Import, Undo, Reset)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Reset Button
                IconButton(
                    onClick = { viewModel.resetToOriginal() },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0xFF1D2030),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reset",
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Undo Button
                IconButton(
                    onClick = { viewModel.undo() },
                    enabled = canUndo,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (canUndo) Color(0xFF1D2030) else Color(0xFF131522),
                        contentColor = if (canUndo) Color.White else Color.Gray
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Undo,
                        contentDescription = "Undo",
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Import Photo Button
                Button(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9F0A),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = "Gallery",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Divider(color = Color(0xFF1C1E2A), thickness = 1.dp)

        // 2. Main Viewport (Image Box)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val displayBitmap = if (showOriginalCompare) original else structural

            if (displayBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF2A2D3E), RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F111E)),
                    contentAlignment = Alignment.Center
                ) {
                    // Display image itself
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = "Main Canvas",
                        contentScale = ContentScale.Fit,
                        colorFilter = if (showOriginalCompare) null else composeColorFilter,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )

                    // DRAG-CROP MASK (Overlay display when crop tab is active)
                    if (isCropModeActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawBehind {
                                    val w = this.size.width
                                    val h = this.size.height

                                    val leftX = cropLeft * w
                                    val topY = cropTop * h
                                    val rightX = cropRight * w
                                    val bottomY = cropBottom * h

                                    // Draw dim outer overlay
                                    // 1. Top block
                                    drawRect(
                                        color = Color.Black.copy(alpha = 0.65f),
                                        size = Size(w, topY)
                                    )
                                    // 2. Left block
                                    drawRect(
                                        color = Color.Black.copy(alpha = 0.65f),
                                        topLeft = Offset(0f, topY),
                                        size = Size(leftX, bottomY - topY)
                                    )
                                    // 3. Right block
                                    drawRect(
                                        color = Color.Black.copy(alpha = 0.65f),
                                        topLeft = Offset(rightX, topY),
                                        size = Size(w - rightX, bottomY - topY)
                                    )
                                    // 4. Bottom block
                                    drawRect(
                                        color = Color.Black.copy(alpha = 0.65f),
                                        topLeft = Offset(0f, bottomY),
                                        size = Size(w, h - bottomY)
                                    )

                                    // Draw Crop Bounding lines (Amber accent)
                                    drawRect(
                                        color = Color(0xFFFFCC00),
                                        topLeft = Offset(leftX, topY),
                                        size = Size(rightX - leftX, bottomY - topY),
                                        style = Stroke(width = 2f * density)
                                    )
                                }
                        )
                    }

                    // Before/After comparison overlay text indicator
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.75f),
                            border = BorderStroke(1.dp, Color(0xFF2C2F48)),
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        showOriginalCompare = true
                                        tryAwaitRelease()
                                        showOriginalCompare = false
                                    }
                                )
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Compare,
                                    contentDescription = "Compare",
                                    tint = Color(0xFFFF9F0A),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "HOLD TO COMPARE ORIGINAL",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // Empty state
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = "Placeholder",
                        tint = Color.DarkGray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Loading original asset...",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // 3. Command sliders + Options Panel
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = Color(0xFF0F111A),
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, Color(0xFF1E2135))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // Dynamic Inner Panel based on selected sub-tab
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    when (selectedTab) {
                        EditorTab.TUNE -> TunePanel(viewModel)
                        EditorTab.REFINE -> RefinePanel(viewModel)
                        EditorTab.EXTRACT -> ExtractPanel(viewModel)
                        EditorTab.RESOLUTION -> ResolutionPanel(viewModel)
                        EditorTab.GEMINI -> GeminiPanel(viewModel)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Navigation Pills for category selection (Material Touch Target conforming)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF08090E), RoundedCornerShape(12.dp))
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    EditorNavButton(
                        label = "Tune",
                        icon = Icons.Rounded.Tune,
                        selected = selectedTab == EditorTab.TUNE,
                        onClick = {
                            selectedTab = EditorTab.TUNE
                            viewModel.isCropModeActive.value = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                    EditorNavButton(
                        label = "Refine",
                        icon = Icons.Rounded.AutoAwesome,
                        selected = selectedTab == EditorTab.REFINE,
                        onClick = {
                            selectedTab = EditorTab.REFINE
                            viewModel.isCropModeActive.value = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                    EditorNavButton(
                        label = "Extract",
                        icon = Icons.Rounded.Crop,
                        selected = selectedTab == EditorTab.EXTRACT,
                        onClick = {
                            selectedTab = EditorTab.EXTRACT
                            viewModel.isCropModeActive.value = true
                        },
                        modifier = Modifier.weight(1f)
                    )
                    EditorNavButton(
                        label = "HD Upscale",
                        icon = Icons.Rounded.AspectRatio,
                        selected = selectedTab == EditorTab.RESOLUTION,
                        onClick = {
                            selectedTab = EditorTab.RESOLUTION
                            viewModel.isCropModeActive.value = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                    EditorNavButton(
                        label = "AI Magic",
                        icon = Icons.Rounded.AutoAwesome,
                        selected = selectedTab == EditorTab.GEMINI,
                        onClick = {
                            selectedTab = EditorTab.GEMINI
                            viewModel.isCropModeActive.value = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // Full-screen structural operation background loader
    if (isProcessing) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f))
                .clickable(enabled = false) {}, // eat clicks
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                border = BorderStroke(1.dp, Color(0xFF2B314B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(32.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFFF9F0A),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Enhancing Masterpiece...",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Running on-device fine-tuning algorithms",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun EditorNavButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .height(50.dp) // Touch targets >= 48dp
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF1D2034) else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color(0xFFFF9F0A) else Color.Gray,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                color = if (selected) Color.White else Color.Gray,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// Dialog-styled tuning panel for Color adjust controls
@Composable
fun TunePanel(viewModel: EnhancerViewModel) {
    val b by viewModel.brightness.collectAsState()
    val c by viewModel.contrast.collectAsState()
    val s by viewModel.saturation.collectAsState()
    val h by viewModel.hue.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("COLOR ACCENT FINE TUNING", fontSize = 11.sp, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

        // Brightness
        Column {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Brightness", color = Color.White, fontSize = 12.sp)
                Text("${b.toInt()}%", color = Color(0xFFFF9F0A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = b,
                onValueChange = { viewModel.brightness.value = it },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF9F0A),
                    activeTrackColor = Color(0xFFFF9F0A),
                    inactiveTrackColor = Color(0xFF232537)
                )
            )
        }

        // Contrast
        Column {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Contrast", color = Color.White, fontSize = 12.sp)
                Text("${c.toInt()}%", color = Color(0xFFFF9F0A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = c,
                onValueChange = { viewModel.contrast.value = it },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF9F0A),
                    activeTrackColor = Color(0xFFFF9F0A),
                    inactiveTrackColor = Color(0xFF232537)
                )
            )
        }

        // Saturation
        Column {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Saturation (Golds & Red Pop)", color = Color.White, fontSize = 12.sp)
                Text("${s.toInt()}%", color = Color(0xFFFF9F0A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = s,
                onValueChange = { viewModel.saturation.value = it },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF9F0A),
                    activeTrackColor = Color(0xFFFF9F0A),
                    inactiveTrackColor = Color(0xFF232537)
                )
            )
        }

        // Hue
        Column {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Hue Shift", color = Color.White, fontSize = 12.sp)
                Text("${h.toInt()}°", color = Color(0xFFFF9F0A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = h,
                onValueChange = { viewModel.hue.value = it },
                valueRange = -180f..180f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF9F0A),
                    activeTrackColor = Color(0xFFFF9F0A),
                    inactiveTrackColor = Color(0xFF232537)
                )
            )
        }
    }
}

// Dialog-styled refine panel (Deblur & Denoise)
@Composable
fun RefinePanel(viewModel: EnhancerViewModel) {
    val sharpness by viewModel.sharpness.collectAsState()
    val denoise by viewModel.denoise.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PIXEL CLARITY & DENOISE GATE", fontSize = 11.sp, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

            // Sharpness (Remove blurness)
            Column {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove Blurness (Sharpness)", color = Color.White, fontSize = 12.sp)
                    Text("${sharpness.toInt()}%", color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = sharpness,
                    onValueChange = { viewModel.sharpness.value = it },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF3B30),
                        activeTrackColor = Color(0xFFFF3B30),
                        inactiveTrackColor = Color(0xFF232537)
                    )
                )
            }

            // Denoise (Remove grain)
            Column {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove Noise / Speckles", color = Color.White, fontSize = 12.sp)
                    Text("${denoise.toInt()}%", color = Color(0xFF34C759), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = denoise,
                    onValueChange = { viewModel.denoise.value = it },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF34C759),
                        activeTrackColor = Color(0xFF34C759),
                        inactiveTrackColor = Color(0xFF232537)
                    )
                )
            }
        }

        // Action Trigger Button
        Button(
            onClick = { viewModel.applyStructuralEdits() },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF3B30),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp) // Touch targets >= 48dp
        ) {
            Icon(Icons.Rounded.Healing, contentDescription = "Refine", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Apply Refinement Algorithms", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Dialog-styled Extract/Crop panel
@Composable
fun ExtractPanel(viewModel: EnhancerViewModel) {
    val l by viewModel.cropLeft.collectAsState()
    val t by viewModel.cropTop.collectAsState()
    val r by viewModel.cropRight.collectAsState()
    val b by viewModel.cropBottom.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("EXTRACT BOUNDS (CROP)", fontSize = 11.sp, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                
                // Quick Presets row
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Almirah Extract Preset", 
                        color = Color(0xFFFFCC00), 
                        fontSize = 10.sp, 
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xFF332000), RoundedCornerShape(4.dp))
                            .clickable {
                                // Ideal cropping margins to extract our beautiful Almirah door art perfectly 
                                // and exclude top header and bottom search bars automatically!
                                viewModel.cropLeft.value = 0.05f
                                viewModel.cropTop.value = 0.11f
                                viewModel.cropRight.value = 0.95f
                                viewModel.cropBottom.value = 0.76f
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Margin Controllers (Sliders are incredibly precise to prevent erratic multi touch issues)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Left Margin
                Column(modifier = Modifier.weight(1f)) {
                    Text("Left margin: ${(l*100).toInt()}%", color = Color.Gray, fontSize = 10.sp)
                    Slider(
                        value = l,
                        onValueChange = { viewModel.cropLeft.value = it.coerceIn(0f, r - 0.1f) },
                        valueRange = 0f..0.45f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF9F0A), activeTrackColor = Color(0xFFFF9F0A))
                    )
                }
                // Right Margin
                Column(modifier = Modifier.weight(1f)) {
                    Text("Right margin: ${(r*100).toInt()}%", color = Color.Gray, fontSize = 10.sp)
                    Slider(
                        value = r,
                        onValueChange = { viewModel.cropRight.value = it.coerceIn(l + 0.1f, 1f) },
                        valueRange = 0.55f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF9F0A), activeTrackColor = Color(0xFFFF9F0A))
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Top Margin
                Column(modifier = Modifier.weight(1f)) {
                    Text("Top margin: ${(t*100).toInt()}%", color = Color.Gray, fontSize = 10.sp)
                    Slider(
                        value = t,
                        onValueChange = { viewModel.cropTop.value = it.coerceIn(0f, b - 0.1f) },
                        valueRange = 0f..0.45f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF9F0A), activeTrackColor = Color(0xFFFF9F0A))
                    )
                }
                // Bottom Margin
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bottom margin: ${(b*100).toInt()}%", color = Color.Gray, fontSize = 10.sp)
                    Slider(
                        value = b,
                        onValueChange = { viewModel.cropBottom.value = it.coerceIn(t + 0.1f, 1f) },
                        valueRange = 0.55f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF9F0A), activeTrackColor = Color(0xFFFF9F0A))
                    )
                }
            }
        }

        Button(
            onClick = { viewModel.applyCrop() },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF9F0A),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp) // Touch targets >= 48dp
        ) {
            Icon(Icons.Rounded.CropFree, contentDescription = "Isolate Crop", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Extract Selected Artwork Bounds", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Resolution Upscaling Panel
@Composable
fun ResolutionPanel(viewModel: EnhancerViewModel) {
    val structural by viewModel.structuralBitmap.collectAsState()
    val width = structural?.width ?: 0
    val height = structural?.height ?: 0

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("HIGH RESOLUTION ENHANCEMENT", fontSize = 11.sp, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

            Text(
                text = "Bilinear upscaling combined with details interest high-pass filter gives crispy, razor sharp textures without grain.",
                fontSize = 11.sp,
                color = Color.Gray,
                lineHeight = 15.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141624), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CURRENT GRID SIZE", color = Color.Gray, fontSize = 9.sp)
                    Text("$width × $height px", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Divider(modifier = Modifier.height(24.dp).width(1.dp), color = Color.DarkGray)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TYPE", color = Color.Gray, fontSize = 9.sp)
                    Text("Ultra-HD 32-bit", color = Color(0xFFFF9F0A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 1.5x Scaling
            OutlinedButton(
                onClick = { viewModel.applyUpscale(1.5f) },
                border = BorderStroke(1.dp, Color(0xFFFF9F0A)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9F0A)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Text("1.5x Detail Boost", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            // 2.0x Scaling
            Button(
                onClick = { viewModel.applyUpscale(2.0f) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9F0A), contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Icon(Icons.Rounded.Hd, contentDescription = "HD", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("2.0x Super-HD Res", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Gemini AI analysis & suggestion tab
@Composable
fun GeminiPanel(viewModel: EnhancerViewModel) {
    val geminiState by viewModel.geminiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        when (val state = geminiState) {
            GeminiState.Idle -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f).padding(top = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = "Gemini", tint = Color(0xFFFFCC00), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("GEMINI SMART ART RESTORATION", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "Send your active workspace photo to Google Gemini. Gemini will analyze composition, identify blur/noise details, and automatically adjust sliders with precision calibration settings!",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }

                    Button(
                        onClick = { viewModel.runGeminiSmartEnhance() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9F0A), contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Icon(Icons.Rounded.Psychology, contentDescription = "Think", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Gemini AI Auto-Cure & Calibrate", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            GeminiState.Analyzing -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color(0xFFFF9F0A), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Gemini is reading compositions...", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Synthesizing expert enhancement instructions", color = Color.Gray, fontSize = 10.sp)
                }
            }

            is GeminiState.Success -> {
                val blueprint = state.blueprint
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = blueprint.artworkTitle.uppercase(),
                            color = Color(0xFFFFCC00),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF003814), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("TUNED SUCCESSFULLY", color = Color(0xFF34C759), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        text = blueprint.analysis,
                        color = Color.White,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Divider(color = Color.DarkGray, thickness = 0.5.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Gemini recommendation applied:", color = Color.Gray, fontSize = 10.sp)
                        Text(
                            text = "Contrast +${blueprint.suggestedContrast} | Sharpness +${blueprint.recommendedSharpness} | Denoise +${blueprint.recommendedDenoise}",
                            color = Color(0xFFFF9F0A),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Expert Insight: ${blueprint.restorationInsight}",
                        color = Color(0xFFFF9F0A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 14.sp,
                        modifier = Modifier
                            .background(Color(0xFF2C1E00), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "Analyze again",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { viewModel.resetToOriginal() }
                                .padding(4.dp)
                        )
                    }
                }
            }

            is GeminiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Rounded.Error, contentDescription = "Error", tint = Color(0xFFFF3B30), modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        color = Color(0xFFFF3B30),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.runGeminiSmartEnhance() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2231), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Retry AI Auto-Cure", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
