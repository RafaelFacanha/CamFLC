package com.camflc.app

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaActionSound
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
fun ProCameraScreen(
    baseDirectory: String,
    activeFolder: String,
    albumsList: List<AlbumInfo>,
    filePrefix: String,
    useNativeCameraFallback: Boolean,
    lastCaptureLog: String,
    registerVolumeTrigger: ((() -> Unit)?) -> Unit,
    onSelectFolder: (String) -> Unit,
    onCreateQuickFolder: (String, Boolean) -> Unit,
    onPhotoSaved: (String) -> Unit,
    onOpenGallery: (String?) -> Unit,
    onLaunchNativeCamera: () -> Unit,
    vibrateFeedback: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val shutterSound = remember {
        MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    }
    DisposableEffect(Unit) {
        onDispose { shutterSound.release() }
    }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var torchEnabled by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var showEvSlider by remember { mutableStateOf(false) }

    // Dialogo rapido para criar pasta ou subpasta sem sair da camera
    var showQuickNewFolderDialog by remember { mutableStateOf(false) }
    var createAsSubfolder by remember { mutableStateOf(true) }
    var quickFolderText by remember { mutableStateOf("") }

    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    var sensorMaxMegapixels by remember { mutableStateOf("MP Máx") }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(4f) }
    var currentZoomRatio by remember { mutableFloatStateOf(1f) }

    var evMin by remember { mutableIntStateOf(0) }
    var evMax by remember { mutableIntStateOf(0) }
    var currentEvIndex by remember { mutableIntStateOf(0) }

    // Estado do Circulo de Foco por Toque
    var focusTouchPoint by remember { mutableStateOf<Offset?>(null) }
    var focusIsLocked by remember { mutableStateOf(false) }
    var focusIndicatorText by remember { mutableStateOf("AF Contínuo") }
    var focusSessionId by remember { mutableIntStateOf(0) }

    // Estagio do obturador: 0 = Pronto | 1 = Capturando | 2 = Salvo
    var shutterStage by remember { mutableIntStateOf(0) }

    // Miniatura viva da ultima foto tirada
    var latestPhotoTick by remember { mutableIntStateOf(0) }
    val latestMediaItem = remember(baseDirectory, activeFolder, albumsList, latestPhotoTick) {
        StorageEngine.getLatestMediaFile(baseDirectory, activeFolder)
    }
    val latestThumbBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = latestMediaItem?.uniquePath
    ) {
        val item = latestMediaItem
        if (item == null || !item.file.exists()) {
            value = null
        } else {
            value = withContext(Dispatchers.IO) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(item.file.absolutePath, opts)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(focusSessionId) {
        if (focusSessionId > 0) {
            delay(1500)
            focusTouchPoint = null
        }
    }

    // Inicializa o motor CameraX em latencia minima e resolucao maxima do sensor
    LaunchedEffect(lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(98)
                    .setResolutionSelector(resolutionSelector)
                    .setFlashMode(flashMode)
                    .build()

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                cameraInstance = cam
                imageCaptureUseCase = imageCapture
                torchEnabled = false

                val cam2Info = Camera2CameraInfo.from(cam.cameraInfo)
                val map = cam2Info.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                val maxSize = map?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

                if (maxSize != null) {
                    val mp = (maxSize.width.toLong() * maxSize.height.toLong()) / 1_000_000.0
                    sensorMaxMegapixels = String.format("%.1f MP", mp)
                }

                val zoomState = cam.cameraInfo.zoomState.value
                minZoomRatio = zoomState?.minZoomRatio ?: 1f
                maxZoomRatio = zoomState?.maxZoomRatio ?: 4f
                currentZoomRatio = 1f

                val exposureState = cam.cameraInfo.exposureState
                evMin = exposureState.exposureCompensationRange.lower
                evMax = exposureState.exposureCompensationRange.upper
                currentEvIndex = exposureState.exposureCompensationIndex

                previewView.setOnTouchListener { view, motionEvent ->
                    if (motionEvent.action == MotionEvent.ACTION_UP) {
                        val touchX = motionEvent.x
                        val touchY = motionEvent.y

                        focusTouchPoint = Offset(touchX, touchY)
                        focusIsLocked = false
                        focusSessionId++
                        focusIndicatorText = "Focando..."

                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(touchX, touchY)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).build()

                        val future = cam.cameraControl.startFocusAndMetering(action)
                        future.addListener({
                            try {
                                val result = future.get()
                                focusIsLocked = result.isFocusSuccessful
                                focusIndicatorText = if (result.isFocusSuccessful) "AF Travado ✓" else "AF Ajustado"
                                vibrateFeedback()
                            } catch (_: Exception) {
                                focusIsLocked = true
                            }
                        }, ContextCompat.getMainExecutor(context))

                        view.performClick()
                    }
                    true
                }
            } catch (e: Exception) {
                onPhotoSaved("ERRO CÂMERA: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Disparo sincronizado (Volume - ou Botao Central)
    val captureProPhoto: () -> Unit = {
        val captureUseCase = imageCaptureUseCase
        if (captureUseCase != null && shutterStage == 0) {
            shutterStage = 1

            val targetFile = StorageEngine.createTargetMediaFile(
                baseRelativePath = baseDirectory,
                albumRelativePath = activeFolder,
                prefix = filePrefix,
                extension = "jpg"
            )

            captureUseCase.flashMode = flashMode
            val outputOptions = ImageCapture.OutputFileOptions.Builder(targetFile).build()

            captureUseCase.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                        vibrateFeedback()
                        shutterStage = 2
                        latestPhotoTick++

                        coroutineScope.launch {
                            delay(280)
                            shutterStage = 0
                        }

                        StorageEngine.indexFileInGallery(context, targetFile)
                        val kb = targetFile.length() / 1024
                        onPhotoSaved("✓ ${targetFile.name} ($kb KB)")
                    }

                    override fun onError(exception: ImageCaptureException) {
                        shutterStage = 0
                        onPhotoSaved("ERRO: ${exception.message}")
                    }
                }
            )
        }
    }

    LaunchedEffect(activeFolder, filePrefix, baseDirectory, imageCaptureUseCase, flashMode) {
        registerVolumeTrigger(captureProPhoto)
    }

    if (showQuickNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showQuickNewFolderDialog = false },
            containerColor = SolarWhite,
            title = {
                Text(
                    text = if (createAsSubfolder) "NOVA SUBPASTA EM '$activeFolder'" else "NOVA PASTA NA RAIZ",
                    color = SolarBlack,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = createAsSubfolder,
                            onClick = { createAsSubfolder = true },
                            label = { Text("Subpasta", fontWeight = FontWeight.Bold) }
                        )
                        FilterChip(
                            selected = !createAsSubfolder,
                            onClick = { createAsSubfolder = false },
                            label = { Text("Na Raiz", fontWeight = FontWeight.Bold) }
                        )
                    }
                    OutlinedTextField(
                        value = quickFolderText,
                        onValueChange = { quickFolderText = it },
                        label = { Text("Nome da pasta (ex: JF22JS35)", fontWeight = FontWeight.Bold) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (quickFolderText.isNotBlank()) {
                            onCreateQuickFolder(quickFolderText, createAsSubfolder)
                            quickFolderText = ""
                            showQuickNewFolderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber)
                ) {
                    Text("CRIAR E ATIVAR", fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickNewFolderDialog = false }) {
                    Text("CANCELAR", color = SolarBlack, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SolarBlack)
    ) {
        // 1. BARRA SUPERIOR DE CONTROLES RAPIDOS E SELETOR DE PASTA
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0F19))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Indicador do Sensor e Prefixo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = SolarAmber,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = sensorMaxMegapixels,
                            color = SolarBlack,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "🏷️ $filePrefix",
                        color = SolarWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Icones rapidos de Flash, Lanterna, Exposicao EV, Grade e Lente
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Flash
                    IconButton(
                        onClick = {
                            flashMode = when (flashMode) {
                                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                            imageCaptureUseCase?.flashMode = flashMode
                            vibrateFeedback()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        val icon = when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "Flash",
                            tint = if (flashMode == ImageCapture.FLASH_MODE_OFF) SolarWhite else SolarAmber
                        )
                    }

                    // Lanterna Continua (Torch)
                    IconButton(
                        onClick = {
                            torchEnabled = !torchEnabled
                            cameraInstance?.cameraControl?.enableTorch(torchEnabled)
                            vibrateFeedback()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Highlight,
                            contentDescription = "Lanterna",
                            tint = if (torchEnabled) SolarGreen else SolarWhite
                        )
                    }

                    // Controle de Exposicao (EV)
                    IconButton(
                        onClick = { showEvSlider = !showEvSlider },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Exposure,
                            contentDescription = "Exposição EV",
                            tint = if (showEvSlider || currentEvIndex != 0) SolarAmber else SolarWhite
                        )
                    }

                    // Grade 3x3
                    IconButton(
                        onClick = { showGrid = !showGrid },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = "Grade",
                            tint = if (showGrid) SolarAmber else SolarWhite
                        )
                    }

                    // Alternar Lente Traseira/Frontal
                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Alternar Lente",
                            tint = SolarWhite
                        )
                    }
                }
            }

            // Carrossel Deslizante de Troca de Pasta em 1 Segundo
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(albumsList) { album ->
                    val isSelected = album.relativePath == activeFolder
                    Surface(
                        color = if (isSelected) SolarAmber else Color(0xFF1E293B),
                        border = BorderStroke(1.5.dp, if (isSelected) SolarWhite else Color(0xFF475569)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .clickable { onSelectFolder(album.relativePath) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.FolderSpecial else Icons.Default.Folder,
                                contentDescription = null,
                                tint = if (isSelected) SolarBlack else SolarAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${album.relativePath} (${album.fileCount})",
                                color = if (isSelected) SolarBlack else SolarWhite,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // 2. VISOR OTICO CENTRAL AMPLIADO
        val borderColor = when (shutterStage) {
            1 -> SolarAmber
            2 -> SolarGreen
            else -> Color.Transparent
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(width = if (shutterStage > 0) 6.dp else 0.dp, color = borderColor)
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Grade 3x3 + Anel Duplo de Foco por Toque
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                if (showGrid) {
                    val gridColor = Color(0x4DFFFFFF)
                    drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1.5f)
                    drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 1.5f)
                    drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1.5f)
                    drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 1.5f)
                }

                focusTouchPoint?.let { centerPoint ->
                    val ringColor = if (focusIsLocked) SolarGreen else SolarAmber
                    val outerRadius = if (focusIsLocked) 80f else 100f

                    drawCircle(
                        color = Color.Black.copy(alpha = 0.6f),
                        radius = outerRadius + 4f,
                        center = centerPoint,
                        style = Stroke(width = 8f)
                    )
                    drawCircle(
                        color = ringColor,
                        radius = outerRadius,
                        center = centerPoint,
                        style = Stroke(width = 5f)
                    )
                    drawCircle(
                        color = ringColor,
                        radius = 8f,
                        center = centerPoint
                    )
                }
            }

            // Aviso Flutuante de Sincronia do Obturador
            if (shutterStage > 0) {
                Surface(
                    color = if (shutterStage == 1) SolarAmber else SolarGreen,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
                ) {
                    Text(
                        text = if (shutterStage == 1) "CAPTURANDO... SEGURE FIRME" else "✓ FOTO CAPTURADA!",
                        color = SolarBlack,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // Controles Flutuantes na Base do Visor (Barra EV + Pilulas de Zoom)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // Barra Deslizante de Exposicao (Exibida quando ativada ou ajustada)
                if ((showEvSlider || currentEvIndex != 0) && evMin < evMax) {
                    Surface(
                        color = Color(0xCC000000),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "☀️ EV: ${if (currentEvIndex > 0) "+$currentEvIndex" else "$currentEvIndex"}",
                                color = SolarAmber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Slider(
                                value = currentEvIndex.toFloat(),
                                onValueChange = { newVal ->
                                    val idx = newVal.roundToInt()
                                    currentEvIndex = idx
                                    cameraInstance?.cameraControl?.setExposureCompensationIndex(idx)
                                },
                                valueRange = evMin.toFloat()..evMax.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = SolarAmber,
                                    activeTrackColor = SolarAmber
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                            )
                            TextButton(
                                onClick = {
                                    currentEvIndex = 0
                                    cameraInstance?.cameraControl?.setExposureCompensationIndex(0)
                                }
                            ) {
                                Text("Zerar", color = SolarWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Pilulas Flutuantes de Zoom (0.6x, 1x, 2x, 4x)
                Surface(
                    color = Color(0xB3000000),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        val zoomSteps = buildList {
                            if (minZoomRatio < 0.95f) add(minZoomRatio)
                            add(1.0f)
                            if (maxZoomRatio >= 2.0f) add(2.0f)
                            if (maxZoomRatio >= 4.0f) add(4.0f.coerceAtMost(maxZoomRatio))
                        }
                        zoomSteps.forEach { targetZoom ->
                            val active = kotlin.math.abs(currentZoomRatio - targetZoom) < 0.15f
                            Surface(
                                color = if (active) SolarAmber else Color.Transparent,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clickable {
                                        currentZoomRatio = targetZoom
                                        cameraInstance?.cameraControl?.setZoomRatio(targetZoom)
                                        vibrateFeedback()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = String.format("%.1fx", targetZoom).replace(".0x", "x"),
                                        color = if (active) SolarBlack else SolarWhite,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. DECK INFERIOR FAMILIAR: [MINIATURA CLICAVEL] • [OBTURADOR CIRCULAR] • [+ SUBPASTA]
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0F19))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$lastCaptureLog • ($focusIndicatorText)",
                color = SolarGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // ESQUERDA: Miniatura da Ultima Foto Tirada (Clica e vai para a Galeria!)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        vibrateFeedback()
                        onOpenGallery(latestMediaItem?.uniquePath)
                    }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1E293B))
                            .border(2.dp, SolarAmber, RoundedCornerShape(14.dp))
                    ) {
                        if (latestThumbBitmap != null) {
                            Image(
                                bitmap = latestThumbBitmap!!,
                                contentDescription = "Abrir última foto na Galeria",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Abrir Galeria",
                                tint = SolarAmber,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Galeria",
                        color = SolarWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // CENTRO: Botao de Obturador Circular Classico (Sincronizado com Volume -)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .border(4.dp, SolarAmber, CircleShape)
                        .clickable { captureProPhoto() }
                        .padding(6.dp)
                ) {
                    Surface(
                        color = when (shutterStage) {
                            1 -> SolarWhite
                            2 -> SolarGreen
                            else -> SolarAmber
                        },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Capturar Foto",
                                tint = SolarBlack,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                // DIREITA: Botao Rapido "+ Subpasta" (e atalho opcional Camera Nativa)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            createAsSubfolder = true
                            showQuickNewFolderDialog = true
                        }
                    ) {
                        Surface(
                            color = Color(0xFF1E293B),
                            border = BorderStroke(2.dp, SolarGreen),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.size(58.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = "Criar Subpasta",
                                    tint = SolarGreen,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "+ Subpasta",
                            color = SolarWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (useNativeCameraFallback) {
                        IconButton(
                            onClick = onLaunchNativeCamera,
                            modifier = Modifier
                                .size(42.dp)
                                .border(1.dp, SolarAmber, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCameraBack,
                                contentDescription = "Câmera Nativa",
                                tint = SolarAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
