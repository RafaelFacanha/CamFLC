package com.camflc.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

// Paleta Base de Alta Visibilidade
val SolarWhite = Color(0xFFFFFFFF)
val SolarSurface = Color(0xFFF1F5F9)
val SolarBlack = Color(0xFF000000)
val SolarAmber = Color(0xFFFFD600)
val SolarGreen = Color(0xFF00C853)
val SolarRed = Color(0xFFD50000)

class MainActivity : ComponentActivity() {

    private var onVolumeDownTrigger: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("camflc_prefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                CamFLCApp(
                    prefs = prefs,
                    registerVolumeTrigger = { callback -> onVolumeDownTrigger = callback },
                    vibrateFeedback = { vibrateShort() }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            onVolumeDownTrigger?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun vibrateShort() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

@Composable
fun CamFLCApp(
    prefs: android.content.SharedPreferences,
    registerVolumeTrigger: ((() -> Unit)?) -> Unit,
    vibrateFeedback: () -> Unit
) {
    val context = LocalContext.current

    // Estado do Tema Dual: false = Modo Sol Claro (Outdoor) | true = Modo Escuro OLED (Indoor/Noturno)
    var isDarkMode by remember {
        mutableStateOf(prefs.getBoolean("is_dark_mode", false))
    }

    // Tela separada de Configuracoes Casuais
    var showCasualSettings by remember { mutableStateOf(false) }

    // Configuracoes Operacionais
    var baseDirectory by remember {
        mutableStateOf(prefs.getString("base_dir", "DCIM/CamFLC") ?: "DCIM/CamFLC")
    }
    var filePrefix by remember {
        mutableStateOf(prefs.getString("file_prefix", "OBRA_01") ?: "OBRA_01")
    }
    var recentPrefixes by remember {
        val saved = prefs.getString("recent_prefixes", "OBRA_01,INSPECAO,LAUDO") ?: "OBRA_01"
        mutableStateOf(saved.split(",").filter { it.isNotBlank() })
    }
    var activeFolder by remember {
        mutableStateOf(prefs.getString("active_folder", "Geral") ?: "Geral")
    }
    var useNativeCameraFallback by remember {
        mutableStateOf(prefs.getBoolean("use_native_fallback", false))
    }

    // Configuracoes LAN
    var deviceName by remember {
        val defaultModel = Build.MODEL?.replace("|", "_") ?: "Android_CamFLC"
        mutableStateOf(prefs.getString("lan_device_name", defaultModel) ?: defaultModel)
    }
    var allowRemoteDelete by remember {
        mutableStateOf(prefs.getBoolean("lan_allow_delete", true))
    }
    var pairedPeers by remember {
        val raw = prefs.getString("lan_paired_peers", "").orEmpty()
        val list = raw.split(";").mapNotNull { entry ->
            val p = entry.split("|")
            if (p.size >= 2 && p[1].isNotBlank()) PeerDevice(name = p[0], ip = p[1], isPaired = true) else null
        }
        mutableStateOf(list)
    }
    var discoveredPeers by remember { mutableStateOf<List<PeerDevice>>(emptyList()) }

    var albumsList by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }
    var storageRefreshTick by remember { mutableIntStateOf(0) }
    var lastCaptureLog by remember { mutableStateOf("Pronto para captura rápida (0.15s).") }

    // 4 Abas Principais: 0 = Inicio | 1 = Camera Pro | 2 = Galeria Local | 3 = Rede LAN
    var currentTab by remember { mutableIntStateOf(0) }
    var initialGalleryFilePath by remember { mutableStateOf<String?>(null) }
    var initialGalleryFolder by remember { mutableStateOf<String?>(null) }

    // Funcao auxiliar para salvar prefixo nos recentes
    val savePrefixToHistory: (String) -> Unit = { prefix ->
        val clean = StorageEngine.sanitizePrefix(prefix)
        if (clean.isNotBlank()) {
            val updated = (listOf(clean) + recentPrefixes.filterNot { it.equals(clean, ignoreCase = true) }).take(6)
            recentPrefixes = updated
            prefs.edit().putString("recent_prefixes", updated.joinToString(",")).apply()
        }
    }

    val lanEngine = remember {
        LanEngine(
            context = context,
            getBaseDirectory = { baseDirectory },
            getDeviceName = { deviceName },
            isRemoteDeleteAllowed = { allowRemoteDelete },
            onStorageChanged = { storageRefreshTick++ }
        )
    }

    DisposableEffect(Unit) {
        lanEngine.startServer { discovered ->
            val existing = discoveredPeers.find { it.ip == discovered.ip }
            if (existing == null || existing.name != discovered.name) {
                discoveredPeers = (discoveredPeers.filterNot { it.ip == discovered.ip } + discovered)
                    .sortedBy { it.ip }
            }
        }
        onDispose {
            lanEngine.stopServer()
        }
    }

    var hasAllFilesAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else true
        )
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        storageRefreshTick++
    }

    var pendingNativeCameraFile by remember { mutableStateOf<File?>(null) }
    val nativeCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingNativeCameraFile
        if (success && file != null && file.exists()) {
            StorageEngine.indexFileInGallery(context, file)
            vibrateFeedback()
            savePrefixToHistory(filePrefix)
            lastCaptureLog = "✓ ${file.name} (${file.length() / 1024} KB)"
            storageRefreshTick++
        } else {
            file?.delete()
        }
    }

    val launchNativeCamera: () -> Unit = {
        try {
            savePrefixToHistory(filePrefix)
            val targetFile = StorageEngine.createTargetMediaFile(
                baseRelativePath = baseDirectory,
                albumRelativePath = activeFolder,
                prefix = filePrefix,
                extension = "jpg"
            )
            pendingNativeCameraFile = targetFile
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
            nativeCameraLauncher.launch(uri)
        } catch (e: Exception) {
            lastCaptureLog = "Erro Câmera Nativa: ${e.message}"
        }
    }

    LaunchedEffect(baseDirectory, activeFolder, storageRefreshTick) {
        val defaultFolders = listOf("Geral", "Inspecao", "Laudo_A", "Equipamentos")
        StorageEngine.ensureDefaultAlbumsIfEmpty(baseDirectory, defaultFolders)
        val loaded = StorageEngine.listAllAlbumsRecursively(baseDirectory)
        albumsList = loaded
        if (loaded.none { it.relativePath == activeFolder } && loaded.isNotEmpty()) {
            activeFolder = loaded.first().relativePath
            prefs.edit().putString("active_folder", activeFolder).apply()
        }
    }

    LaunchedEffect(currentTab, showCasualSettings) {
        if (currentTab != 1 || showCasualSettings) {
            registerVolumeTrigger {
                vibrateFeedback()
                showCasualSettings = false
                currentTab = 1
            }
        }
    }

    val topBarBg = if (isDarkMode) Color(0xFF0B0F19) else SolarBlack

    Scaffold(
        containerColor = if (isDarkMode) Color(0xFF0B0F19) else SolarWhite,
        topBar = {
            if (currentTab != 1 || showCasualSettings) {
                Surface(
                    color = topBarBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (showCasualSettings) {
                                IconButton(
                                    onClick = { showCasualSettings = false },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = SolarAmber)
                                }
                            } else {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = SolarAmber)
                            }
                            Text(
                                text = if (showCasualSettings) "CONFIGURAÇÕES CASUAIS" else "CamFLC",
                                color = SolarAmber,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    isDarkMode = !isDarkMode
                                    prefs.edit().putBoolean("is_dark_mode", isDarkMode).apply()
                                    vibrateFeedback()
                                },
                                border = BorderStroke(1.dp, SolarAmber),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(
                                    imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = "Alternar Tema",
                                    tint = SolarAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isDarkMode) "MODO SOL" else "MODO ESCURO",
                                    color = SolarAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            IconButton(
                                onClick = {
                                    showCasualSettings = !showCasualSettings
                                    vibrateFeedback()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (showCasualSettings) Icons.Default.Close else Icons.Default.Settings,
                                    contentDescription = "Configurações",
                                    tint = if (showCasualSettings) SolarGreen else SolarWhite
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!showCasualSettings) {
                NavigationBar(
                    containerColor = SolarBlack,
                    contentColor = SolarWhite
                ) {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Default.FolderSpecial, contentDescription = null) },
                        label = { Text("INÍCIO", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SolarBlack,
                            selectedTextColor = SolarAmber,
                            indicatorColor = SolarAmber,
                            unselectedIconColor = SolarWhite,
                            unselectedTextColor = SolarWhite
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = {
                            savePrefixToHistory(filePrefix)
                            currentTab = 1
                        },
                        icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                        label = { Text("CÂMERA", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SolarBlack,
                            selectedTextColor = SolarAmber,
                            indicatorColor = SolarAmber,
                            unselectedIconColor = SolarWhite,
                            unselectedTextColor = SolarWhite
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = {
                            initialGalleryFolder = activeFolder
                            currentTab = 2
                        },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        label = { Text("GALERIA", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SolarBlack,
                            selectedTextColor = SolarAmber,
                            indicatorColor = SolarAmber,
                            unselectedIconColor = SolarWhite,
                            unselectedTextColor = SolarWhite
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 3,
                        onClick = { currentTab = 3 },
                        icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                        label = { Text("REDE LAN", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SolarBlack,
                            selectedTextColor = SolarAmber,
                            indicatorColor = SolarAmber,
                            unselectedIconColor = SolarWhite,
                            unselectedTextColor = SolarWhite
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showCasualSettings) {
                CasualSettingsScreen(
                    isDarkMode = isDarkMode,
                    onToggleDarkMode = {
                        isDarkMode = it
                        prefs.edit().putBoolean("is_dark_mode", it).apply()
                    },
                    baseDirectory = baseDirectory,
                    onApplyBaseDir = { confirmedDir ->
                        val cleaned = StorageEngine.sanitizeRelativePath(confirmedDir).ifEmpty { "DCIM/CamFLC" }
                        baseDirectory = cleaned
                        prefs.edit().putString("base_dir", cleaned).apply()
                        vibrateFeedback()
                        lastCaptureLog = "Diretório base aplicado: $cleaned"
                        storageRefreshTick++
                    },
                    activeFolder = activeFolder,
                    albumsCount = albumsList.size,
                    onRenameActiveFolder = { newName ->
                        val updatedRel = StorageEngine.renameAlbum(baseDirectory, activeFolder, newName)
                        if (updatedRel != null) {
                            activeFolder = updatedRel
                            prefs.edit().putString("active_folder", updatedRel).apply()
                            vibrateFeedback()
                            lastCaptureLog = "Pasta renomeada para: $updatedRel"
                            storageRefreshTick++
                        }
                    },
                    onDeleteActiveFolder = {
                        if (albumsList.size > 1) {
                            if (StorageEngine.deleteAlbum(baseDirectory, activeFolder)) {
                                vibrateFeedback()
                                lastCaptureLog = "Pasta excluída: $activeFolder"
                                storageRefreshTick++
                            }
                        }
                    },
                    onCleanGhostFolders = {
                        val removed = StorageEngine.cleanEmptyGhostFolders(baseDirectory)
                        vibrateFeedback()
                        lastCaptureLog = "Faxina concluída: $removed pastas vazias removidas!"
                        storageRefreshTick++
                    },
                    useNativeCameraFallback = useNativeCameraFallback,
                    onToggleNativeCamera = {
                        useNativeCameraFallback = it
                        prefs.edit().putBoolean("use_native_fallback", it).apply()
                    },
                    deviceName = deviceName,
                    onDeviceNameChange = { newName ->
                        val clean = newName.replace("|", "_").replace(";", "_")
                        deviceName = clean
                        prefs.edit().putString("lan_device_name", clean).apply()
                    },
                    allowRemoteDelete = allowRemoteDelete,
                    onToggleRemoteDelete = {
                        allowRemoteDelete = it
                        prefs.edit().putBoolean("lan_allow_delete", it).apply()
                    },
                    hasAllFilesAccess = hasAllFilesAccess,
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    },
                    onRefreshPermissions = {
                        hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Environment.isExternalStorageManager()
                        } else true
                        hasCameraPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        storageRefreshTick++
                    },
                    statusLog = lastCaptureLog,
                    onCloseSettings = { showCasualSettings = false }
                )
            } else {
                when (currentTab) {
                    0 -> QuickOperationHomeScreen(
                        isDarkMode = isDarkMode,
                        filePrefix = filePrefix,
                        recentPrefixes = recentPrefixes,
                        onPrefixChange = {
                            filePrefix = it
                            prefs.edit().putString("file_prefix", it).apply()
                        },
                        onSelectRecentPrefix = { chosen ->
                            filePrefix = chosen
                            prefs.edit().putString("file_prefix", chosen).apply()
                            vibrateFeedback()
                        },
                        albumsList = albumsList,
                        activeFolder = activeFolder,
                        hasAllFilesAccess = hasAllFilesAccess,
                        hasCameraPermission = hasCameraPermission,
                        onRequestImmediatePermissions = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        },
                        onSelectFolder = { folderRelPath ->
                            activeFolder = folderRelPath
                            prefs.edit().putString("active_folder", folderRelPath).apply()
                            vibrateFeedback()
                            storageRefreshTick++
                        },
                        onAddFolder = { inputName, asSubfolder ->
                            val targetRel = if (asSubfolder && activeFolder.isNotBlank()) {
                                "$activeFolder/${inputName.trim()}"
                            } else {
                                inputName.trim()
                            }
                            val createdRel = StorageEngine.createAlbum(baseDirectory, targetRel)
                            if (createdRel != null) {
                                activeFolder = createdRel
                                prefs.edit().putString("active_folder", createdRel).apply()
                                vibrateFeedback()
                                storageRefreshTick++
                            }
                        },
                        useNativeCameraFallback = useNativeCameraFallback,
                        onOpenProCamera = {
                            savePrefixToHistory(filePrefix)
                            currentTab = 1
                        },
                        onLaunchNativeCamera = launchNativeCamera
                    )

                    1 -> ProCameraScreen(
                        baseDirectory = baseDirectory,
                        activeFolder = activeFolder,
                        albumsList = albumsList,
                        filePrefix = filePrefix,
                        useNativeCameraFallback = useNativeCameraFallback,
                        lastCaptureLog = lastCaptureLog,
                        registerVolumeTrigger = registerVolumeTrigger,
                        onSelectFolder = { folderRel ->
                            activeFolder = folderRel
                            prefs.edit().putString("active_folder", folderRel).apply()
                            vibrateFeedback()
                            storageRefreshTick++
                        },
                        onCreateQuickFolder = { newName, asSubfolder ->
                            val targetRel = if (asSubfolder && activeFolder.isNotBlank()) {
                                "$activeFolder/${newName.trim()}"
                            } else {
                                newName.trim()
                            }
                            val createdRel = StorageEngine.createAlbum(baseDirectory, targetRel)
                            if (createdRel != null) {
                                activeFolder = createdRel
                                prefs.edit().putString("active_folder", createdRel).apply()
                                vibrateFeedback()
                                storageRefreshTick++
                            }
                        },
                        onPhotoSaved = { logMsg ->
                            savePrefixToHistory(filePrefix)
                            lastCaptureLog = logMsg
                            storageRefreshTick++
                        },
                        onOpenGallery = { focusedPath, folderToOpen ->
                            initialGalleryFilePath = focusedPath
                            initialGalleryFolder = folderToOpen
                            currentTab = 2
                        },
                        onLaunchNativeCamera = launchNativeCamera,
                        vibrateFeedback = vibrateFeedback
                    )

                    2 -> GalleryScreen(
                        baseDirectory = baseDirectory,
                        albumsList = albumsList,
                        activeFolder = activeFolder,
                        initialFocusedFilePath = initialGalleryFilePath,
                        initialFocusedFolder = initialGalleryFolder,
                        onClearInitialFocus = {
                            initialGalleryFilePath = null
                            initialGalleryFolder = null
                        },
                        isDarkMode = isDarkMode,
                        onSelectActiveFolder = { chosenFolder ->
                            activeFolder = chosenFolder
                            prefs.edit().putString("active_folder", chosenFolder).apply()
                            storageRefreshTick++
                        },
                        onStorageChanged = { storageRefreshTick++ },
                        vibrateFeedback = vibrateFeedback
                    )

                    3 -> LanScreen(
                        lanEngine = lanEngine,
                        isDarkMode = isDarkMode,
                        pairedPeers = pairedPeers,
                        discoveredPeers = discoveredPeers,
                        onSavePairedPeer = { peerName, peerIp ->
                            val cleanName = peerName.replace("|", "_").replace(";", "_")
                            val cleanIp = peerIp.trim()
                            val updated = (pairedPeers.filterNot { it.ip == cleanIp } +
                                    PeerDevice(name = cleanName, ip = cleanIp, isPaired = true))
                                .sortedBy { it.ip }
                            pairedPeers = updated
                            val serialized = updated.joinToString(";") { "${it.name}|${it.ip}" }
                            prefs.edit().putString("lan_paired_peers", serialized).apply()
                        },
                        onRemovePairedPeer = { targetIp ->
                            val updated = pairedPeers.filterNot { it.ip == targetIp }
                            pairedPeers = updated
                            val serialized = updated.joinToString(";") { "${it.name}|${it.ip}" }
                            prefs.edit().putString("lan_paired_peers", serialized).apply()
                        },
                        onTriggerRadarScan = { lanEngine.sendDiscoveryBroadcast() },
                        vibrateFeedback = vibrateFeedback
                    )
                }
            }
        }
    }
}

@Composable
fun QuickOperationHomeScreen(
    isDarkMode: Boolean,
    filePrefix: String,
    recentPrefixes: List<String>,
    onPrefixChange: (String) -> Unit,
    onSelectRecentPrefix: (String) -> Unit,
    albumsList: List<AlbumInfo>,
    activeFolder: String,
    hasAllFilesAccess: Boolean,
    hasCameraPermission: Boolean,
    onRequestImmediatePermissions: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onAddFolder: (String, Boolean) -> Unit,
    useNativeCameraFallback: Boolean,
    onOpenProCamera: () -> Unit,
    onLaunchNativeCamera: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF0B0F19) else SolarWhite
    val cardColor = if (isDarkMode) Color(0xFF1E293B) else SolarSurface
    val textColor = if (isDarkMode) SolarWhite else SolarBlack
    val subTextColor = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF334155)
    val borderColor = if (isDarkMode) Color(0xFF475569) else SolarBlack

    var dropdownExpanded by remember { mutableStateOf(false) }
    var folderSearchQuery by remember { mutableStateOf("") }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var createAsSubfolder by remember { mutableStateOf(true) }
    var newFolderNameInput by remember { mutableStateOf("") }

    val cleanPrefix = StorageEngine.sanitizePrefix(filePrefix)
    val activeAlbumInfo = albumsList.find { it.relativePath == activeFolder }

    // Filtra as pastas dentro da Lista Suspensa (Dropdown)
    val filteredAlbums = remember(albumsList, folderSearchQuery) {
        val q = folderSearchQuery.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) {
            albumsList
        } else {
            albumsList.filter { it.relativePath.lowercase(Locale.getDefault()).contains(q) }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            containerColor = cardColor,
            title = {
                Text(
                    text = if (createAsSubfolder) "NOVA SUBPASTA EM '$activeFolder'" else "NOVA PASTA NA RAIZ",
                    color = textColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            },
            text = {
                OutlinedTextField(
                    value = newFolderNameInput,
                    onValueChange = { newFolderNameInput = it },
                    label = { Text("Nome da pasta (ex: JF22JS35)", fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderNameInput.isNotBlank()) {
                            onAddFolder(newFolderNameInput, createAsSubfolder)
                            newFolderNameInput = ""
                            showCreateFolderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack)
                ) {
                    Text("CRIAR E ATIVAR", fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("CANCELAR", color = textColor, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!hasAllFilesAccess || !hasCameraPermission) {
            Surface(
                color = SolarAmber,
                border = BorderStroke(2.dp, SolarBlack),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRequestImmediatePermissions() }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "⚠️ Toque aqui para liberar Permissões de Pastas, Câmera e Áudio",
                        color = SolarBlack,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SolarBlack)
                }
            }
        }

        // RESUMO COMPACTO DO DESTINO ATIVO
        Surface(
            color = Color(0xFF0B0F19),
            border = BorderStroke(1.5.dp, SolarAmber),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "📂 PASTA ATIVA: $activeFolder",
                        color = SolarAmber,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${activeAlbumInfo?.fileCount ?: 0} arq",
                        color = SolarGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = "🏷️ Próximo arquivo: ${cleanPrefix}_AAAAMMDD_HHMMSS (.jpg / .mp4)",
                    color = SolarWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 1. BLOCO DE USO FREQUENTE: PREFIXO DO ARQUIVO + CHIPS RECENTES
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.5.dp, borderColor),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "1. PREFIXO DO ARQUIVO",
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                OutlinedTextField(
                    value = filePrefix,
                    onValueChange = onPrefixChange,
                    placeholder = { Text("Ex: JF22JS35, LOTE_01...", color = subTextColor, fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(Icons.Default.Label, contentDescription = null, tint = SolarAmber) },
                    trailingIcon = {
                        if (filePrefix.isNotEmpty()) {
                            IconButton(onClick = { onPrefixChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar", tint = textColor)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (recentPrefixes.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Recentes:",
                            color = subTextColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(recentPrefixes) { recent ->
                                val isCurrent = recent.equals(filePrefix.trim(), ignoreCase = true)
                                Surface(
                                    color = if (isCurrent) SolarAmber else bgColor,
                                    border = BorderStroke(1.dp, if (isCurrent) SolarBlack else borderColor),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.clickable { onSelectRecentPrefix(recent) }
                                ) {
                                    Text(
                                        text = recent,
                                        color = if (isCurrent) SolarBlack else textColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. BLOCO DE USO FREQUENTE: SELECAO DE PASTA ATIVA EM LISTA SUSPENSA (DROPDOWN)
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.5.dp, borderColor),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "2. SELECIONAR PASTA / SUBPASTA ATIVA (${albumsList.size})",
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                // Caixa da Lista Suspensa (Dropdown)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        color = bgColor,
                        border = BorderStroke(2.dp, SolarAmber),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable { dropdownExpanded = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderSpecial,
                                    contentDescription = null,
                                    tint = SolarAmber,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = activeFolder,
                                        color = textColor,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${activeAlbumInfo?.fileCount ?: 0} arquivos • ${activeAlbumInfo?.formattedSize() ?: "0 B"}",
                                        color = subTextColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Abrir lista de pastas",
                                tint = SolarAmber,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = {
                            dropdownExpanded = false
                            folderSearchQuery = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.90f)
                            .heightIn(max = 380.dp)
                            .background(cardColor)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            OutlinedTextField(
                                value = folderSearchQuery,
                                onValueChange = { folderSearchQuery = it },
                                placeholder = { Text("Pesquisar pasta na lista...", color = subTextColor, fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = textColor) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        filteredAlbums.forEach { album ->
                            val isSelected = album.relativePath == activeFolder
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (album.relativePath.contains("/")) "↳ 📂 ${album.relativePath}" else "📁 ${album.relativePath}",
                                            color = if (isSelected) SolarAmber else textColor,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "${album.fileCount} arq • ${album.formattedSize()}",
                                            color = if (isSelected) SolarGreen else subTextColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectFolder(album.relativePath)
                                    dropdownExpanded = false
                                    folderSearchQuery = ""
                                }
                            )
                        }
                    }
                }

                // Botoes Rapidos de Hierarquia: [+ Subpasta], [+ Raiz] e [Subir para Pasta Pai]
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            createAsSubfolder = true
                            showCreateFolderDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("+ SUBPASTA", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    OutlinedButton(
                        onClick = {
                            createAsSubfolder = false
                            showCreateFolderDialog = true
                        },
                        border = BorderStroke(1.5.dp, borderColor),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = SolarAmber, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("+ PASTA RAIZ", color = textColor, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                if (activeFolder.contains("/")) {
                    val parentFolder = activeFolder.substringBeforeLast("/")
                    OutlinedButton(
                        onClick = { onSelectFolder(parentFolder) },
                        border = BorderStroke(1.5.dp, borderColor),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = SolarAmber, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Subir para pasta pai: $parentFolder",
                            color = textColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // BOTAO PRINCIPAL DE ACAO (ABRIR CAMERA PRO)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onOpenProCamera,
                colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                border = BorderStroke(2.dp, SolarBlack),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("ABRIR CÂMERA PRO (VOL -)", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }

            if (useNativeCameraFallback) {
                OutlinedButton(
                    onClick = onLaunchNativeCamera,
                    border = BorderStroke(2.dp, borderColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(64.dp)
                ) {
                    Icon(Icons.Default.PhotoCameraBack, contentDescription = "Câmera Nativa", tint = textColor)
                }
            }
        }
    }
}

@Composable
fun CasualSettingsScreen(
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean) -> Unit,
    baseDirectory: String,
    onApplyBaseDir: (String) -> Unit,
    activeFolder: String,
    albumsCount: Int,
    onRenameActiveFolder: (String) -> Unit,
    onDeleteActiveFolder: () -> Unit,
    onCleanGhostFolders: () -> Unit,
    useNativeCameraFallback: Boolean,
    onToggleNativeCamera: (Boolean) -> Unit,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    allowRemoteDelete: Boolean,
    onToggleRemoteDelete: (Boolean) -> Unit,
    hasAllFilesAccess: Boolean,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onRefreshPermissions: () -> Unit,
    statusLog: String,
    onCloseSettings: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF0B0F19) else SolarWhite
    val cardColor = if (isDarkMode) Color(0xFF1E293B) else SolarSurface
    val textColor = if (isDarkMode) SolarWhite else SolarBlack
    val borderColor = if (isDarkMode) Color(0xFF475569) else SolarBlack

    var draftBaseDir by remember(baseDirectory) { mutableStateOf(baseDirectory) }
    var renameFolderText by remember(activeFolder) { mutableStateOf(activeFolder.substringAfterLast("/")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.5.dp, borderColor),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "1. APARÊNCIA E VISIBILIDADE",
                    color = textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Modo Escuro OLED (Ambientes internos/noite)",
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = isDarkMode, onCheckedChange = onToggleDarkMode)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Exibir botão auxiliar da Câmera Nativa do Sistema",
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = useNativeCameraFallback, onCheckedChange = onToggleNativeCamera)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.5.dp, borderColor),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "2. DIRETÓRIO BASE E MANUTENÇÃO DE PASTAS",
                    color = textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("DCIM/CamFLC", "Pictures/CamFLC", "Documents/CamFLC").forEach { preset ->
                        val selected = baseDirectory == preset
                        Surface(
                            color = if (selected) SolarAmber else bgColor,
                            border = BorderStroke(1.5.dp, if (selected) SolarBlack else borderColor),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    draftBaseDir = preset
                                    onApplyBaseDir(preset)
                                }
                        ) {
                            Text(
                                text = preset.substringBefore("/"),
                                color = if (selected) SolarBlack else textColor,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draftBaseDir,
                        onValueChange = { draftBaseDir = it },
                        label = { Text("Caminho Base Personalizado", fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onApplyBaseDir(draftBaseDir) },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Text("APLICAR", fontWeight = FontWeight.ExtraBold)
                    }
                }

                Divider(color = borderColor, thickness = 1.dp)

                Button(
                    onClick = onCleanGhostFolders,
                    colors = ButtonDefaults.buttonColors(containerColor = SolarGreen, contentColor = SolarBlack),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("LIMPAR TODAS AS PASTAS VAZIAS (0 ARQ)", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = renameFolderText,
                        onValueChange = { renameFolderText = it },
                        label = { Text("Renomear pasta ativa ('$activeFolder')", fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onRenameActiveFolder(renameFolderText) },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Text("RENOMEAR", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                }

                if (albumsCount > 1) {
                    OutlinedButton(
                        onClick = onDeleteActiveFolder,
                        border = BorderStroke(1.5.dp, SolarRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, tint = SolarRed)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("EXCLUIR PASTA ATIVA '$activeFolder'", color = SolarRed, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.5.dp, borderColor),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "3. REDE LAN E PERMISSÕES ANDROID",
                    color = textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                OutlinedTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    label = { Text("Nome deste Dispositivo na Rede LAN", fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Permitir que dispositivos na rede movam/excluam fotos daqui",
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = allowRemoteDelete, onCheckedChange = onToggleRemoteDelete)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRequestPermissions,
                        colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("PERMISSÕES ANDROID", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onRefreshPermissions,
                        border = BorderStroke(1.5.dp, borderColor)
                    ) {
                        Text("VERIFICAR", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                }

                Surface(
                    color = SolarBlack,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusLog,
                        color = SolarGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }

        Button(
            onClick = onCloseSettings,
            colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("CONCLUIR E VOLTAR À OPERAÇÃO", fontWeight = FontWeight.ExtraBold)
        }
    }
}
