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

    var focusTouchPoint by remember { mutableStateOf<Offset?>(null) }
    var focusIsLocked by remember { mutableStateOf(false) }
    var focusIndicatorText by remember { mutableStateOf("AF Contínuo") }
    var focusSessionId by remember { mutableIntStateOf(0) }

    var shutterStage by remember { mutableIntStateOf(0) }

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
                        Button(
                            onClick = { createAsSubfolder = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (createAsSubfolder) SolarAmber else SolarSurface,
                                contentColor = SolarBlack
                            ),
                            border = BorderStroke(1.5.dp, SolarBlack),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Subpasta", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { createAsSubfolder = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!createAsSubfolder) SolarAmber else SolarSurface,
                                contentColor = SolarBlack
                            ),
                            border = BorderStroke(1.5.dp, SolarBlack),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Na Raiz", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
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

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

                    IconButton
