package com.camflc.app

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    onCreateQuickFolder: (String) -> Unit,
    onPhotoSaved: (String) -> Unit,
    onLaunchNativeCamera: () -> Unit,
    vibrateFeedback: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Controles de camera e hardware
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var torchEnabled by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var showQuickNewFolderDialog by remember { mutableStateOf(false) }
    var quickFolderText by remember { mutableStateOf("") }

    // Estados lidos diretamente do hardware do dispositivo (CameraCharacteristics)
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    var sensorMaxMegapixels by remember { mutableStateOf("Calculando MP...") }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(4f) }
    var currentZoomRatio by remember { mutableFloatStateOf(1f) }

    var evMin by remember { mutableIntStateOf(0) }
    var evMax by remember { mutableIntStateOf(0) }
    var currentEvIndex by remember { mutableIntStateOf(0) }

    // Feedback visual de captura sob sol forte e indicador de foco
    var captureBorderFlash by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var focusIndicatorText by remember { mutableStateOf("AF Contínuo") }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Inicializa o motor CameraX em Qualidade Maxima de Hardware
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
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
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

                // Leitura de limites fisicos do sensor via Camera2Interop
                val cam2Info = Camera2CameraInfo.from(cam.cameraInfo)
                val map = cam2Info.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                val maxSize = map?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

                if (maxSize != null) {
                    val mp = (maxSize.width.toLong() * maxSize.height.toLong()) / 1_000_000.0
                    sensorMaxMegapixels = String.format("%.1f MP (%dx%d)", mp, maxSize.width, maxSize.height)
                } else {
                    sensorMaxMegapixels = "Resolução Máxima Nativa"
                }

                // Limites de Zoom do aparelho
                val zoomState = cam.cameraInfo.zoomState.value
                minZoomRatio = zoomState?.minZoomRatio ?: 1f
                maxZoomRatio = zoomState?.maxZoomRatio ?: 4f
                currentZoomRatio = 1f

                // Limites de Exposicao (EV) do sensor
                val exposureState = cam.cameraInfo.exposureState
                evMin = exposureState.exposureCompensationRange.lower
                evMax = exposureState.exposureCompensationRange.upper
                currentEvIndex = exposureState.exposureCompensationIndex

                // Configura Toque para Focar (Tap-to-Focus + Fotometria no ponto)
                previewView.setOnTouchListener { view, motionEvent ->
                    if (motionEvent.action == MotionEvent.ACTION_UP) {
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(motionEvent.x, motionEvent.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).build()
                        cam.cameraControl.startFocusAndMetering(action)
                        focusIndicatorText = "Foco Cravado (${motionEvent.x.roundToInt()}, ${motionEvent.y.roundToInt()})"
                        vibrateFeedback()
                        view.performClick()
                    }
                    true
                }
            } catch (e: Exception) {
                onPhotoSaved("ERRO HARDWARE CÂMERA: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Funcao de Captura Real Instantanea em JPEG 100% (Acionada pelo Botao na Tela ou Volume -)
    val captureProPhoto: () -> Unit = {
        val captureUseCase = imageCaptureUseCase
        if (captureUseCase != null && !isCapturing) {
            isCapturing = true
            vibrateFeedback()
            coroutineScope.launch {
                captureBorderFlash = true
                delay(180)
                captureBorderFlash = false
            }

            val targetFile = StorageEngine.createTargetMediaFile(
                baseRelativePath = baseDirectory,
                albumName = activeFolder,
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
                        isCapturing = false
                        StorageEngine.indexFileInGallery(context, targetFile)
                        val kb = targetFile.length() / 1024
                        onPhotoSaved("FOTO PRO SALVA ($kb KB): ${targetFile.parentFile?.name}/${targetFile.name}")
                    }

                    override fun onError(exception: ImageCaptureException) {
                        isCapturing = false
                        onPhotoSaved("ERRO NA CAPTURA: ${exception.message}")
                    }
                }
            )
        }
    }

    // Conecta o gatilho fisico Volume - diretamente ao obturador da Camera Pro
    LaunchedEffect(activeFolder, filePrefix, baseDirectory, imageCaptureUseCase, flashMode) {
        registerVolumeTrigger(captureProPhoto)
    }

    // Dialogo Rapido para Criar Nova Pasta Sem Sair do Visor da Camera
    if (showQuickNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showQuickNewFolderDialog = false },
            containerColor = SolarWhite,
            title = {
                Text("NOVA PASTA NO VISOR", color = SolarBlack, fontWeight = FontWeight.ExtraBold)
            },
            text = {
                OutlinedTextField(
                    value = quickFolderText,
                    onValueChange = { quickFolderText = it },
                    label = { Text("Nome da Nova Pasta/Álbum", fontWeight = FontWeight.Bold) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (quickFolderText.isNotBlank()) {
                            onCreateQuickFolder(quickFolderText)
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
        // 1. HUD SUPERIOR SOLAR: Info do Sensor + Seletor Agil de Pastas em 1 Segundo
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SolarBlack)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "SENSOR: $sensorMaxMegapixels • JPEG 100%",
                    color = SolarAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = focusIndicatorText,
                    color = SolarGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // Carrossel de Pastas + Botao "+ Nova" direto no visor
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Surface(
                        color = SolarGreen,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(42.dp)
                            .clickable { showQuickNewFolderDialog = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = SolarBlack)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ PASTA", color = SolarBlack, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        }
                    }
                }

                items(albumsList) { album ->
                    val isSelected = album.name == activeFolder
                    Surface(
                        color = if (isSelected) SolarAmber else Color(0xFF222222),
                        border = BorderStroke(2.dp, if (isSelected) SolarWhite else SolarAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(42.dp)
                            .clickable { onSelectFolder(album.name) }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = "${album.name} (${album.fileCount})",
                                color = if (isSelected) SolarBlack else SolarWhite,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // 2. VISOR OTICO CENTRAL COM GRADE 3x3 E BORDA DE CONFIRMACAO DE DISPARO
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(
                    width = if (captureBorderFlash) 6.dp else 2.dp,
                    color = if (captureBorderFlash) SolarGreen else SolarAmber
                )
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Grade Profissional de Enquadramento 3x3 (Regra dos Tercos)
            if (showGrid) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val gridColor = Color(0x88FFD600)
                    drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 2f)
                    drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 2f)
                    drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 2f)
                    drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 2f)
                }
            }

            // Pilula Flutuante de Prefixo e Status no Rodape do Visor
            Surface(
                color = Color(0xD9000000),
                border = BorderStroke(1.dp, SolarAmber),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
            ) {
                Text(
                    text = lastCaptureLog,
                    color = SolarGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // 3. PAINEL INFERIOR DE CONTROLES DE HARDWARE (ZOOM, EV, FLASH, LANTERNA E DISPARO)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SolarBlack)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Linha de Zoom Rapido + Controles de Luz
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Seletores Rapidos de Zoom Fisico/Digital
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val zoomSteps = buildList {
                        if (minZoomRatio < 0.95f) add(minZoomRatio)
                        add(1.0f)
                        if (maxZoomRatio >= 2.0f) add(2.0f)
                        if (maxZoomRatio >= 4.0f) add(4.0f.coerceAtMost(maxZoomRatio))
                    }
                    zoomSteps.forEach { targetZoom ->
                        val active = kotlin.math.abs(currentZoomRatio - targetZoom) < 0.15f
                        Surface(
                            color = if (active) SolarAmber else Color(0xFF262626),
                            border = BorderStroke(1.dp, SolarAmber),
                            shape = CircleShape,
                            modifier = Modifier
                                .size(38.dp)
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

                // Botoes de Flash, Lanterna Continua (Torch), Grade e Lente Frontal/Traseira
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Alternar Flash de Disparo
                    OutlinedButton(
                        onClick = {
                            flashMode = when (flashMode) {
                                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                            imageCaptureUseCase?.flashMode = flashMode
                            vibrateFeedback()
                        },
                        border = BorderStroke(1.dp, SolarAmber),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        val flashLabel = when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> "FLASH ON"
                            ImageCapture.FLASH_MODE_AUTO -> "FLASH AUTO"
                            else -> "FLASH OFF"
                        }
                        Text(flashLabel, color = SolarAmber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    // Lanterna Continua (Torch) para ambientes escuros
                    OutlinedButton(
                        onClick = {
                            torchEnabled = !torchEnabled
                            cameraInstance?.cameraControl?.enableTorch(torchEnabled)
                            vibrateFeedback()
                        },
                        border = BorderStroke(1.dp, if (torchEnabled) SolarGreen else SolarAmber),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (torchEnabled) SolarGreen else Color.Transparent
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = if (torchEnabled) "LUZ ON" else "LUZ",
                            color = if (torchEnabled) SolarBlack else SolarAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    // Grade 3x3
                    IconButton(
                        onClick = { showGrid = !showGrid },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = null,
                            tint = if (showGrid) SolarAmber else SolarWhite
                        )
                    }

                    // Alternar Camera Traseira / Frontal
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
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = null, tint = SolarAmber)
                    }
                }
            }

            // Controle de Exposicao Manual (EV) para compensar sol forte ou contraluz
            if (evMin < evMax) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "LUZ (EV): ${if (currentEvIndex > 0) "+$currentEvIndex" else "$currentEvIndex"}",
                        color = SolarAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.width(78.dp)
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
                            .height(26.dp)
                    )
                }
            }

            // Botao Principal de Captura Instantanea + Atalho Opcional Camera Nativa
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = captureProPhoto,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SolarAmber,
                        contentColor = SolarBlack
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                ) {
                    Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isCapturing) "GRAVANDO..." else "CAPTURAR (${activeFolder}) • VOL -",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (useNativeCameraFallback) {
                    OutlinedButton(
                        onClick = onLaunchNativeCamera,
                        border = BorderStroke(2.dp, SolarAmber),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(60.dp)
                    ) {
                        Icon(Icons.Default.PhotoCameraBack, contentDescription = null, tint = SolarAmber)
                    }
                }
            }
        }
    }
}
