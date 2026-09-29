package com.example.globaltranslation.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.globaltranslation.core.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoTranslationApp(viewModel: CameraViewModel) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importPhoto { loadSelectedPhoto(resolver, uri) }
    }
    val choosePhoto: () -> Unit = {
        try { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: android.content.ActivityNotFoundException) { viewModel.showError("无法打开图片选择器，请检查系统相册或文件应用。") }
    }
    var settingsPage by rememberSaveable { mutableStateOf(false) }
    BackHandler(settingsPage || state.photo != null || state.isBusy) {
        when {
            settingsPage -> settingsPage = false
            state.isBusy -> viewModel.cancel()
            else -> viewModel.resetPhoto()
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (settingsPage) "设置" else "拍照翻译", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { if (settingsPage) IconButton(onClick = { settingsPage = false }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回相机")
            } },
            actions = { if (!settingsPage) {
                if (state.photo != null) IconButton(onClick = choosePhoto, enabled = !state.isBusy && state.settingsLoaded,
                    modifier = Modifier.testTag("choose_photo")) { Icon(Icons.Default.PhotoLibrary, "从相册选择") }
                IconButton(onClick = { settingsPage = true }, enabled = !state.isBusy) { Icon(Icons.Default.Settings, "设置") }
            } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val message = state.error ?: state.notice
            if (message != null) {
                Surface(color = if (state.error != null) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f).testTag("status_message"), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = viewModel::clearMessage) { Icon(Icons.Default.Close, "关闭提示") }
                    }
                }
            }
            if (settingsPage) SettingsContent(state, viewModel)
            else CameraContent(state, viewModel, choosePhoto) { settingsPage = true }
        }
    }
}

@Composable
private fun CameraContent(state: CameraUiState, viewModel: CameraViewModel, choosePhoto: () -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    var choosing by remember { mutableStateOf<String?>(null) }
    var selectedBlock by remember { mutableStateOf<PhotoTextBlock?>(null) }
    var capture by remember { mutableStateOf<((Long) -> Unit)?>(null) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(state.photo) { selectedBlock = null }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choosing = "script" }, enabled = !state.isBusy,
                modifier = Modifier.weight(1f).testTag("script_selector")) {
                Column { Text("原文文字体系", style = MaterialTheme.typography.labelSmall); Text(state.settings.script.label) }
            }
            OutlinedButton(onClick = { choosing = "target" }, enabled = !state.isBusy,
                modifier = Modifier.weight(1f).testTag("target_selector")) {
                Column { Text("翻译成", style = MaterialTheme.typography.labelSmall); Text(TargetLanguages.find(state.settings.targetLanguage).label) }
            }
        }
        OutlinedButton(onClick = { choosing = "prompt" }, enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth().testTag("prompt_selector")) {
            Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("翻译要求：${state.settings.selectedTemplate?.name ?: "仅基础翻译"}", Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        if (!state.hasApiKey) {
            Surface(onClick = openSettings, enabled = !state.isBusy, color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("先在设置中填写自己的 DeepSeek API Key", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
            val photo = state.photo
            when {
                photo != null -> AndroidView(factory = { PhotoOverlayView(it) }, modifier = Modifier.fillMaxSize().testTag("photo_overlay"),
                    update = { it.show(photo, state.blocks, state.translations) { block -> selectedBlock = block } })
                hasPermission -> CameraPreview(flash, Modifier.fillMaxSize(),
                    onCaptureReady = { capture = it }, onCaptured = viewModel::captured,
                    onCaptureError = viewModel::captureFailed,
                    onCameraError = { viewModel.showError("无法打开相机，请检查相机权限或关闭其他相机应用。") })
                else -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("允许使用相机，即可拍照翻译", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("允许相机权限") }
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }) { Text("打开应用权限设置") }
                }
            }
            if (photo == null && hasPermission) {
                FilledTonalIconButton(onClick = { flash = !flash }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Icon(if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff, if (flash) "关闭补光" else "打开补光")
                }
            }
        }
        if (state.isBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.stage.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::cancel) { Text("取消") }
            }
        } else {
            Text(when {
                state.isResultStale -> "尚未应用更改，请点击下方按钮。"
                state.photo != null && state.blocks.isNotEmpty() -> "已翻译 ${state.translations.size}/${state.blocks.size} 段 · 双指放大，点按查看全文；红框未完成"
                else -> "对准清晰印刷文字 · 原文只在本机识别"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.photo != null) {
                FilledTonalIconButton(onClick = viewModel::rotatePhoto, enabled = !state.isBusy) {
                    Icon(Icons.Default.RotateLeft, "向左旋转照片")
                }
                OutlinedButton(onClick = viewModel::resetPhoto, enabled = !state.isBusy, modifier = Modifier.weight(1f)) { Text("重新拍照") }
                Button(onClick = viewModel::translate, enabled = !state.isBusy, modifier = Modifier.weight(1.6f).testTag("retranslate")) {
                    Text(if (state.needsRecognition) "重新识别并翻译" else if (state.error != null) "重试翻译" else "重新翻译")
                }
            } else {
                OutlinedButton(onClick = choosePhoto, enabled = !state.isBusy && state.settingsLoaded,
                    modifier = Modifier.weight(1f).height(54.dp).testTag("choose_photo")) {
                    Icon(Icons.Default.PhotoLibrary, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp)); Text("从相册选择")
                }
                Button(onClick = { viewModel.beginCapture()?.let { capture?.invoke(it) } },
                    enabled = hasPermission && capture != null && !state.isBusy && state.settingsLoaded,
                    modifier = Modifier.weight(1f).height(54.dp).testTag("capture")) {
                    Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("拍照并翻译")
                }
            }
        }
    }
    when (choosing) {
        "script" -> ChoiceDialog("原文文字体系", TextScript.entries.map { Choice(it.name, it.label, it.description) }, state.settings.script.name,
            { viewModel.selectScript(TextScript.valueOf(it)); choosing = null }, { choosing = null })
        "target" -> ChoiceDialog("翻译成", TargetLanguages.all.map { Choice(it.code, it.label) }, state.settings.targetLanguage,
            { viewModel.selectTarget(it); choosing = null }, { choosing = null })
        "prompt" -> ChoiceDialog("翻译要求", listOf(Choice("", "仅基础翻译", "使用 App 内置的翻译规则")) +
            state.settings.templates.map { Choice(it.id, it.name, it.body) }, state.settings.selectedTemplateId.orEmpty(),
            { viewModel.selectTemplate(it.ifEmpty { null }); choosing = null }, { choosing = null },
            content = { TextButton(onClick = { choosing = null; openSettings() }) { Text("管理模板") } })
    }
    selectedBlock?.let { block -> BlockDetails(block, state.translations[block.id]) { selectedBlock = null } }
}

