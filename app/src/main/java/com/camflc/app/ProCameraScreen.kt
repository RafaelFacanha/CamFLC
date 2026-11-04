package com.camflc.app

import android.annotation.SuppressLint
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
    onCreateQuickFolder: (String, Boolean) -> Unit,
    onPhotoSaved: (String) -> Unit,
    onLaunchNativeCamera: () -> Unit,
    vibrateFeedback: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Som oficial de obturador nativo do Android para confirmar o instante exato da captura
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

    // Dialogo de nova pasta / subpasta direto no visor
    var showQuickNewFolderDialog by remember { mutableStateOf(false) }
    var createAsSubfolder by remember { mutableStateOf(true) }
    var quickFolderText by remember { mutableStateOf("") }

    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    var sensorMaxMegapixels by remember { mutableStateOf("Calculando MP...") }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(4f) }
    var currentZoomRatio by remember { mutableFloatStateOf(1f) }

    var evMin by remember { mutableIntStateOf(0) }
    var evMax by remember { mutableIntStateOf(0) }
    var currentEvIndex by remember { mutableIntStateOf(0) }

    // Estado do Circulo Visual de Foco por Toque (Coordenadas X, Y + Status de Travamento)
    var focusTouchPoint by remember { mutableStateOf<Offset?>(null) }
    var focusIsLocked by remember { mutableStateOf(false) }
    var focusIndicatorText by remember { mutableStateOf("Toque na imagem para focar") }
    var focusSessionId by remember { mutableIntStateOf(0) }

    // Estados Reais do Obturador:
    // 0 = Pronto | 1 = Expondo Sensor (Segure Firme - Amarelo) | 2 = Captura Concluida (Pode Mover - Verde)
    var shutterStage by remember { mutableIntStateOf(0) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Esconde o circulo de foco suavemente 1.5s apos travar
    LaunchedEffect(focusSessionId) {
        if (focusSessionId > 0) {
            delay(1600)
            focusTouchPoint = null
        }
    }

    // Inicializa CameraX com Prioridade de Disparo Rapido (Zero-Lag) e Resolucao Maxima do Sensor
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

                // CAPTURE_MODE_MINIMIZE_LATENCY elimina o atraso entre o clique e a captura real da imagem
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
                    sensorMaxMegapixels = String.format("%.1f MP (%dx%d)", mp, maxSize.width, maxSize.height)
                } else {
                    sensorMaxMegapixels = "Resolução Máxima Nativa"
                }

                val zoomState = cam.cameraInfo.zoomState.value
                minZoomRatio = zoomState?.minZoomRatio ?: 1f
                maxZoomRatio = zoomState?.maxZoomRatio ?: 4f
                currentZoomRatio = 1f

                val exposureState = cam.cameraInfo.exposureState
                evMin = exposureState.exposureCompensationRange.lower
                evMax = exposureState.exposureCompensationRange.upper
                currentEvIndex = exposureState.exposureCompensationIndex

                // Toque para Focar com Circulo Visual no Ponto Exato (Amarelo -> Verde)
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
                                focusIndicatorText = if (result.isFocusSuccessful) {
                                    "Foco Travado ✓"
                                } else {
                                    "Foco Ajustado"
                                }
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
                onPhotoSaved("ERRO HARDWARE CÂMERA: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Funcao de Captura Sincronizada (So vibra e pisca Verde quando a imagem ja foi congelada!)
    val captureProPhoto: () -> Unit = {
        val captureUseCase = imageCaptureUseCase
        if (captureUseCase != null && shutterStage == 0) {
            // Estagio 1: Avisa que o sensor esta lendo a luz (Sem vibrar antes da hora!)
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
                        // Estagio 2: Quadro 100% capturado e salvo -> Toca Obturador + Vibra + Pisca Borda Verde!
                        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                        vibrateFeedback()
                        shutterStage = 2
                        coroutineScope.launch {
                            delay(300)
                            shutterStage = 0
                        }

                        StorageEngine.indexFileInGallery(context, targetFile)
                        val kb = targetFile.length() / 1024
                        onPhotoSaved("✓ CAPTURA CONCLUÍDA ($kb KB): $activeFolder/${targetFile.name}")
                    }

                    override fun onError(exception: ImageCaptureException) {
                        shutterStage = 0
                        onPhotoSaved("ERRO NA CAPTURA: ${exception.message}")
                    }
                }
            )
        }
    }

    LaunchedEffect(activeFolder, filePrefix, baseDirectory, imageCaptureUseCase, flashMode) {
        registerVolumeTrigger(captureProPhoto)
    }

    // Dialogo Rapido para Criar Pasta ou Subpasta Sem Sair do Visor
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
                            label = { Text("Subpasta de '$activeFolder'", fontWeight = FontWeight.Bold) }
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
                        label = { Text("Nome (ex: JF22JS35)", fontWeight = FontWeight.Bold) },
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
        // 1. HUD SUPERIOR SOLAR: Sensor + Criador de Subpasta + Seletor de Pastas
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
                    text = "SENSOR: $sensorMaxMegapixels",
                    color = SolarAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = focusIndicatorText,
                    color = if (focusIsLocked) SolarGreen else SolarAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

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
                            .clickable {
                                createAsSubfolder = true
                                showQuickNewFolderDialog = true
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = SolarBlack)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ SUBPASTA", color = SolarBlack, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }
                }

                items(albumsList) { album ->
                    val isSelected = album.relativePath == activeFolder
                    Surface(
                        color = if (isSelected) SolarAmber else Color(0xFF222222),
                        border = BorderStroke(2.dp, if (isSelected) SolarWhite else SolarAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(42.dp)
                            .clickable { onSelectFolder(album.relativePath) }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = "${album.relativePath} (${album.fileCount})",
                                color = if (isSelected) SolarBlack else SolarWhite,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // 2. VISOR OTICO CENTRAL COM CIRCULO DE FOCO POR TOQUE E SINALIZADOR DE CAPTURA REAL
        val borderColor = when (shutterStage) {
            1 -> SolarAmber // Lendo sensor: Segure firme
            2 -> SolarGreen // Foto salva: Pode mover!
            else -> Color(0x66FFD600)
        }
        val borderWidth = if (shutterStage > 0) 8.dp else 2.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(width = borderWidth, color = borderColor)
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Camada Grafica: Grade 3x3 + Circulo Visual de Foco no Ponto Tocado
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                if (showGrid) {
                    val gridColor = Color(0x66FFD600)
                    drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 2f)
                    drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 2f)
                    drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 2f)
                    drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 2f)
                }

                // Desenha o Circulo Habitual de Foco na coordenada exata do toque
                focusTouchPoint?.let { centerPoint ->
                    val ringColor = if (focusIsLocked) SolarGreen else SolarAmber
                    val outerRadius = if (focusIsLocked) 85f else 105f

                    // Sombra preta de contraste para visibilidade sob sol forte
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.65f),
                        radius = outerRadius + 6f,
                        center = centerPoint,
                        style = Stroke(width = 10f)
                    )
                    // Anel principal (Amarelo focando -> Verde travado)
                    drawCircle(
                        color = ringColor,
                        radius = outerRadius,
                        center = centerPoint,
                        style = Stroke(width = 6f)
                    )
                    // Ponto central de mira
                    drawCircle(
                        color = ringColor,
                        radius = 10f,
                        center = centerPoint
                    )
                }
            }

            // Aviso Central de Captura Sincronizada (Evita mover o celular antes da hora)
            if (shutterStage > 0) {
                Surface(
                    color = if (shutterStage == 1) SolarAmber else SolarGreen,
                    border = BorderStroke(3.dp, SolarBlack),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = if (shutterStage == 1) {
                            "CAPTURANDO... SEGURE FIRME"
                        } else {
                            "✓ FOTO CAPTURADA! PODE MOVER"
                        },
                        color = SolarBlack,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Barra de Log Inferior
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

        // 3. PAINEL INFERIOR DE CONTROLES DE HARDWARE
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SolarBlack)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
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

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = captureProPhoto,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (shutterStage == 1) SolarWhite else SolarAmber,
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
                        text = when (shutterStage) {
                            1 -> "CAPTURANDO... SEGURE!"
                            2 -> "✓ FOTO SALVA!"
                            else -> "CAPTURAR ($activeFolder) • VOL -"
                        },
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
