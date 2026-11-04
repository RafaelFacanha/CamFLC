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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

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
        vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

@Composable
fun CamFLCApp(
    prefs: android.content.SharedPreferences,
    registerVolumeTrigger: ((() -> Unit)?) -> Unit,
    vibrateFeedback: () -> Unit
) {
    val context = LocalContext.current

    var baseDirectory by remember {
        mutableStateOf(prefs.getString("base_dir", "DCIM/CamFLC") ?: "DCIM/CamFLC")
    }
    var filePrefix by remember {
        mutableStateOf(prefs.getString("file_prefix", "OBRA_01") ?: "OBRA_01")
    }
    var activeFolder by remember {
        mutableStateOf(prefs.getString("active_folder", "Geral") ?: "Geral")
    }
    var useNativeCameraFallback by remember {
        mutableStateOf(prefs.getBoolean("use_native_fallback", true))
    }

    var albumsList by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }
    var recentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var storageRefreshTick by remember { mutableIntStateOf(0) }
    var lastCaptureLog by remember { mutableStateOf("Pronto para captura rápida sem atraso.") }
    var currentTab by remember { mutableIntStateOf(0) }

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
            lastCaptureLog = "FOTO NATIVA SALVA: ${file.name} (${file.length() / 1024} KB)"
            storageRefreshTick++
        } else {
            file?.delete()
            lastCaptureLog = "Captura na câmera nativa cancelada."
        }
    }

    val launchNativeCamera: () -> Unit = {
        try {
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
            lastCaptureLog = "ERRO AO ABRIR CÂMERA NATIVA: ${e.message}"
        }
    }

    // So recarrega pastas quando confirmado (nunca durante digitacao parcial!)
    LaunchedEffect(baseDirectory, activeFolder, storageRefreshTick) {
        val defaultFolders = listOf("Geral", "Inspecao", "Laudo_A", "Equipamentos")
        StorageEngine.ensureDefaultAlbumsIfEmpty(baseDirectory, defaultFolders)
        val loaded = StorageEngine.listAllAlbumsRecursively(baseDirectory)
        albumsList = loaded
        if (loaded.none { it.relativePath == activeFolder } && loaded.isNotEmpty()) {
            activeFolder = loaded.first().relativePath
            prefs.edit().putString("active_folder", activeFolder).apply()
        }
        recentFiles = StorageEngine.listRecentFilesInAlbum(baseDirectory, activeFolder)
    }

    LaunchedEffect(currentTab) {
        if (currentTab != 1) {
            registerVolumeTrigger {
                vibrateFeedback()
                currentTab = 1
            }
        }
    }

    Scaffold(
        containerColor = SolarWhite,
        bottomBar = {
            NavigationBar(
                containerColor = SolarBlack,
                contentColor = SolarWhite
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    label = { Text("PASTAS & CONFIG", fontWeight = FontWeight.ExtraBold) },
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
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    label = { Text("CÂMERA PRO", fontWeight = FontWeight.ExtraBold) },
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
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    label = { Text("REDE LAN", fontWeight = FontWeight.ExtraBold) },
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
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                0 -> HomeStorageScreen(
                    baseDirectory = baseDirectory,
                    onApplyBaseDir = { confirmedDir ->
                        val cleaned = StorageEngine.sanitizeRelativePath(confirmedDir).ifEmpty { "DCIM/CamFLC" }
                        baseDirectory = cleaned
                        prefs.edit().putString("base_dir", cleaned).apply()
                        vibrateFeedback()
                        lastCaptureLog = "DIRETÓRIO BASE APLICADO: /storage/emulated/0/$cleaned"
                        storageRefreshTick++
                    },
                    filePrefix = filePrefix,
                    onPrefixChange = {
                        filePrefix = it
                        prefs.edit().putString("file_prefix", it).apply()
                    },
                    albumsList = albumsList,
                    activeFolder = activeFolder,
                    recentFiles = recentFiles,
                    hasAllFilesAccess = hasAllFilesAccess,
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO
                            )
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
                            lastCaptureLog = "PASTA ATIVA CRIADA: $baseDirectory/$createdRel"
                            storageRefreshTick++
                        }
                    },
                    onRenameActiveFolder = { newName ->
                        val updatedRel = StorageEngine.renameAlbum(baseDirectory, activeFolder, newName)
                        if (updatedRel != null) {
                            activeFolder = updatedRel
                            prefs.edit().putString("active_folder", updatedRel).apply()
                            vibrateFeedback()
                            lastCaptureLog = "PASTA RENOMEADA PARA: $updatedRel"
                            storageRefreshTick++
                        } else {
                            lastCaptureLog = "Não foi possível renomear a pasta."
                        }
                    },
                    onDeleteActiveFolder = {
                        if (albumsList.size > 1) {
                            val deleted = StorageEngine.deleteAlbum(baseDirectory, activeFolder)
                            if (deleted) {
                                vibrateFeedback()
                                lastCaptureLog = "PASTA EXCLUÍDA: $activeFolder"
                                storageRefreshTick++
                            }
                        } else {
                            lastCaptureLog = "Mantenha pelo menos 1 pasta ativa."
                        }
                    },
                    onCleanGhostFolders = {
                        val removed = StorageEngine.cleanEmptyGhostFolders(baseDirectory)
                        vibrateFeedback()
                        lastCaptureLog = "FAXINA CONCLUÍDA: $removed pastas vazias removidas com sucesso!"
                        storageRefreshTick++
                    },
                    useNativeCameraFallback = useNativeCameraFallback,
                    onToggleNativeCamera = {
                        useNativeCameraFallback = it
                        prefs.edit().putBoolean("use_native_fallback", it).apply()
                    },
                    lastCaptureLog = lastCaptureLog,
                    onOpenProCamera = { currentTab = 1 },
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
                        lastCaptureLog = logMsg
                        storageRefreshTick++
                    },
                    onLaunchNativeCamera = launchNativeCamera,
                    vibrateFeedback = vibrateFeedback
                )

                2 -> LanPlaceholderScreen(
                    baseDirectory = baseDirectory,
                    albumsList = albumsList
                )
            }
        }
    }
}