private data class Choice(val id: String, val title: String, val description: String = "")

@Composable
private fun ChoiceDialog(title: String, options: List<Choice>, selected: String, choose: (String) -> Unit,
    dismiss: () -> Unit, content: @Composable () -> Unit = {}) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(options, key = { it.id }) { choice ->
                Row(Modifier.fillMaxWidth().clickable { choose(choice.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice.id == selected, onClick = { choose(choice.id) })
                    Column { Text(choice.title); if (choice.description.isNotEmpty()) Text(choice.description,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } }, dismissButton = content)
}

@Composable
private fun BlockDetails(block: PhotoTextBlock, translation: String?, dismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = dismiss, title = { Text("完整译文") }, text = {
        SelectionContainer {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("原文", style = MaterialTheme.typography.labelMedium)
                Text(block.text)
                HorizontalDivider()
                Text("译文", style = MaterialTheme.typography.labelMedium)
                Text(translation ?: "这段文字尚未完成翻译，请返回重试。")
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } }, dismissButton = {
        if (translation != null) TextButton(onClick = { clipboard.setText(AnnotatedString(translation)) }) { Text("复制译文") }
    })
}

@Composable
private fun SettingsContent(state: CameraUiState, viewModel: CameraViewModel) {
    var keyInput by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<PromptTemplate?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PromptTemplate?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("DeepSeek API", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(if (state.hasApiKey) "已配置 · Key 加密保存在本机" else "使用自己的 Key，费用由自己的 DeepSeek 账户承担。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = keyInput, onValueChange = { if (it.length <= 512) keyInput = it },
                label = { Text(if (state.hasApiKey) "填写新的 API Key" else "API Key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("key_input"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveApiKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank(),
                    modifier = Modifier.testTag("save_key")) { Text("保存 Key") }
                if (state.hasApiKey) TextButton(onClick = viewModel::clearApiKey) { Text("清除 Key") }
            }
        }
        item { HorizontalDivider() }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("翻译要求模板", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                FilledTonalButton(onClick = { adding = true }, modifier = Modifier.testTag("add_template")) { Text("新增") }
            }
            Text("模板补充术语、领域或风格；目标语言始终以相机页的选择为准。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.settings.templates.isEmpty()) item {
            Text("还没有模板。可添加“机械工程”“医学”等常用要求，也可直接使用基础翻译。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.settings.templates, key = { it.id }) { template ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(template.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(template.body.ifBlank { "使用基础翻译规则" }, maxLines = 4, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { editing = template }) { Text("编辑") }
                        TextButton(onClick = { deleting = template }) { Text("删除") }
                    }
                }
            }
        }
        item {
            HorizontalDivider(); Spacer(Modifier.height(16.dp))
            Text("照片在本机识字，只将识别出的文字和翻译要求发送给 DeepSeek。照片与译文仅保留在当前会话中。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (adding || editing != null) TemplateEditor(editing,
        save = { name, body -> viewModel.saveTemplate(editing?.id, name, body); adding = false; editing = null },
        dismiss = { adding = false; editing = null })
    deleting?.let { template -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text("删除“${template.name}”？") }, text = { Text("如果正在使用此模板，将切换为仅基础翻译。") },
        confirmButton = { TextButton(onClick = { viewModel.deleteTemplate(template.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun TemplateEditor(template: PromptTemplate?, save: (String, String) -> Unit, dismiss: () -> Unit) {
    var name by remember(template?.id) { mutableStateOf(template?.name.orEmpty()) }
    var body by remember(template?.id) { mutableStateOf(template?.body.orEmpty()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (template == null) "新增翻译要求" else "编辑翻译要求") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("模板名称") }, singleLine = true,
                isError = name.length > 40, supportingText = { Text("${name.length}/40") }, modifier = Modifier.testTag("template_name"))
            OutlinedTextField(body, { body = it }, label = { Text("附加翻译要求") }, minLines = 4, maxLines = 8,
                placeholder = { Text("例如：使用机械工程术语，保留型号、数值和单位。") },
                isError = body.length > 8000, supportingText = { Text("${body.length}/8000") }, modifier = Modifier.testTag("template_body"))
        }
    }, confirmButton = { TextButton(onClick = { save(name, body) }, enabled = name.isNotBlank() && name.length <= 40 && body.length <= 8000,
        modifier = Modifier.testTag("save_template")) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
