package com.multitrack.app.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.data.ScanFilter
import com.multitrack.app.player.data.ScanSettingsStore

/**
 * 扫描设置页（资料库右上角 ⚙ 进入）。
 *
 * 版式对齐用户给的参考截图：分组标题（歌曲库 / 扫描 / 信息）+ 行式条目；
 * 「已排除的文件夹」「需扫描的文件夹」是带说明文字的二级页，底部 + 添加文件夹。
 *
 * 所有排除项**全局生效**：过滤只发生在扫描入口（不进曲库），
 * 所以这里每处修改都触发一次重扫，曲库里已有的违规条目会被清掉。
 */
@Composable
fun ScanSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val store = remember { container.scanSettings }
    var opts by remember { mutableStateOf(store.load()) }
    var page by remember { mutableStateOf(ScanPage.MAIN) }

    var minDurOpen by remember { mutableStateOf(false) }
    var typesOpen by remember { mutableStateOf(false) }
    var storageOpen by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    val scan by container.scan.collectAsState()
    val scope = rememberCoroutineScope()

    // 系统返回键逐级回退：二级页 → 设置主页 → 资料库，而不是一路退出应用。
    // （对话框打开时返回键由 AlertDialog 自己消费，到不了这里）
    BackHandler {
        if (page != ScanPage.MAIN) page = ScanPage.MAIN else onBack()
    }

    fun applyAndRescan(o: ScanSettingsStore.Options) {
        opts = o
        container.rescan()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PColors.Bg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (page == ScanPage.MAIN) onBack() else page = ScanPage.MAIN
            }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PColors.TextDim)
            }
            Text(
                when (page) {
                    ScanPage.MAIN -> "歌曲库及标签"
                    ScanPage.ROOTS -> "需扫描的文件夹"
                    ScanPage.EXCLUDE -> "已排除的文件夹"
                },
                color = PColors.Text, fontSize = 17.sp
            )
        }

        when (page) {
            ScanPage.MAIN -> Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel("歌曲库")
                SettingsRow("需扫描的文件夹", rootsSubtitle(opts.scanRoots)) { page = ScanPage.ROOTS }
                SettingsRow("已排除的文件夹", "${opts.excludeDirs.size} 个 · 子文件夹一并排除") { page = ScanPage.EXCLUDE }
                SettingsRow(
                    "排除短时长的文件",
                    if (opts.minDurationSec > 0) "少于 ${opts.minDurationSec / 60} 分 ${opts.minDurationSec % 60} 秒" else "未启用"
                ) { minDurOpen = true }
                SettingsRow("高级", "重置扫描设置") { advancedOpen = true }

                SectionLabel("扫描")
                SettingsRow(
                    if (scan.running) scan.message.ifBlank { "扫描中…" } else "立即扫描",
                    if (scan.running) "" else "按当前设置重建索引${if (scan.count > 0) " · 当前 ${scan.count} 首" else ""}",
                    leading = { Icon(Icons.Rounded.Refresh, contentDescription = null, tint = PColors.Accent) },
                    enabled = !scan.running
                ) { container.rescan() }
                SettingsToggleRow(
                    "使用 folder.jpg、cover.jpg 等文件",
                    "若未找到歌曲内嵌的专辑封面，则使用同一文件夹的 folder.jpg、AlbumArt.jpg 等作为封面",
                    checked = opts.folderCovers
                ) { on ->
                    store.saveFolderCovers(on)
                    opts = store.load()
                    scope.launch { container.art.invalidateAll() }
                }

                SectionLabel("信息")
                SettingsRow("支持的文件类型", "mp3 / flac / wav / ogg / m4a …") { typesOpen = true }
                SettingsRow("存储设备", "") { storageOpen = true }
                // 底部导航浮在内容上（80dp + 手势条）：最后一行要完整滚出来，
                // 竖屏横屏都要留够，否则「存储设备」永远压在导航栏底下
                Spacer(Modifier.height(110.dp))
            }

            ScanPage.ROOTS -> FolderListPage(
                container = container,
                current = opts.scanRoots,
                hint = "只扫描下列文件夹中的歌曲（子文件夹一并包含）。留空 = 扫描整个媒体库。",
                emptyHint = "未指定，扫描全库"
            ) { dirs ->
                store.saveRoots(dirs)
                applyAndRescan(store.load())
            }

            ScanPage.EXCLUDE -> FolderListPage(
                container = container,
                current = opts.excludeDirs,
                hint = "下列文件夹中列出的歌曲将不会被扫描。这些文件夹中的子文件夹的歌曲也不会被扫描到。",
                emptyHint = "未排除任何文件夹"
            ) { dirs ->
                store.saveExcludes(dirs)
                applyAndRescan(store.load())
            }
        }
    }

    if (minDurOpen) MinDurationDialog(opts.minDurationSec, onDismiss = { minDurOpen = false }) { sec ->
        store.saveMinDurationSec(sec)
        applyAndRescan(store.load())
        minDurOpen = false
    }
    if (typesOpen) InfoDialog("支持的文件类型", "mp3、flac、wav、ogg、m4a、aac、opus、wma 等 —\n能进曲库的范围由系统媒体库（MediaStore）索引的音频决定。") { typesOpen = false }
    if (storageOpen) StorageDialog(container) { storageOpen = false }
    if (advancedOpen) AlertDialog(
        onDismissRequest = { advancedOpen = false },
        title = { Text("高级", color = PColors.Text) },
        text = { Text("重置全部扫描设置：清空扫描根、排除目录与短时长过滤，恢复默认（全库扫描）。曲库需要重扫一次才会恢复。", color = PColors.TextWeak, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = {
                store.reset()
                applyAndRescan(store.load())
                advancedOpen = false
            }) { Text("重置", color = PColors.Accent) }
        },
        dismissButton = { TextButton(onClick = { advancedOpen = false }) { Text("取消", color = PColors.TextWeak) } },
        containerColor = PColors.SurfaceAlt
    )
}