@Composable
fun HomeStorageScreen(
    baseDirectory: String,
    onApplyBaseDir: (String) -> Unit,
    filePrefix: String,
    onPrefixChange: (String) -> Unit,
    albumsList: List<AlbumInfo>,
    activeFolder: String,
    recentFiles: List<File>,
    hasAllFilesAccess: Boolean,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onAddFolder: (String, Boolean) -> Unit,
    onRenameActiveFolder: (String) -> Unit,
    onDeleteActiveFolder: () -> Unit,
    onCleanGhostFolders: () -> Unit,
    useNativeCameraFallback: Boolean,
    onToggleNativeCamera: (Boolean) -> Unit,
    lastCaptureLog: String,
    onOpenProCamera: () -> Unit,
    onLaunchNativeCamera: () -> Unit
) {
    var newFolderName by remember { mutableStateOf("") }
    // Variavel em buffer para NAO criar pastas enquanto o usuario digita letra por letra
    var draftBaseDir by remember(baseDirectory) { mutableStateOf(baseDirectory) }
    var renameFolderText by remember(activeFolder) { mutableStateOf(activeFolder.substringAfterLast("/")) }
    var showManageFolderTools by remember { mutableStateOf(false) }

    val cleanPrefix = StorageEngine.sanitizePrefix(filePrefix)
    val activeAlbumInfo = albumsList.find { it.relativePath == activeFolder }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!hasAllFilesAccess || !hasCameraPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SolarAmber),
                border = BorderStroke(3.dp, SolarBlack),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "LIBERAR ACESSO TOTAL ÀS PASTAS E CÂMERA",
                        color = SolarBlack,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRequestPermissions,
                            colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("1. LIBERAR AGORA", fontWeight = FontWeight.ExtraBold)
                        }
                        OutlinedButton(
                            onClick = onRefreshPermissions,
                            border = BorderStroke(2.dp, SolarBlack),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SolarBlack)
                        ) {
                            Text("2. ATUALIZAR", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }

        // Cabecalho de Alto Contraste com Hierarquia Completa de Subpastas
        Surface(
            color = SolarBlack,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "CamFLC • CONTROLE DE CAMPO",
                    color = SolarAmber,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Destino Ativo: /storage/emulated/0/$baseDirectory/$activeFolder/",
                    color = SolarWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Próximo Arquivo: ${cleanPrefix}_AAAAMMDD_HHMMSS.jpg • (${activeAlbumInfo?.fileCount ?: 0} arq)",
                    color = SolarGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onOpenProCamera,
                colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                border = BorderStroke(3.dp, SolarBlack),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("ABRIR CÂMERA PRO", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }

            if (useNativeCameraFallback) {
                Button(
                    onClick = onLaunchNativeCamera,
                    colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                    border = BorderStroke(3.dp, SolarBlack),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                ) {
                    Icon(Icons.Default.PhotoCameraBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CÂMERA NATIVA", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // 1. Gerenciador Agil de Pastas e Subpastas
        Card(
            colors = CardDefaults.cardColors(containerColor = SolarSurface),
            border = BorderStroke(2.dp, SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "1. PASTAS & SUBPASTAS (1 TOQUE)",
                        color = SolarBlack,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    TextButton(onClick = { showManageFolderTools = !showManageFolderTools }) {
                        Text(
                            text = if (showManageFolderTools) "OCULTAR OPÇÕES" else "OPÇÕES / LIMPEZA",
                            color = SolarBlack,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Se estiver dentro de uma subpasta (ex: DESC/JF22JS35), mostra atalho para subir para a pasta pai
                if (activeFolder.contains("/")) {
                    val parentFolder = activeFolder.substringBeforeLast("/")
                    OutlinedButton(
                        onClick = { onSelectFolder(parentFolder) },
                        border = BorderStroke(2.dp, SolarBlack),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = SolarBlack)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "VOLTAR PARA PASTA PAI: $parentFolder",
                            color = SolarBlack,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(albumsList) { album ->
                        val isSelected = album.relativePath == activeFolder
                        val isSubfolder = album.relativePath.contains("/")
                        Surface(
                            color = if (isSelected) SolarBlack else SolarWhite,
                            border = BorderStroke(2.dp, SolarBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(60.dp)
                                .clickable { onSelectFolder(album.relativePath) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        isSelected -> Icons.Default.FolderSpecial
                                        isSubfolder -> Icons.Default.SubdirectoryArrowRight
                                        else -> Icons.Default.Folder
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) SolarAmber else SolarBlack
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = album.relativePath,
                                        color = if (isSelected) SolarAmber else SolarBlack,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = "${album.fileCount} arq • ${album.formattedSize()}",
                                        color = if (isSelected) SolarWhite else SolarBlack,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Campo unico intuitivo para criar Pasta na Raiz ou Subpasta dentro da Pasta Ativa
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = {
                        Text(
                            text = "Nome da Pasta ou Subpasta (ex: DESC ou DESC/JF22JS35)",
                            fontWeight = FontWeight.Bold,
                            color = SolarBlack
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Cria como Subpasta dentro da pasta atualmente selecionada
                    Button(
                        onClick = {
                            if (newFolderName.isNotBlank()) {
                                onAddFolder(newFolderName, true)
                                newFolderName = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                        border = BorderStroke(2.dp, SolarBlack),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "+ SUBPASTA EM '${activeFolder.substringAfterLast("/")}'",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }

                    // Cria diretamente na Raiz
                    Button(
                        onClick = {
                            if (newFolderName.isNotBlank()) {
                                onAddFolder(newFolderName, false)
                                newFolderName = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("+ PASTA NA RAIZ", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                }

                if (showManageFolderTools) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = SolarBlack, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Botao de Faxina em 1 Toque para apagar pastas vazias criadas por engano
                    Button(
                        onClick = onCleanGhostFolders,
                        colors = ButtonDefaults.buttonColors(containerColor = SolarGreen, contentColor = SolarBlack),
                        border = BorderStroke(2.dp, SolarBlack),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIMPAR TODAS AS PASTAS VAZIAS (0 ARQ)",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = renameFolderText,
                            onValueChange = { renameFolderText = it },
                            label = { Text("Renomear '$activeFolder'", fontWeight = FontWeight.Bold) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { onRenameActiveFolder(renameFolderText) },
                            colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarWhite),
                            modifier = Modifier.height(54.dp)
                        ) {
                            Text("RENOMEAR", fontWeight = FontWeight.ExtraBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onDeleteActiveFolder,
                        border = BorderStroke(2.dp, SolarRed),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SolarRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("EXCLUIR PASTA '$activeFolder' DO DISCO", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        // 2. Prefixo Padrao e Diretorio Base Com Confirmacao Explicita
        Card(
            colors = CardDefaults.cardColors(containerColor = SolarSurface),
            border = BorderStroke(2.dp, SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "2. PREFIXO E DIRETÓRIO BASE",
                    color = SolarBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                OutlinedTextField(
                    value = filePrefix,
                    onValueChange = onPrefixChange,
                    label = { Text("Prefixo Padrão dos Arquivos (ex: JF22JS35)", fontWeight = FontWeight.Bold, color = SolarBlack) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("DCIM/CamFLC", "Pictures/CamFLC", "Documents/CamFLC").forEach { preset ->
                        val selected = baseDirectory == preset
                        Surface(
                            color = if (selected) SolarBlack else SolarWhite,
                            border = BorderStroke(2.dp, SolarBlack),
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
                                color = if (selected) SolarAmber else SolarBlack,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }

                // Campo com Botao APLICAR para nunca criar pastas parciais enquanto digita
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draftBaseDir,
                        onValueChange = { draftBaseDir = it },
                        label = { Text("Caminho Base Personalizado", fontWeight = FontWeight.Bold, color = SolarBlack) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onApplyBaseDir(draftBaseDir) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (draftBaseDir != baseDirectory) SolarAmber else SolarBlack,
                            contentColor = if (draftBaseDir != baseDirectory) SolarBlack else SolarAmber
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("APLICAR", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        // 3. Ultimos Arquivos Reais na Pasta/Subpasta Ativa
        Card(
            colors = CardDefaults.cardColors(containerColor = SolarSurface),
            border = BorderStroke(2.dp, SolarBlack),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "3. ÚLTIMOS ARQUIVOS EM '$activeFolder'",
                    color = SolarBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Exibir botão auxiliar da Câmera Nativa",
                        color = SolarBlack,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = useNativeCameraFallback,
                        onCheckedChange = onToggleNativeCamera
                    )
                }

                Surface(
                    color = SolarBlack,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lastCaptureLog,
                        color = SolarGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                recentFiles.forEach { file ->
                    Text(
                        text = "✓ ${file.name} (${file.length() / 1024} KB)",
                        color = SolarBlack,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun LanPlaceholderScreen(
    baseDirectory: String,
    albumsList: List<AlbumInfo>
) {
    val totalFiles = albumsList.sumOf { it.fileCount }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            color = SolarBlack,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "COMPARTILHAMENTO LAN (CELULAR & PC)",
                    color = SolarAmber,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Raiz: /storage/emulated/0/$baseDirectory",
                    color = SolarWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${albumsList.size} pastas/subpastas • $totalFiles fotos prontas para rede",
                    color = SolarGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
