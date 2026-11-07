package com.camflc.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.util.Range
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
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
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class HardwareCameraLens(
    val cameraId: String,
    val label: String,
    val lensFacing: Int,
    val focalLength: Float,
    val minFocusDistance: Float,
    val maxMegapixels: Double
)

@OptIn(ExperimentalCamera2Interop::class, ExperimentalZeroShutterLag::class)
@SuppressLint("ClickableViewAccessibility", "MissingPermission")
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
    onOpenGallery: (String?, String) -> Unit,
    onLaunchNativeCamera: () -> Unit,
    vibrateFeedback: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Executor dedicado em background para salvar JPEGs sem travar o visor
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }
    val shutterSound = remember {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            ioExecutor.shutdown()
            shutterSound.release()
        }
    }

    // Modo Principal: false = FOTO (.JPG) | true = VIDEO (.MP4)
    var isVideoMode by remember { mutableStateOf(false) }

    // Lista Suspensa (Dropdown) de Pastas no Topo da Camera
    var folderDropdownExpanded by remember { mutableStateOf(false) }
    var folderFilterText by remember { mutableStateOf("") }

    // Dialogo rapido para criar Subpasta/Pasta
    var showQuickNewFolderDialog by remember { mutableStateOf(false) }
    var createAsSubfolder by remember { mutableStateOf(true) }
    var quickFolderText by remember { mutableStateOf("") }

    // Lentes Fisicas Detectadas no Hardware
    var availableLenses by remember { mutableStateOf<List<HardwareCameraLens>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var currentLensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    // Controles de Hardware (Foto e Video)
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var torchEnabled by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var showEvSlider by remember { mutableStateOf(false) }
    // Prioridade de Foto: true = Velocidade Maxima (~0.15s) | false = Qualidade Maxima 100%
    var fastZeroLagPriority by remember { mutableStateOf(true) }

    // Modos de Foco Camera2: 0 = AF-C Auto | 1 = MACRO Close-up | 2 = Manual (MF Slider)
    var focusModeIndex by remember { mutableIntStateOf(0) }
    var manualFocusDiopter by remember { mutableFloatStateOf(0f) }
    var maxFocusDiopter by remember { mutableFloatStateOf(10f) }

    // Balanco de Branco (AWB): Auto, Sol, Nublado, Fluorescente, Incandescente
    val awbModes = remember {
        listOf(
            "AWB: Auto" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
            "☀️ Sol" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            "☁️ Nublado" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            "💡 Fluor" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
            "🕯️ Quente" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        )
    }
    var awbIndex by remember { mutableIntStateOf(0) }
    var hdrSceneEnabled by remember { mutableStateOf(false) }

    // Configuracoes de Video Pro (4K / 1080p / 720p + 30/60 FPS + Microfone)
    var selectedVideoQuality by remember { mutableStateOf(Quality.FHD) }
    var supportedVideoQualities by remember { mutableStateOf(listOf(Quality.FHD, Quality.HD)) }
    var targetVideoFps by remember { mutableIntStateOf(30) }
    var supports60Fps by remember { mutableStateOf(false) }
    var audioEnabled by remember { mutableStateOf(true) }

    // Estado da Gravacao de Video Ativa
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var isVideoPaused by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableLongStateOf(0L) }

    // Instancias CameraX
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCaptureUseCase by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    var sensorBadgeText by remember { mutableStateOf("MP Máx") }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(4f) }
    var currentZoomRatio by remember { mutableFloatStateOf(1f) }

    var evMin by remember { mutableIntStateOf(0) }
    var evMax by remember { mutableIntStateOf(0) }
    var currentEvIndex by remember { mutableIntStateOf(0) }

    // Estado do Foco por Toque
    var focusTouchPoint by remember { mutableStateOf<Offset?>(null) }
    var focusIsLocked by remember { mutableStateOf(false) }
    var focusIndicatorText by remember { mutableStateOf("AF Contínuo") }
    var focusSessionId by remember { mutableIntStateOf(0) }

    // Estagio do Obturador: 0 = Pronto | 2 = SINAL VERDE INSTANTANEO (~0.15s)
    var shutterStage by remember { mutableIntStateOf(0) }
    var savingInBackgroundCount by remember { mutableIntStateOf(0) }

    // Miniatura viva da ultima foto/video da PASTA ATIVA
    var latestMediaTick by remember { mutableIntStateOf(0) }
    val latestMediaItem = remember(baseDirectory, activeFolder, albumsList, latestMediaTick) {
        StorageEngine.getLatestMediaFile(baseDirectory, activeFolder)
    }
    val latestThumbBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = latestMediaItem?.uniquePath,
        key2 = latestMediaTick
    ) {
        val item = latestMediaItem
        if (item == null || !item.file.exists()) {
            value = null
        } else {
            value = withContext(Dispatchers.IO) {
                StorageEngine.loadMediaThumbnail(item.file, sampleSize = 4)?.asImageBitmap()
            }
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Cronometro de Video em Tempo Real
    LaunchedEffect(isRecordingVideo, isVideoPaused) {
        while (isRecordingVideo) {
            delay(1000)
            if (!isVideoPaused) {
                recordingSeconds++
            }
        }
    }

    LaunchedEffect(focusSessionId) {
        if (focusSessionId > 0) {
            delay(1400)
            focusTouchPoint = null
        }
    }

    // Funcao que aplica em tempo real os Controles Hardware Camera2 (Foco, Macro, MF, AWB, HDR, 60fps, OIS/EIS)
    val applyHardwareCamera2Options: (Camera) -> Unit = { cam ->
        try {
            val cam2Control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            // 1. Modo de Foco (AF-C Foto/Video, Macro ou Manual MF)
            when (focusModeIndex) {
                0 -> {
                    val afMode = if (isVideoMode) {
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    } else {
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    }
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
                    focusIndicatorText = if (isVideoMode) "AF-Vídeo Contínuo" else "AF-C Automático"
                }
                1 -> {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_MACRO
                    )
                    focusIndicatorText = "🌷 Foco Macro Ativo"
                }
                2 -> {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    builder.setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        manualFocusDiopter
                    )
                    val distLabel = if (manualFocusDiopter <= 0.05f) "Infinito ∞" else String.format(Locale.US, "%.1f dpt", manualFocusDiopter)
                    focusIndicatorText = "📏 MF Manual ($distLabel)"
                }
            }

            // 2. Balanco de Branco (AWB)
            val awbModeValue = awbModes[awbIndex].second
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbModeValue)

            // 3. Modo HDR / Cena
            if (hdrSceneEnabled) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CameraMetadata.CONTROL_SCENE_MODE_HDR
                )
            }

            // 4. Estabilizacao Dupla (OIS Optica + EIS Eletronica de Video) e Reducao de Ruido Rapida
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
            builder.setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            )

            // 5. Taxa de Quadros em Modo Video (30 FPS vs 60 FPS)
            if (isVideoMode) {
                val fps = if (targetVideoFps == 60 && supports60Fps) 60 else 30
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(fps, fps)
                )
            }

            cam2Control.captureRequestOptions = builder.build()
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(focusModeIndex, manualFocusDiopter, awbIndex, hdrSceneEnabled, targetVideoFps, isVideoMode) {
        cameraInstance?.let { applyHardwareCamera2Options(it) }
    }

    // Inicializacao completa do Hardware CameraX + Enumeracao das Lentes Fisicas (Ultra-Wide, Macro, Principal, Frontal)
    LaunchedEffect(selectedCameraId, currentLensFacing, isVideoMode, selectedVideoQuality, fastZeroLagPriority) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                // Enumera todas as cameras disponiveis no aparelho e classifica (Ultra-Wide, Principal, Macro, Tele, Frontal)
                val detected = mutableListOf<HardwareCameraLens>()
                val backInfos = mutableListOf<Triple<String, Float, Pair<Float, Double>>>()

                for (info in cameraProvider.availableCameraInfos) {
                    val c2 = Camera2CameraInfo.from(info)
                    val id = c2.cameraId
                    val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                        ?: CameraCharacteristics.LENS_FACING_BACK
                    val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.firstOrNull() ?: 4.0f
                    val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        ?: 0f
                    val map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val maxSz = map?.getOutputSizes(ImageFormat.JPEG)
                        ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    val mp = if (maxSz != null) (maxSz.width.toLong() * maxSz.height.toLong()) / 1_000_000.0 else 12.0

                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        backInfos.add(Triple(id, focal, minFocus to mp))
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        detected.add(
                            HardwareCameraLens(
                                cameraId = id,
                                label = "🔄 Frontal",
                                lensFacing = CameraSelector.LENS_FACING_FRONT,
                                focalLength = focal,
                                minFocusDistance = minFocus,
                                maxMegapixels = mp
                            )
                        )
                    }
                }

                val sortedBack = backInfos.sortedBy { it.second }
                sortedBack.forEachIndexed { idx, (id, focal, pair) ->
                    val (minF, mp) = pair
                    val label = when {
                        idx == 0 && sortedBack.size > 1 && focal < 3.2f -> "0.5x Ultra"
                        minF >= 12f && mp <= 5.5 -> "🌷 Macro"
                        idx == 0 -> "1x Principal"
                        else -> "Lente ${idx + 1} (${String.format(Locale.US, "%.0fMP", mp)})"
                    }
                    detected.add(
                        HardwareCameraLens(
                            cameraId = id,
                            label = label,
                            lensFacing = CameraSelector.LENS_FACING_BACK,
                            focalLength = focal,
                            minFocusDistance = minF,
                            maxMegapixels = mp
                        )
                    )
                }
                availableLenses = detected

                // Seleciona a lente fisica escolhida pelo usuario ou o padrao traseiro/frontal
                val cameraSelector = if (selectedCameraId != null) {
                    CameraSelector.Builder()
                        .addCameraFilter { camInfos ->
                            val filtered = camInfos.filter {
                                Camera2CameraInfo.from(it).cameraId == selectedCameraId
                            }
                            filtered.ifEmpty { camInfos }
                        }
                        .build()
                } else {
                    CameraSelector.Builder()
                        .requireLensFacing(currentLensFacing)
                        .build()
                }

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                // Configura Fotografia com Latencia Zero (ZSL) ou Qualidade Maxima
                val captureMode = if (fastZeroLagPriority) {
                    ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                } else {
                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(captureMode)
                    .setJpegQuality(if (fastZeroLagPriority) 92 else 99)
                    .setResolutionSelector(resolutionSelector)
                    .setFlashMode(flashMode)
                    .build()

                // Configura Gravador de Video com Bitrate Alto (24 Mbps) e Qualidade Selecionada
                val qualitySelector = QualitySelector.from(
                    selectedVideoQuality,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .setTargetVideoEncodingBitRate(24_000_000)
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                val cam = if (isVideoMode) {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        videoCapture
                    )
                } else {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                }

                cameraInstance = cam
                imageCaptureUseCase = imageCapture
                videoCaptureUseCase = videoCapture

                // Inspeciona capacidades reais da lente ativa
                val cam2Info = Camera2CameraInfo.from(cam.cameraInfo)
                val activeCamId = cam2Info.cameraId
                if (selectedCameraId == null) selectedCameraId = activeCamId

                val map = cam2Info.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                val maxSize = map?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

                val mpText = if (maxSize != null) {
                    val mp = (maxSize.width.toLong() * maxSize.height.toLong()) / 1_000_000.0
                    String.format(Locale.US, "%.1f MP", mp)
                } else "MP Máx"

                // Verifica suporte real a 60 FPS e Qualidades de Video (4K / FHD / HD)
                val fpsRanges = cam2Info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                )
                supports60Fps = fpsRanges?.any { it.upper >= 60 } == true

                val videoCaps = Recorder.getVideoCapabilities(cam.cameraInfo)
                val qualities = videoCaps.getSupportedQualities(DynamicRange.SDR)
                    .filter { it == Quality.UHD || it == Quality.FHD || it == Quality.HD }
                if (qualities.isNotEmpty()) {
                    supportedVideoQualities = qualities
                    if (!qualities.contains(selectedVideoQuality)) {
                        selectedVideoQuality = qualities.first()
                    }
                }

                sensorBadgeText = if (isVideoMode) {
                    val qLabel = when (selectedVideoQuality) {
                        Quality.UHD -> "4K UHD"
                        Quality.FHD -> "1080p FHD"
                        else -> "720p HD"
                    }
                    "$qLabel • ${targetVideoFps}fps"
                } else {
                    "$mpText • ${if (fastZeroLagPriority) "ZSL 0.15s" else "MAX HQ"}"
                }

                maxFocusDiopter = (cam2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                ) ?: 10f).coerceAtLeast(2f)

                val zoomState = cam.cameraInfo.zoomState.value
                minZoomRatio = zoomState?.minZoomRatio ?: 1f
                maxZoomRatio = zoomState?.maxZoomRatio ?: 4f
                currentZoomRatio = zoomState?.zoomRatio ?: 1f

                val exposureState = cam.cameraInfo.exposureState
                evMin = exposureState.exposureCompensationRange.lower
                evMax = exposureState.exposureCompensationRange.upper
                currentEvIndex = exposureState.exposureCompensationIndex

                applyHardwareCamera2Options(cam)

                // Toque na tela para focar e fotometrar
                previewView.setOnTouchListener { view, motionEvent ->
                    if (motionEvent.action == MotionEvent.ACTION_UP) {
                        val touchX = motionEvent.x
                        val touchY = motionEvent.y

                        focusTouchPoint = Offset(touchX, touchY)
                        focusIsLocked = false
                        focusSessionId++
                        focusIndicatorText = "Focando ponto..."

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
                onPhotoSaved("Alerta Câmera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // 1. DISPARO DE FOTO COM SINAL VERDE INSTANTANEO (~0.15s) E GRAVACAO ASSINCRONA
    val captureProPhoto: () -> Unit = {
        val captureUseCase = imageCaptureUseCase
        if (captureUseCase != null) {
            val targetFile = StorageEngine.createTargetMediaFile(
                baseRelativePath = baseDirectory,
                albumRelativePath = activeFolder,
                prefix = filePrefix,
                extension = "jpg"
            )

            captureUseCase.flashMode = flashMode
            val outputOptions = ImageCapture.OutputFileOptions.Builder(targetFile).build()
            savingInBackgroundCount++

            captureUseCase.takePicture(
                outputOptions,
                ioExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    // Dispara no exato instante em que o obturador do sensor capta a luz (~0.10s a 0.18s)!
                    override fun onCaptureStarted() {
                        coroutineScope.launch(Dispatchers.Main) {
                            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                            vibrateFeedback()
                            shutterStage = 2 // LUZ VERDE IMEDIATA: ja pode mover o celular!
                            delay(220)
                            shutterStage = 0
                        }
                    }

                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        StorageEngine.indexFileInGallery(context, targetFile)
                        val kb = targetFile.length() / 1024
                        coroutineScope.launch(Dispatchers.Main) {
                            savingInBackgroundCount = (savingInBackgroundCount - 1).coerceAtLeast(0)
                            latestMediaTick++
                            onPhotoSaved("✓ ${targetFile.name} ($kb KB)")
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        coroutineScope.launch(Dispatchers.Main) {
                            savingInBackgroundCount = (savingInBackgroundCount - 1).coerceAtLeast(0)
                            shutterStage = 0
                            onPhotoSaved("ERRO FOTO: ${exception.message}")
                        }
                    }
                }
            )
        }
    }

    // 2. GRAVACAO DE VIDEO PROFISSIONAL (.MP4 COM AUDIO E PAUSA/RETOMADA)
    val toggleVideoRecording: () -> Unit = {
        val currentRec = activeRecording
        if (currentRec != null && isRecordingVideo) {
            currentRec.stop()
            activeRecording = null
            isRecordingVideo = false
            isVideoPaused = false
        } else {
            val vidUseCase = videoCaptureUseCase
            if (vidUseCase != null) {
                val targetVideoFile = StorageEngine.createTargetMediaFile(
                    baseRelativePath = baseDirectory,
                    albumRelativePath = activeFolder,
                    prefix = filePrefix,
                    extension = "mp4"
                )
                val fileOutputOptions = FileOutputOptions.Builder(targetVideoFile).build()
                var pending = vidUseCase.output.prepareRecording(context, fileOutputOptions)

                val hasAudioPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (audioEnabled && hasAudioPerm) {
                    pending = pending.withAudioEnabled()
                }

                recordingSeconds = 0L
                isRecordingVideo = true
                isVideoPaused = false
                shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
                vibrateFeedback()

                activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Finalize -> {
                            isRecordingVideo = false
                            isVideoPaused = false
                            activeRecording = null
                            shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                            vibrateFeedback()

                            if (!event.hasError()) {
                                StorageEngine.indexFileInGallery(context, targetVideoFile)
                                latestMediaTick++
                                val mb = String.format(Locale.US, "%.2f MB", targetVideoFile.length() / (1024.0 * 1024.0))
                                onPhotoSaved("🎬 VÍDEO SALVO: ${targetVideoFile.name} ($mb)")
                            } else {
                                targetVideoFile.delete()
                                onPhotoSaved("ERRO VÍDEO: código ${event.error}")
                            }
                        }
                    }
                }
            }
        }
    }

    // Sincroniza o Botao Fisico Volume - com Foto Instantanea ou Iniciar/Parar Video
    LaunchedEffect(
        isVideoMode,
        isRecordingVideo,
        activeFolder,
        filePrefix,
        baseDirectory,
        imageCaptureUseCase,
        videoCaptureUseCase,
        flashMode
    ) {
        registerVolumeTrigger {
            if (isVideoMode) {
                toggleVideoRecording()
            } else {
                captureProPhoto()
            }
        }
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

    val activeAlbumObj = albumsList.find { it.relativePath == activeFolder }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SolarBlack)
    ) {
        // 1. TOPO: LISTA SUSPENSA (DROPDOWN) DE PASTA ATIVA + BARRA DE HARDWARE CAMERA2
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0F19))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Linha 1: Lista Suspensa de Pasta Ativa + Badge de Sensor + Botao +Subpasta
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.5.dp, SolarAmber),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clickable { folderDropdownExpanded = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.FolderSpecial,
                                    contentDescription = null,
                                    tint = SolarAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "$activeFolder (${activeAlbumObj?.fileCount ?: 0})",
                                    color = SolarWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Abrir lista de pastas",
                                tint = SolarAmber
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = folderDropdownExpanded,
                        onDismissRequest = {
                            folderDropdownExpanded = false
                            folderFilterText = ""
                        },
                        modifier = Modifier
                            .width(300.dp)
                            .heightIn(max = 360.dp)
                            .background(Color(0xFF1E293B))
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            OutlinedTextField(
                                value = folderFilterText,
                                onValueChange = { folderFilterText = it },
                                placeholder = { Text("Filtrar pasta...", color = Color.LightGray, fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        val filtered = albumsList.filter {
                            folderFilterText.isBlank() ||
                                    it.relativePath.contains(folderFilterText.trim(), ignoreCase = true)
                        }
                        filtered.forEach { alb ->
                            val isSelected = alb.relativePath == activeFolder
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (alb.relativePath.contains("/")) "↳ 📂 ${alb.relativePath}" else "📁 ${alb.relativePath}",
                                            color = if (isSelected) SolarAmber else SolarWhite,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "${alb.fileCount} arq",
                                            color = SolarGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectFolder(alb.relativePath)
                                    folderDropdownExpanded = false
                                    folderFilterText = ""
                                }
                            )
                        }
                    }
                }

                Surface(
                    color = SolarAmber,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = sensorBadgeText,
                        color = SolarBlack,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            // Linha 2: Controles Avancados de Hardware (Flash, Lanterna, AWB, HDR, Velocidade/QualidadeVideo, Grade)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isVideoMode) {
                    // Prioridade ZSL 0.15s vs Maxima Qualidade
                    item {
                        ProControlChip(
                            label = if (fastZeroLagPriority) "⚡ 0.15s ZSL" else "💎 MAX MP",
                            active = fastZeroLagPriority,
                            onClick = {
                                fastZeroLagPriority = !fastZeroLagPriority
                                vibrateFeedback()
                            }
                        )
                    }
                    // Flash de Foto
                    item {
                        val flashLabel = when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> "⚡ Flash ON"
                            ImageCapture.FLASH_MODE_AUTO -> "⚡ Flash AUTO"
                            else -> "⚡ Flash OFF"
                        }
                        ProControlChip(
                            label = flashLabel,
                            active = flashMode != ImageCapture.FLASH_MODE_OFF,
                            onClick = {
                                flashMode = when (flashMode) {
                                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                                    else -> ImageCapture.FLASH_MODE_OFF
                                }
                                imageCaptureUseCase?.flashMode = flashMode
                                vibrateFeedback()
                            }
                        )
                    }
                } else {
                    // Seletores Exclusivos de Video Pro: Resolucao (4K / 1080p / 720p), 60 FPS e Microfone
                    item {
                        val qText = when (selectedVideoQuality) {
                            Quality.UHD -> "🎬 4K UHD"
                            Quality.FHD -> "🎬 1080p FHD"
                            else -> "🎬 720p HD"
                        }
                        ProControlChip(
                            label = qText,
                            active = selectedVideoQuality == Quality.UHD,
                            onClick = {
                                if (!isRecordingVideo && supportedVideoQualities.isNotEmpty()) {
                                    val idx = supportedVideoQualities.indexOf(selectedVideoQuality)
                                    selectedVideoQuality = supportedVideoQualities[(idx + 1) % supportedVideoQualities.size]
                                    vibrateFeedback()
                                }
                            }
                        )
                    }
                    if (supports60Fps) {
                        item {
                            ProControlChip(
                                label = "🎞️ ${targetVideoFps} FPS",
                                active = targetVideoFps == 60,
                                onClick = {
                                    if (!isRecordingVideo) {
                                        targetVideoFps = if (targetVideoFps == 30) 60 else 30
                                        vibrateFeedback()
                                    }
                                }
                            )
                        }
                    }
                    item {
                        ProControlChip(
                            label = if (audioEnabled) "🎙️ Áudio ON" else "🔇 Sem Áudio",
                            active = audioEnabled,
                            onClick = {
                                if (!isRecordingVideo) {
                                    audioEnabled = !audioEnabled
                                    vibrateFeedback()
                                }
                            }
                        )
                    }
                }

                // Lanterna Continua (Torch)
                item {
                    ProControlChip(
                        label = if (torchEnabled) "🔦 Luz ON" else "🔦 Luz",
                        active = torchEnabled,
                        onClick = {
                            torchEnabled = !torchEnabled
                            cameraInstance?.cameraControl?.enableTorch(torchEnabled)
                            vibrateFeedback()
                        }
                    )
                }

                // Balanco de Branco (AWB)
                item {
                    ProControlChip(
                        label = awbModes[awbIndex].first,
                        active = awbIndex != 0,
                        onClick = {
                            awbIndex = (awbIndex + 1) % awbModes.size
                            vibrateFeedback()
                        }
                    )
                }

                // Modo HDR
                item {
                    ProControlChip(
                        label = if (hdrSceneEnabled) "✨ HDR ON" else "✨ HDR",
                        active = hdrSceneEnabled,
                        onClick = {
                            hdrSceneEnabled = !hdrSceneEnabled
                            vibrateFeedback()
                        }
                    )
                }

                // Exposicao EV
                item {
                    ProControlChip(
                        label = "☀️ EV (${if (currentEvIndex > 0) "+$currentEvIndex" else "$currentEvIndex"})",
                        active = showEvSlider || currentEvIndex != 0,
                        onClick = { showEvSlider = !showEvSlider }
                    )
                }

                // Grade 3x3
                item {
                    ProControlChip(
                        label = "# Grade",
                        active = showGrid,
                        onClick = { showGrid = !showGrid }
                    )
                }
            }
        }

        // 2. VISOR CENTRAL COM SINAL VERDE INSTANTANEO E SELETORES DE LENTES / MACRO / AF
        val borderColor = when {
            isRecordingVideo -> SolarRed
            shutterStage == 2 -> SolarGreen
            else -> Color.Transparent
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(width = if (borderColor != Color.Transparent) 6.dp else 0.dp, color = borderColor)
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
                    val outerRadius = if (focusIsLocked) 78f else 96f

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

            // Aviso Superior: Sinal Verde Instantaneo (~0.15s) ou Cronometro de Gravacao de Video
            if (isRecordingVideo) {
                val mins = recordingSeconds / 60
                val secs = recordingSeconds % 60
                val timeStr = String.format(Locale.US, "%02d:%02d", mins, secs)
                Surface(
                    color = if (isVideoPaused) SolarAmber else SolarRed,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = if (isVideoPaused) "⏸️ PAUSADO • $timeStr" else "🔴 GRAVANDO • $timeStr",
                        color = if (isVideoPaused) SolarBlack else SolarWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            } else if (shutterStage == 2) {
                Surface(
                    color = SolarGreen,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = "✓ CAPTURADO! PODE MOVER",
                        color = SolarBlack,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // Painel Flutuante na Base do Visor: Barra MF / Barra EV + Modos de Foco + Lentes Fisicas e Zoom
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Barra Deslizante de Foco Manual (Quando MF selecionado)
                if (focusModeIndex == 2) {
                    Surface(
                        color = Color(0xCC000000),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text("∞ Inf", color = SolarWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = manualFocusDiopter,
                                onValueChange = { manualFocusDiopter = it },
                                valueRange = 0f..maxFocusDiopter,
                                colors = SliderDefaults.colors(
                                    thumbColor = SolarGreen,
                                    activeTrackColor = SolarGreen
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                            )
                            Text("🌷 Macro", color = SolarGreen, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                // Barra Deslizante de Exposicao Solar (EV)
                if ((showEvSlider || currentEvIndex != 0) && evMin < evMax) {
                    Surface(
                        color = Color(0xCC000000),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "☀️ EV: ${if (currentEvIndex > 0) "+$currentEvIndex" else "$currentEvIndex"}",
                                color = SolarAmber,
                                fontSize = 11.sp,
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
                                colors = SliderDefaults.colors(thumbColor = SolarAmber, activeTrackColor = SolarAmber),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                            )
                            Text(
                                text = "Zerar",
                                color = SolarWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    currentEvIndex = 0
                                    cameraInstance?.cameraControl?.setExposureCompensationIndex(0)
                                }
                            )
                        }
                    }
                }

                // Linha de Modos de Foco: [AF-C Auto] [🌷 Macro] [📏 MF Manual]
                Surface(
                    color = Color(0xB3000000),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        val focusLabels = listOf("🎯 AF Auto", "🌷 Macro", "📏 MF Manual")
                        focusLabels.forEachIndexed { idx, text ->
                            val selected = focusModeIndex == idx
                            Surface(
                                color = if (selected) SolarGreen else Color.Transparent,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.clickable {
                                    focusModeIndex = idx
                                    vibrateFeedback()
                                }
                            ) {
                                Text(
                                    text = text,
                                    color = if (selected) SolarBlack else SolarWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Linha de Lentes Fisicas (Ultra-Wide, Principal, Macro, Frontal) + Niveis de Zoom (0.6x, 1x, 2x, 4x)
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        // Lentes Fisicas Reais do Dispositivo
                        items(availableLenses) { lens ->
                            val isCurrentLens = selectedCameraId == lens.cameraId
                            Surface(
                                color = if (isCurrentLens) SolarAmber else Color(0xFF1E293B),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.clickable {
                                    if (!isRecordingVideo) {
                                        selectedCameraId = lens.cameraId
                                        currentLensFacing = lens.lensFacing
                                        if (lens.label.contains("Macro", ignoreCase = true)) {
                                            focusModeIndex = 1
                                        }
                                        vibrateFeedback()
                                    }
                                }
                            ) {
                                Text(
                                    text = lens.label,
                                    color = if (isCurrentLens) SolarBlack else SolarWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        // Passos de Zoom na lente atual
                        val zoomSteps = buildList {
                            if (minZoomRatio < 0.95f) add(minZoomRatio)
                            add(1.0f)
                            if (maxZoomRatio >= 2.0f) add(2.0f)
                            if (maxZoomRatio >= 4.0f) add(4.0f.coerceAtMost(maxZoomRatio))
                        }
                        items(zoomSteps) { targetZoom ->
                            val active = kotlin.math.abs(currentZoomRatio - targetZoom) < 0.15f
                            Surface(
                                color = if (active) SolarWhite else Color.Transparent,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clickable {
                                        currentZoomRatio = targetZoom
                                        cameraInstance?.cameraControl?.setZoomRatio(targetZoom)
                                        vibrateFeedback()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = String.format(Locale.US, "%.1fx", targetZoom).replace(".0x", "x"),
                                        color = if (active) SolarBlack else SolarAmber,
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

        // 3. DECK INFERIOR: SELETOR [FOTO | VIDEO] + [MINIATURA PASTA ATIVA] • [OBTURADOR] • [+ SUBPASTA]
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0F19))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Chave Seletora de Modo: FOTO (.JPG) vs VIDEO (.MP4) + Status de Gravacao em Background
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (!isVideoMode) SolarAmber else Color(0xFF1E293B),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable {
                            if (!isRecordingVideo) {
                                isVideoMode = false
                                vibrateFeedback()
                            }
                        }
                    ) {
                        Text(
                            text = "📷 FOTO",
                            color = if (!isVideoMode) SolarBlack else SolarWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }

                    Surface(
                        color = if (isVideoMode) SolarRed else Color(0xFF1E293B),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable {
                            if (!isRecordingVideo) {
                                isVideoMode = true
                                vibrateFeedback()
                            }
                        }
                    ) {
                        Text(
                            text = "🔴 VÍDEO",
                            color = SolarWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }

                Text(
                    text = if (savingInBackgroundCount > 0) {
                        "⚡ Salvando ($savingInBackgroundCount)... • $focusIndicatorText"
                    } else {
                        "$focusIndicatorText"
                    },
                    color = SolarGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // ESQUERDA: Miniatura da Ultima Foto/Video (Abre a Galeria DIRETO NA PASTA ATIVA!)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        if (!isRecordingVideo) {
                            vibrateFeedback()
                            onOpenGallery(latestMediaItem?.uniquePath, activeFolder)
                        }
                    }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1E293B))
                            .border(2.dp, SolarAmber, RoundedCornerShape(14.dp))
                    ) {
                        if (latestThumbBitmap != null) {
                            Image(
                                bitmap = latestThumbBitmap!!,
                                contentDescription = "Abrir pasta ativa na Galeria",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (latestMediaItem?.isVideo == true) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircleFilled,
                                    contentDescription = null,
                                    tint = SolarAmber,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Abrir Galeria",
                                tint = SolarAmber,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = activeFolder.substringAfterLast("/"),
                        color = SolarWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 72.dp)
                    )
                }

                // CENTRO: Obturador de Foto Instantanea (0.15s) ou Gravador de Video com Pausa
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isVideoMode && isRecordingVideo) {
                        // Botao de Pausar / Retomar Video durante a filmagem
                        IconButton(
                            onClick = {
                                val rec = activeRecording
                                if (rec != null) {
                                    if (isVideoPaused) {
                                        rec.resume()
                                        isVideoPaused = false
                                    } else {
                                        rec.pause()
                                        isVideoPaused = true
                                    }
                                    vibrateFeedback()
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF1E293B), CircleShape)
                                .border(2.dp, SolarAmber, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isVideoPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Pausar/Retomar",
                                tint = SolarAmber
                            )
                        }
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .border(
                                4.dp,
                                if (isVideoMode) SolarRed else if (shutterStage == 2) SolarGreen else SolarAmber,
                                CircleShape
                            )
                            .clickable {
                                if (isVideoMode) toggleVideoRecording() else captureProPhoto()
                            }
                            .padding(6.dp)
                    ) {
                        Surface(
                            color = when {
                                isVideoMode && isRecordingVideo -> SolarRed
                                isVideoMode -> SolarRed.copy(alpha = 0.85f)
                                shutterStage == 2 -> SolarGreen
                                else -> SolarAmber
                            },
                            shape = if (isVideoMode && isRecordingVideo) RoundedCornerShape(12.dp) else CircleShape,
                            modifier = if (isVideoMode && isRecordingVideo) Modifier.size(34.dp) else Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (!isVideoMode) {
                                    Icon(
                                        imageVector = if (shutterStage == 2) Icons.Default.Check else Icons.Default.CameraAlt,
                                        contentDescription = "Disparar",
                                        tint = SolarBlack,
                                        modifier = Modifier.size(30.dp)
                                    )
                                } else if (!isRecordingVideo) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = "Gravar Vídeo",
                                        tint = SolarWhite,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
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
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = "Criar Subpasta",
                                    tint = SolarGreen,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "+ Subpasta",
                            color = SolarWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (useNativeCameraFallback) {
                        IconButton(
                            onClick = onLaunchNativeCamera,
                            modifier = Modifier
                                .size(40.dp)
                                .border(1.dp, SolarAmber, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCameraBack,
                                contentDescription = "Câmera Nativa",
                                tint = SolarAmber,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProControlChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (active) SolarAmber else Color(0xFF1E293B),
        border = BorderStroke(1.dp, if (active) SolarWhite else Color(0xFF475569)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .height(30.dp)
            .clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                color = if (active) SolarBlack else SolarWhite,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
