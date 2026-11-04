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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

// Paleta de Alto Contraste Solar (Outdoor-First)
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

    // Estado real sincronizado com o sistema de arquivos do aparelho
    var albumsList by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }
    var recentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var storageRefreshTick by remember { mutableIntStateOf(0) }
    var lastCaptureLog by remember { mutableStateOf("Pronto para gravar arquivos reais no dispositivo.") }
    var currentTab by remember { mutableIntStateOf(0) }

    // Verificacao de permissoes de Armazenamento Total (Android 11+) e Camera
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

    // Launcher para abrir a Camera Nativa do Sistema salvando direto na pasta e com prefixo
    var pendingNativeCameraFile by remember { mutableStateOf<File?>(null) }
    val nativeCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingNativeCameraFile
        if (success && file != null && file.exists()) {
            StorageEngine.indexFileInGallery(context, file)
            vibrateFeedback()
            lastCaptureLog = "FOTO NATIVA SALVA: ${file.absolutePath} (${file.length() / 1024} KB)"
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
                albumName = activeFolder,
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

    // Atualiza as pastas reais do disco sempre que algo mudar
    LaunchedEffect(baseDirectory, activeFolder, storageRefreshTick) {
        val defaultFolders = listOf("Geral", "Inspecao", "Laudo_A", "Equipamentos")
        StorageEngine.ensureDefaultAlbums(baseDirectory, defaultFolders)
        val loaded = StorageEngine.listAlbums(baseDirectory)
        albumsList = loaded
        if (loaded.none { it.name == activeFolder } && loaded.isNotEmpty()) {
            activeFolder = loaded.first().name
            prefs.edit().putString("active_folder", activeFolder).apply()
        }
        recentFiles = StorageEngine.listRecentFilesInAlbum(baseDirectory, activeFolder)
    }

    // Funcao de Disparo Fisico (Volume -) gravando arquivo real no disco
    val triggerPhysicalCapture: () -> Unit = {
        vibrateFeedback()
        val result = StorageEngine.generateQuickTestPhoto(
            context = context,
            baseRelativePath = baseDirectory,
            albumName = activeFolder,
            prefix = filePrefix
        )
        result.onSuccess { savedFile ->
            lastCaptureLog = "GRAVADO NO DISCO: ${savedFile.absolutePath}"
            storageRefreshTick++
        }.onFailure { err ->
            lastCaptureLog = "FALHA DE ESCRITA: ${err.message} (Verifique a permissão de pastas)"
        }
    }

    LaunchedEffect(filePrefix, activeFolder, baseDirectory) {
        registerVolumeTrigger(triggerPhysicalCapture)
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
                    onBaseDirChange = {
                        baseDirectory = it
                        prefs.edit().putString("base_dir", it).apply()
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
                    onSelectFolder = { folderName ->
                        activeFolder = folderName
                        prefs.edit().putString("active_folder", folderName).apply()
                        vibrateFeedback()
                        storageRefreshTick++
                    },
                    onAddFolder = { newName ->
                        val created = StorageEngine.createAlbum(baseDirectory, newName)
                        if (created) {
                            val clean = StorageEngine.sanitizeFolderName(newName)
                            activeFolder = clean
                            prefs.edit().putString("active_folder", clean).apply()
                            vibrateFeedback()
                            lastCaptureLog = "PASTA CRIADA NO DISCO: /storage/emulated/0/$baseDirectory/$clean"
                            storageRefreshTick++
                        }
                    },
                    onRenameActiveFolder = { newName ->
                        val renamed = StorageEngine.renameAlbum(baseDirectory, activeFolder, newName)
                        if (renamed) {
                            val clean = StorageEngine.sanitizeFolderName(newName)
                            activeFolder = clean
                            prefs.edit().putString("active_folder", clean).apply()
                            vibrateFeedback()
                            lastCaptureLog = "PASTA RENOMEADA PARA: $clean"
                            storageRefreshTick++
                        } else {
                            lastCaptureLog = "Não foi possível renomear (verifique o nome ou permissão)."
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
                    useNativeCameraFallback = useNativeCameraFallback,
                    onToggleNativeCamera = {
                        useNativeCameraFallback = it
                        prefs.edit().putBoolean("use_native_fallback", it).apply()
                    },
                    lastCaptureLog = lastCaptureLog,
                    onOpenProCamera = { currentTab = 1 },
                    onLaunchNativeCamera = launchNativeCamera
                )

                1 -> CameraPreviewPlaceholderScreen(
                    activeFolder = activeFolder,
                    albumsList = albumsList,
                    filePrefix = filePrefix,
                    lastCaptureLog = lastCaptureLog,
                    useNativeCameraFallback = useNativeCameraFallback,
                    onSelectFolder = { folder ->
                        activeFolder = folder
                        prefs.edit().putString("active_folder", folder).apply()
                        vibrateFeedback()
                        storageRefreshTick++
                    },
                    onTriggerCapture = triggerPhysicalCapture,
                    onLaunchNativeCamera = launchNativeCamera
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
    onBaseDirChange: (String) -> Unit,
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
    onAddFolder: (String) -> Unit,
    onRenameActiveFolder: (String) -> Unit,
    onDeleteActiveFolder: () -> Unit,
    useNativeCameraFallback: Boolean,
    onToggleNativeCamera: (Boolean) -> Unit,
    lastCaptureLog: String,
    onOpenProCamera: () -> Unit,
    onLaunchNativeCamera: () -> Unit
) {
    var newFolderName by remember { mutableStateOf("") }
    var renameFolderText by remember(activeFolder) { mutableStateOf(activeFolder) }
    var showManageFolderTools by remember { mutableStateOf(false) }

    val cleanPrefix = StorageEngine.sanitizePrefix(filePrefix)
    val activeAlbumInfo = albumsList.find { it.name == activeFolder }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Alerta de Permissoes de Hardware e Pastas (Exibido apenas se faltar liberar algo)
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
                    Text(
                        text = "Para criar, renomear pastas livremente no armazenamento interno e usar a câmera sem bloqueios do Android, toque em LIBERAR AGORA e depois em ATUALIZAR STATUS.",
                        color = SolarBlack,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
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

        // Cabecalho de Alto Contraste com Caminho Fisico Real
        Surface(
            color = SolarBlack,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "CamFLC • MOTOR DE PASTAS ATIVO",
                    color = SolarAmber,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Caminho Real: /storage/emulated/0/$baseDirectory/$activeFolder/",
                    color = SolarWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Padrão: ${cleanPrefix}_AAAAMMDD_HHMMSS.jpg • (${activeAlbumInfo?.fileCount ?: 0} arquivos | ${activeAlbumInfo?.formattedSize() ?: "0 B"})",
                    color = SolarGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Botoes de Acao Rapida de Captura (Camera Pro + Camera Nativa Automatica)
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
                Text("VISOR PRO", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
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

        // 1. Alternancia Agil de Albuns Reais no Disco
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
                        text = "1. ÁLBUNS REAIS NO DISCO (1 TOQUE)",
                        color = SolarBlack,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    TextButton(onClick = { showManageFolderTools = !showManageFolderTools }) {
                        Text(
                            text = if (showManageFolderTools) "OCULTAR OPÇÕES" else "EDITAR PASTA",
                            color = SolarBlack,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(albumsList) { album ->
                        val isSelected = album.name == activeFolder
                        Surface(
                            color = if (isSelected) SolarBlack else SolarWhite,
                            border = BorderStroke(2.dp, SolarBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(58.dp)
                                .clickable { onSelectFolder(album.name) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.FolderSpecial else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (isSelected) SolarAmber else SolarBlack
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = album.name,
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

                // Criador rapido de nova pasta fisica
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Novo Álbum/Pasta no Disco", fontWeight = FontWeight.Bold, color = SolarBlack) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            onAddFolder(newFolderName)
                            newFolderName = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SolarBlack, contentColor = SolarAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CRIAR", fontWeight = FontWeight.ExtraBold)
                    }
                }

                // Ferramentas de Renomear / Excluir a Pasta Ativa
                if (showManageFolderTools) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = SolarBlack, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "GERENCIAR PASTA ATIVA ('$activeFolder'):",
                        color = SolarBlack,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = renameFolderText,
                            onValueChange = { renameFolderText = it },
                            label = { Text("Renomear Pasta Ativa", fontWeight = FontWeight.Bold) },
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

        // 2. Prefixo Padrao e Selecao Rapida de Diretorio Raiz
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
                    text = "2. PREFIXO E LOCAL DE ARMAZENAMENTO",
                    color = SolarBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                OutlinedTextField(
                    value = filePrefix,
                    onValueChange = onPrefixChange,
                    label = { Text("Prefixo Padrão dos Arquivos", fontWeight = FontWeight.Bold, color = SolarBlack) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Atalhos de Diretório Raiz no Celular:",
                    color = SolarBlack,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
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
                                .clickable { onBaseDirChange(preset) }
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

                OutlinedTextField(
                    value = baseDirectory,
                    onValueChange = onBaseDirChange,
                    label = { Text("Caminho Base Personalizado", fontWeight = FontWeight.Bold, color = SolarBlack) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 3. Log de Gravacao Fisica e Ultimos Arquivos da Pasta Ativa
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
                    text = "3. ARQUIVOS REAIS EM '$activeFolder' & GATILHO VOLUME -",
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
                        text = "Exibir botão da Câmera Nativa do Sistema",
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

                if (recentFiles.isNotEmpty()) {
                    Text(
                        text = "Últimos arquivos gravados nesta pasta:",
                        color = SolarBlack,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    recentFiles.forEach { file ->
                        Text(
                            text = "✓ ${file.name} (${file.length() / 1024} KB)",
                            color = SolarBlack,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = "Pasta vazia. Aperte 'Volume -' ou 'Câmera Nativa' para gravar o primeiro arquivo real!",
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
fun CameraPreviewPlaceholderScreen(
    activeFolder: String,
    albumsList: List<AlbumInfo>,
    filePrefix: String,
    lastCaptureLog: String,
    useNativeCameraFallback: Boolean,
    onSelectFolder: (String) -> Unit,
    onTriggerCapture: () -> Unit,
    onLaunchNativeCamera: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SolarBlack)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "PASTA ATIVA NO VISOR (TOQUE PARA ALTERNAR EM 1 SEGUNDO):",
                color = SolarAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(albumsList) { album ->
                    val isSelected = album.name == activeFolder
                    Surface(
                        color = if (isSelected) SolarAmber else Color(0xFF262626),
                        border = BorderStroke(2.dp, SolarAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .clickable { onSelectFolder(album.name) }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            Text(
                                text = "${album.name} (${album.fileCount})",
                                color = if (isSelected) SolarBlack else SolarWhite,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = Color(0xFF1A1A1A),
            border = BorderStroke(2.dp, SolarAmber),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = null,
                    tint = SolarAmber,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "MOTOR DE ARMAZENAMENTO FÍSICO CONECTADO",
                    color = SolarWhite,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Pressione 'VOLUME -' para gravar um JPEG real de teste na pasta '$activeFolder' ou acione a Câmera Nativa abaixo. No Marco 3 este quadro será o visor ótico CameraX Pro!",
                    color = SolarAmber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = lastCaptureLog,
                    color = SolarGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onTriggerCapture,
                colors = ButtonDefaults.buttonColors(containerColor = SolarAmber, contentColor = SolarBlack),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "GRAVAR JPEG REAL (OU VOLUME -)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            if (useNativeCameraFallback) {
                OutlinedButton(
                    onClick = onLaunchNativeCamera,
                    border = BorderStroke(2.dp, SolarAmber),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SolarAmber),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Icon(Icons.Default.PhotoCameraBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ABRIR CÂMERA NATIVA DO APARELHO",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
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
                    text = "${albumsList.size} álbuns físicos prontos • $totalFiles arquivos indexados para rede",
                    color = SolarGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