private enum class ScanPage { MAIN, ROOTS, EXCLUDE }

private fun rootsSubtitle(roots: Set<String>): String =
    if (roots.isEmpty()) "未指定 · 扫描全库" else "${roots.size} 个 · 只扫这些目录（含子目录）"

/* ---------------- 二级页：文件夹列表 ---------------- */

@Composable
private fun FolderListPage(
    container: AppContainer,
    current: Set<String>,
    hint: String,
    emptyHint: String,
    onChange: (Set<String>) -> Unit
) {
    var addOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Text(hint, color = PColors.TextWeak, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(vertical = 10.dp))
        LazyColumn(
            Modifier.weight(1f),
            // 底部导航浮在内容上：留出它的高度，列表滚到底也不被挡
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 110.dp)
        ) {
            if (current.isEmpty()) {
                item { Text(emptyHint, color = PColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            items(current.sorted()) { dir ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(dir, color = PColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onChange(current - dir) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "移除", tint = PColors.TextDim)
                    }
                }
            }
            // 「+ 添加文件夹」紧跟在列表内容下方，不再固定在屏幕底部
            // （底部导航是浮层，固定底部的按钮会被它盖住）
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { addOpen = true }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = PColors.Text)
                    Text("添加文件夹", color = PColors.Text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
    if (addOpen) AddFolderDialog(container, existing = current, onDismiss = { addOpen = false }) { dir ->
        onChange(current + dir)
        addOpen = false
    }
}

/**
 * 添加文件夹：**搜出来的曲库目录点一下就加**，输入框里的路径也能直接回车添加
 * （曲库里还没有歌的目录也能手动加进来）。
 */
@Composable
private fun AddFolderDialog(
    container: AppContainer,
    existing: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val folders by container.folderDao().observeAll().collectAsState(initial = emptyList())
    val candidates = remember(folders, query) {
        val q = query.trim()
        if (q.isBlank()) folders.take(60)
        else folders.filter { it.path.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }.take(60)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加文件夹", color = PColors.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("搜索曲库目录，或直接输入路径", color = PColors.TextDim, fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = PColors.Text, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.fillMaxWidth().height(260.dp).padding(top = 8.dp)) {
                    val typed = ScanFilter.normalizeDir(query)
                    if (typed.isNotBlank() && folders.none { it.path == typed } && typed !in existing) {
                        item {
                            Text(
                                "添加输入的路径：$typed",
                                color = PColors.Accent, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().clickable { onAdd(typed) }.padding(vertical = 8.dp)
                            )
                        }
                    }
                    items(candidates) { f ->
                        if (f.path !in existing) {
                            Text(
                                f.path,
                                color = PColors.Text, fontSize = 12.sp, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().clickable { onAdd(f.path) }.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = PColors.TextWeak) } },
        containerColor = PColors.SurfaceAlt
    )
}

/* ---------------- 对话框 ---------------- */

@Composable
private fun MinDurationDialog(currentSec: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var min by remember { mutableStateOf(if (currentSec > 0) (currentSec / 60).toString() else "") }
    var sec by remember { mutableStateOf(if (currentSec > 0) (currentSec % 60).toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排除短时长的文件", color = PColors.Text) },
        text = {
            Column {
                Text("少于这个时长的歌曲及音频片段将从扫描过程中排除。", color = PColors.TextWeak, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedTextField(
                        value = min, onValueChange = { min = it.take(3) },
                        label = { Text("mm", color = PColors.TextDim, fontSize = 11.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = PColors.Text),
                        modifier = Modifier.weight(1f)
                    )
                    Text("  :  ", color = PColors.TextWeak)
                    OutlinedTextField(
                        value = sec, onValueChange = { sec = it.take(2) },
                        label = { Text("ss", color = PColors.TextDim, fontSize = 11.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = PColors.Text),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "保持这两个值为空或 0:0 不会排除任何文件。",
                    color = PColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ScanFilter.parseDurationSec(min, sec)) }) { Text("保存", color = PColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = PColors.TextWeak) } },
        containerColor = PColors.SurfaceAlt
    )
}

@Composable
private fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = PColors.Text) },
        text = { Text(body, color = PColors.TextWeak, fontSize = 13.sp, lineHeight = 19.sp) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了", color = PColors.Accent) } },
        containerColor = PColors.SurfaceAlt
    )
}

@Composable
private fun StorageDialog(container: AppContainer, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lines = remember {
        runCatching {
            val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java)
            sm.storageVolumes.mapNotNull { v ->
                val dir = runCatching { v.directory?.absolutePath }.getOrNull()
                if (dir.isNullOrBlank()) null else dir
            }
        }.getOrNull().orEmpty().ifEmpty { listOf("内部存储 · /storage/emulated/0") }
    }
    InfoDialog("存储设备", lines.joinToString("\n"), onDismiss)
}

/* ---------------- 通用行（给 Paparazzi 回归复用） ---------------- */

@Composable
fun SettingsSectionLabel(text: String) {
    Text(
        text,
        color = PColors.Accent, fontSize = 12.sp,
        modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) PColors.Text else PColors.TextDim, fontSize = 14.sp)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = PColors.TextWeak, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = PColors.TextDim, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SettingsToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PColors.Text, fontSize = 14.sp)
            Text(subtitle, color = PColors.TextWeak, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = PColors.Accent)
        )
    }
}

// SectionLabel 的旧名，留一个私有转发避免别处引用
@Composable
private fun SectionLabel(text: String) = SettingsSectionLabel(text)
