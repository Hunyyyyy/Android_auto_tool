package com.autoclicker.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.autoclicker.app.data.FlowStore
import com.autoclicker.app.model.BranchRule
import com.autoclicker.app.model.COMPARE_OPS
import com.autoclicker.app.model.ClickFlow
import com.autoclicker.app.model.DAY_LABELS
import com.autoclicker.app.model.FlowBranch
import com.autoclicker.app.model.Game
import com.autoclicker.app.model.NO_MATCH_SKIP_BRANCH_STEPS
import com.autoclicker.app.model.NO_MATCH_STOP
import com.autoclicker.app.model.isNumeric
import com.autoclicker.app.model.RepeatBlock
import com.autoclicker.app.model.Step
import com.autoclicker.app.model.branchPosition
import com.autoclicker.app.model.daysLabel
import com.autoclicker.app.model.hasDay
import com.autoclicker.app.model.parseStepNumbers
import com.autoclicker.app.model.resolvedRepeats
import com.autoclicker.app.model.toStepNumberText
import com.autoclicker.app.model.toggledDay
import com.autoclicker.app.service.ClickAccessibilityService
import com.autoclicker.app.service.RunState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

@Composable
fun App(onSendToBack: () -> Unit) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val id = editingId
    if (id == null) {
        MainScreen(onEdit = { editingId = it }, onSendToBack = onSendToBack)
    } else {
        BackHandler { editingId = null }
        EditorScreen(flowId = id, onBack = { editingId = null }, onSendToBack = onSendToBack)
    }
}

// =============================================================================
// Màn hình chính: danh sách luồng + checkbox + Start
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onEdit: (String) -> Unit, onSendToBack: () -> Unit) {
    val context = LocalContext.current
    val flows by FlowStore.flows.collectAsState()
    val connected by RunState.connected.collectAsState()
    val running by RunState.running.collectAsState()

    // Cờ connected mất khi app bị kill, nên đọc thêm trạng thái bật thật từ hệ thống.
    var enabledInSettings by remember { mutableStateOf(isServiceEnabledInSettings(context)) }
    LaunchedEffect(connected) {
        if (connected) {
            enabledInSettings = true
            return@LaunchedEffect
        }
        while (true) {
            enabledInSettings = isServiceEnabledInSettings(context)
            delay(2000)
        }
    }

    // Dịch vụ bật trong cài đặt nhưng không kết nối (bị kill): tự tắt/bật lại một lần nếu
    // máy đã cấp WRITE_SECURE_SETTINGS (cấp bằng adb một lần, xem canRestartService).
    var restartTried by remember { mutableStateOf(false) }
    LaunchedEffect(enabledInSettings, connected) {
        if (enabledInSettings && !connected && !restartTried && canRestartService(context)) {
            delay(3000)
            if (!RunState.connected.value) {
                restartTried = true
                restartAccessibilityService(context)
            }
        }
    }

    // Đếm ngược (mặc định 3 giây) dùng chung với bong bóng nổi qua RunState.
    val delayValue by RunState.startDelaySec.collectAsState()
    var delayText by rememberSaveable { mutableStateOf(delayValue.toString()) }
    LaunchedEffect(delayValue) {
        if (delayText.toIntOrNull() != delayValue) delayText = delayValue.toString()
    }
    val games by FlowStore.games.collectAsState()
    val selectedGameId by FlowStore.selectedGameId.collectAsState()
    var showAddGame by remember { mutableStateOf(false) }
    var addFlowToGameId by remember { mutableStateOf<String?>(null) }
    var renamingGame by remember { mutableStateOf<Game?>(null) }
    var deletingGame by remember { mutableStateOf<Game?>(null) }
    var copyingFlow by remember { mutableStateOf<ClickFlow?>(null) }
    var renamingFlow by remember { mutableStateOf<ClickFlow?>(null) }
    var deletingFlow by remember { mutableStateOf<ClickFlow?>(null) }
    var movingFlow by remember { mutableStateOf<ClickFlow?>(null) }

    // Thứ tự chạy = thứ tự trong từng game, chỉ tính các luồng đã tick.
    // remember: không tính lại mỗi lần gõ đếm ngược.
    val orderMap = remember(flows) {
        flows.groupBy { it.gameId }
            .flatMap { (_, list) ->
                list.filter { it.enabled }.mapIndexed { index, flow -> flow.id to index + 1 }
            }
            .toMap()
    }
    // Chỉ các luồng đã tick của game đang chọn (radio) mới chạy.
    val runnable = remember(flows, selectedGameId) {
        flows.any { it.gameId == selectedGameId && it.enabled && it.steps.isNotEmpty() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto Click") },
                // Luôn hiện, dù dịch vụ đang bật hay tắt, để tự tắt/bật khi cần.
                actions = {
                    TextButton(onClick = { openAccessibilityPage(context) }) { Text("⚙ Trợ năng") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAddGame = true }) {
                Text("＋ Thêm game")
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = { raw ->
                            delayText = raw.filter(Char::isDigit).take(2)
                            delayText.toIntOrNull()?.let { RunState.startDelaySec.value = it }
                        },
                        label = { Text("Đếm ngược trước khi chạy (giây)", maxLines = 1) },
                        singleLine = true,
                        keyboardOptions = numberKeyboard,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (running) {
                        Button(
                            onClick = { ClickAccessibilityService.instance?.stopRun() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("■ DỪNG") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = connected && runnable,
                                onClick = {
                                    val wait = delayText.toIntOrNull() ?: 3
                                    val service = ClickAccessibilityService.instance
                                    if (service != null) {
                                        service.startRun(wait)
                                        onSendToBack()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("▶ START") }
                            OutlinedButton(
                                enabled = connected,
                                onClick = {
                                    val service = ClickAccessibilityService.instance
                                    if (service != null) {
                                        service.showLauncher()
                                        onSendToBack()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("🫧 Bong bóng") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!connected) {
                item {
                    if (enabledInSettings) ServiceConnectingCard(context) else ServiceCard(context)
                }
            }
            games.forEach { game ->
                val gameFlows = flows.filter { it.gameId == game.id }
                item(key = "game-${game.id}") {
                    GameHeader(
                        game = game,
                        selected = game.id == selectedGameId,
                        onSelect = { FlowStore.selectGame(game.id) },
                        onAddFlow = { addFlowToGameId = game.id },
                        onRename = { renamingGame = game },
                        onDelete = { deletingGame = game }
                    )
                }
                if (gameFlows.isEmpty()) {
                    item(key = "empty-${game.id}") {
                        Text(
                            "Chưa có luồng. Bấm “＋ Luồng” ở trên.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 24.dp)
                        )
                    }
                }
                items(gameFlows, key = { it.id }) { flow ->
                    Box(Modifier.padding(start = 16.dp)) {
                        FlowItem(
                            flow = flow,
                            order = orderMap[flow.id],
                            onEdit = { onEdit(flow.id) },
                            onCopy = { copyingFlow = flow },
                            onRename = { renamingFlow = flow },
                            onChangeGame = { movingFlow = flow },
                            onDelete = { deletingFlow = flow }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAddGame) {
        NameDialog(
            title = "Game mới",
            label = "Tên game",
            onDismiss = { showAddGame = false },
            onOk = { name ->
                showAddGame = false
                FlowStore.selectGame(FlowStore.addGame(name))
            }
        )
    }
    addFlowToGameId?.let { gameId ->
        NameDialog(
            title = "Luồng mới",
            label = "Tên luồng",
            onDismiss = { addFlowToGameId = null },
            onOk = { name ->
                addFlowToGameId = null
                onEdit(FlowStore.addFlow(name, gameId))
            }
        )
    }
    renamingGame?.let { game ->
        NameDialog(
            title = "Đổi tên game",
            label = "Tên game",
            initial = game.name,
            onDismiss = { renamingGame = null },
            onOk = { name ->
                renamingGame = null
                FlowStore.renameGame(game.id, name)
            }
        )
    }
    copyingFlow?.let { flow ->
        CopyFlowDialog(
            flow = flow,
            games = games,
            onDismiss = { copyingFlow = null },
            onCopy = { gameIds ->
                copyingFlow = null
                val count = FlowStore.copyFlow(flow.id, gameIds)
                Toast.makeText(context, "Đã sao chép sang $count game", Toast.LENGTH_SHORT).show()
            }
        )
    }
    renamingFlow?.let { flow ->
        NameDialog(
            title = "Đổi tên luồng",
            label = "Tên luồng",
            initial = flow.name,
            onDismiss = { renamingFlow = null },
            onOk = { name ->
                renamingFlow = null
                FlowStore.renameFlow(flow.id, name)
            }
        )
    }
    movingFlow?.let { flow ->
        val others = games.filter { it.id != flow.gameId }
        AlertDialog(
            onDismissRequest = { movingFlow = null },
            title = { Text("Chuyển “${flow.name}” sang game") },
            text = {
                Column {
                    if (others.isEmpty()) Text("Chưa có game nào khác. Hãy thêm game trước.")
                    others.forEach { game ->
                        TextButton(
                            onClick = {
                                movingFlow = null
                                FlowStore.changeGame(flow.id, game.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(game.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { movingFlow = null }) { Text("Huỷ") } }
        )
    }
    deletingFlow?.let { flow ->
        AlertDialog(
            onDismissRequest = { deletingFlow = null },
            title = { Text("Xoá luồng?") },
            text = { Text("Luồng “${flow.name}” cùng ${flow.steps.size} điểm sẽ bị xoá, không thể hoàn tác.") },
            confirmButton = {
                TextButton(onClick = {
                    deletingFlow = null
                    FlowStore.deleteFlow(flow.id)
                }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { deletingFlow = null }) { Text("Huỷ") } }
        )
    }
    deletingGame?.let { game ->
        AlertDialog(
            onDismissRequest = { deletingGame = null },
            title = { Text("Xoá game?") },
            text = { Text("Game “${game.name}” và toàn bộ luồng trong game sẽ bị xoá.") },
            confirmButton = {
                TextButton(onClick = {
                    deletingGame = null
                    FlowStore.deleteGame(game.id)
                }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { deletingGame = null }) { Text("Huỷ") } }
        )
    }
}

/** Chọn một hoặc nhiều game đích để sao chép luồng (kèm toàn bộ điểm) sang. */
@Composable
private fun CopyFlowDialog(
    flow: ClickFlow,
    games: List<Game>,
    onDismiss: () -> Unit,
    onCopy: (List<String>) -> Unit
) {
    var chosen by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sao chép “${flow.name}”") },
        text = {
            Column {
                Text("Chọn game muốn sao chép luồng sang:")
                games.forEach { game ->
                    val isCurrent = game.id == flow.gameId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chosen = if (game.id in chosen) chosen - game.id else chosen + game.id
                            }
                    ) {
                        Checkbox(
                            checked = game.id in chosen,
                            onCheckedChange = { checked ->
                                chosen = if (checked) chosen + game.id else chosen - game.id
                            }
                        )
                        Text(if (isCurrent) "${game.name} (game hiện tại)" else game.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onCopy(chosen.toList()) }) {
                Text("Sao chép")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}

/** Tiêu đề game: radio chọn game sẽ chạy, thêm luồng, đổi tên, xoá. */
@Composable
private fun GameHeader(
    game: Game,
    selected: Boolean,
    onSelect: () -> Unit,
    onAddFlow: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(
                game.name,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSelect)
            )
            TextButton(onClick = onAddFlow) { Text("＋ Luồng") }
            TextButton(onClick = onRename) { Text("Sửa") }
            TextButton(onClick = onDelete) { Text("Xoá") }
        }
    }
}

@Composable
private fun FlowItem(
    flow: ClickFlow,
    order: Int?,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onChangeGame: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = flow.enabled,
                    onCheckedChange = { checked ->
                        FlowStore.updateFlow(flow.id) { it.copy(enabled = checked) }
                    }
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onEdit)
                ) {
                    Text(flow.name, fontWeight = FontWeight.Bold)
                    val orderText = if (order != null) " • chạy thứ $order" else ""
                    Text(
                        "${flow.steps.size} bước • lặp ${flow.repeatCount} lần$orderText",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onEdit) { Text("Sửa") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Spacer(Modifier.width(8.dp))
                // Bộ −/＋ nhẹ thay cho ô nhập: danh sách dài cuộn mượt hơn (ô nhập đầy đủ ở màn hình sửa luồng).
                RepeatStepper(flow)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { FlowStore.moveFlow(flow.id, -1) }) { Text("▲") }
                TextButton(onClick = { FlowStore.moveFlow(flow.id, +1) }) { Text("▼") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onRename) { Text("✎ Đổi tên") }
                TextButton(onClick = onChangeGame) { Text("⇄ Đổi game") }
                TextButton(onClick = onCopy) { Text("⧉ Sao chép") }
                TextButton(onClick = onDelete) { Text("🗑 Xoá") }
            }
        }
    }
}

/** Chỉnh số lần lặp của luồng bằng nút −/＋ (tối thiểu 1). */
@Composable
private fun RepeatStepper(flow: ClickFlow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Lặp", style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = {
                FlowStore.updateFlow(flow.id) { it.copy(repeatCount = (it.repeatCount - 1).coerceAtLeast(1)) }
            }
        ) { Text("−") }
        Text("${flow.repeatCount}", fontWeight = FontWeight.Bold)
        TextButton(
            onClick = {
                FlowStore.updateFlow(flow.id) { it.copy(repeatCount = (it.repeatCount + 1).coerceAtMost(9999)) }
            }
        ) { Text("＋") }
    }
}

/** Hàng 7 nút T2…CN bật/tắt từng ngày trong tuần. */
@Composable
private fun DayChips(mask: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        DAY_LABELS.forEachIndexed { index, label ->
            val on = mask.hasDay(index)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onChange(mask.toggledDay(index)) }
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    label,
                    fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Rẽ nhánh theo chữ: sau bước N, thấy chữ nào thì các bước phía sau chỉ chạy những bước của nhánh
 * đó. Khung đọc chữ được chỉnh bằng khung viền trắng trực tiếp trên màn hình.
 */
@Composable
private fun BranchesSection(flow: ClickFlow, onSendToBack: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Rẽ nhánh theo chữ", fontWeight = FontWeight.Bold)
            Text(
                "Ví dụ: sau bước 3, thấy “xxx” thì chạy bước 4-8, thấy “yyy” thì chạy bước 5-8. " +
                    "Gõ danh sách bước dạng 4-8 hoặc 4,8,9.",
                style = MaterialTheme.typography.bodySmall
            )
            flow.branches.forEach { branch ->
                key(branch.id) { BranchRow(flow, branch, onSendToBack) }
            }
            OutlinedButton(
                onClick = {
                    val m = context.resources.displayMetrics
                    FlowStore.upsertBranch(
                        flow.id,
                        FlowBranch(
                            afterStepId = flow.steps.firstOrNull()?.id ?: "",
                            x = m.widthPixels / 2,
                            y = m.heightPixels / 2,
                            rules = listOf(BranchRule())
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("＋ Thêm rẽ nhánh") }
        }
    }
}

@Composable
private fun BranchRow(flow: ClickFlow, branch: FlowBranch, onSendToBack: () -> Unit) {
    val context = LocalContext.current
    fun edit(change: (FlowBranch) -> FlowBranch) = FlowStore.upsertBranch(flow.id, change(branch))
    val steps = flow.steps
    val position = flow.branchPosition(branch)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField(
                "Xét sau bước số (0 = đầu luồng)",
                position.coerceAtLeast(0).toLong(),
                Modifier.weight(1f)
            ) { v ->
                val p = v.toInt().coerceIn(0, steps.size)
                edit { it.copy(afterStepId = if (p == 0) "" else steps[p - 1].id) }
            }
            NumberField("Chờ tối đa (ms)", branch.waitMs, Modifier.weight(1f)) { v ->
                edit { it.copy(waitMs = v) }
            }
            NumberField("Xác nhận (lần)", branch.confirmReads.toLong(), Modifier.weight(1f)) { v ->
                edit { it.copy(confirmReads = v.toInt().coerceAtLeast(1)) }
            }
        }
        if (position < 0) {
            Text(
                "Bước gốc của rẽ nhánh đã bị xoá — hãy đặt lại “Xét sau bước số”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        branch.rules.forEach { rule ->
            key(rule.id) {
                var text by remember(rule.id) { mutableStateOf(rule.text) }
                var stepsText by remember(rule.id) {
                    mutableStateOf(
                        rule.stepIds
                            .mapNotNull { id -> steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) }
                            .toStepNumberText()
                    )
                }
                fun editRule(change: (BranchRule) -> BranchRule) = edit { b ->
                    b.copy(rules = b.rules.map { if (it.id == rule.id) change(it) else it })
                }
                val kinds = listOf("") + COMPARE_OPS
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Bấm để đổi loại: Chữ -> Số >= -> Số <= -> Số > -> Số < -> Số = -> Chữ.
                    OutlinedButton(
                        onClick = {
                            val next = kinds[(kinds.indexOf(rule.compare) + 1) % kinds.size]
                            editRule { it.copy(compare = next) }
                        }
                    ) { Text(if (rule.isNumeric()) "Số ${rule.compare}" else "Chữ") }
                    if (rule.isNumeric()) {
                        NumberField("Ngưỡng", rule.threshold.toLong(), Modifier.weight(1f)) { v ->
                            editRule { it.copy(threshold = v.toInt()) }
                        }
                    } else {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { value ->
                                text = value
                                editRule { it.copy(text = value) }
                            },
                            label = { Text("Thấy chữ", maxLines = 1) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TextButton(
                        onClick = {
                            edit { b -> b.copy(rules = b.rules.filterNot { it.id == rule.id }) }
                        }
                    ) { Text("✕") }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = { value ->
                            stepsText = value
                            val ids = parseStepNumbers(value, steps.size).map { steps[it - 1].id }
                            edit { b ->
                                b.copy(rules = b.rules.map { if (it.id == rule.id) it.copy(stepIds = ids) else it })
                            }
                        },
                        label = { Text("Chạy bước (vd 4-8)", maxLines = 1) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    NumberField(
                        "Dừng luồng sau N lần (0 = tắt)",
                        rule.finishAfter.toLong(),
                        Modifier.weight(1f)
                    ) { v -> editRule { it.copy(finishAfter = v.toInt().coerceAtLeast(0)) } }
                }
                // Nhảy tới bước: nhánh đúng thì bỏ luôn các bước đứng trước bước đầu của nhánh.
                OutlinedButton(
                    onClick = { editRule { it.copy(jump = !it.jump) } }
                ) { Text(if (rule.jump) "↷ Nhảy tới bước đã chọn: BẬT" else "↷ Nhảy tới bước đã chọn: tắt") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!rule.isNumeric()) {
                        // Gần đúng: chịu OCR đọc lệch. Đúng chữ: khung phải chứa nguyên cụm chữ đã nhập.
                        OutlinedButton(
                            onClick = { editRule { it.copy(exactMatch = !it.exactMatch) } }
                        ) { Text(if (rule.exactMatch) "= Đúng chữ" else "≈ Gần đúng") }
                    }
                    OutlinedButton(
                        onClick = {
                            val service = ClickAccessibilityService.instance
                            if (service == null) {
                                Toast.makeText(context, "Hãy bật dịch vụ Trợ năng trước", Toast.LENGTH_LONG).show()
                            } else {
                                service.showRegionEditor(flow.id, branch.id, rule.id)
                                onSendToBack()
                            }
                        }
                    ) { Text(if (rule.ownRegion) "▢ Khung riêng" else "▢ Khung chung → riêng") }
                    if (rule.ownRegion) {
                        TextButton(onClick = { editRule { it.copy(ownRegion = false) } }) { Text("↺ Dùng khung chung") }
                    }
                }
            }
        }
        OutlinedButton(onClick = { edit { it.copy(rules = it.rules + BranchRule()) } }) {
            Text("＋ Thêm nhánh")
        }

        // Khi không nhánh nào đúng: bấm để đổi giữa 3 cách xử lý.
        OutlinedButton(
            onClick = { edit { it.copy(noMatchAction = (it.noMatchAction + 1) % 3) } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when (branch.noMatchAction) {
                    NO_MATCH_SKIP_BRANCH_STEPS -> "Không nhánh nào đúng: bỏ qua các bước của nhánh"
                    NO_MATCH_STOP -> "Không nhánh nào đúng: dừng luồng"
                    else -> "Không nhánh nào đúng: chạy tiếp tất cả"
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val service = ClickAccessibilityService.instance
                    if (service == null) {
                        Toast.makeText(context, "Hãy bật dịch vụ Trợ năng trước", Toast.LENGTH_LONG).show()
                    } else {
                        service.showRegionEditor(flow.id, branch.id)
                        onSendToBack()
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("▢ Khung chung / Thử") }
            OutlinedButton(onClick = { FlowStore.removeBranch(flow.id, branch.id) }) { Text("Xoá") }
        }
    }
}

/** Ô nhập số lần lặp lại của một luồng (tối thiểu 1). */
@Composable
private fun RepeatField(flow: ClickFlow, modifier: Modifier = Modifier) {
    var text by remember(flow.id) { mutableStateOf(flow.repeatCount.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(4)
            val n = digits.toIntOrNull()
            if (n == null) {
                text = digits
            } else {
                text = if (n < 1) "1" else digits
                FlowStore.updateFlow(flow.id) { it.copy(repeatCount = n.coerceAtLeast(1)) }
            }
        },
        label = { Text("Lặp (lần)", maxLines = 1) },
        singleLine = true,
        keyboardOptions = numberKeyboard,
        modifier = modifier
    )
}

/** true nếu dịch vụ Trợ năng của app đang nằm trong danh sách đã bật của hệ thống. */
private fun isServiceEnabledInSettings(context: Context): Boolean {
    val self = ComponentName(context, ClickAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
}

/**
 * Chỉ true khi đã chạy một lần: adb shell pm grant com.autoclicker.app android.permission.WRITE_SECURE_SETTINGS
 */
private fun canRestartService(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED

/** Tắt rồi bật lại dịch vụ trong danh sách Trợ năng để hệ thống bind lại; luôn khôi phục danh sách. */
private suspend fun restartAccessibilityService(context: Context) {
    val resolver = context.contentResolver
    val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    val self = ComponentName(context, ClickAccessibilityService::class.java)
    val original = Settings.Secure.getString(resolver, key) ?: return
    val others = original.split(':').filter {
        it.isNotBlank() && ComponentName.unflattenFromString(it) != self
    }
    try {
        Settings.Secure.putString(resolver, key, others.joinToString(":"))
        delay(500)
    } catch (_: SecurityException) {
        return
    } finally {
        withContext(NonCancellable) {
            try {
                Settings.Secure.putString(resolver, key, original)
            } catch (_: SecurityException) {
            }
        }
    }
}

/**
 * Mở thẳng trang riêng của Auto Click trong cài đặt Trợ năng, không được thì mở danh sách chung.
 * Dùng chuỗi action vì hằng số Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS không có ở compileSdk 34.
 */
private fun openAccessibilityPage(context: Context) {
    val self = ComponentName(context, ClickAccessibilityService::class.java)
    try {
        context.startActivity(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, self.flattenToString())
        )
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

/** Dịch vụ đã bật trong cài đặt nhưng hệ thống không bind lại (thường sau khi app bị kill). */
@Composable
private fun ServiceConnectingCard(context: Context) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Dịch vụ Trợ năng chưa hoạt động", fontWeight = FontWeight.Bold)
            Text(
                "Dịch vụ vẫn đang bật nhưng hệ thống đã ngắt kết nối (thường do app bị kill " +
                    "hoặc vừa cài lại). Bấm nút bên dưới, gạt TẮT rồi BẬT lại Auto Click là dùng được."
            )
            Button(onClick = { openAccessibilityPage(context) }) {
                Text("Mở trang Auto Click để tắt/bật lại")
            }
        }
    }
}

@Composable
private fun ServiceCard(context: Context) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Chưa bật dịch vụ Trợ năng", fontWeight = FontWeight.Bold)
            Text(
                "Cài đặt → Trợ năng → Ứng dụng đã tải xuống → Auto Click → Bật.\n" +
                    "Nếu máy báo “Cài đặt bị hạn chế” (Android 13+): Cài đặt → Ứng dụng → " +
                    "Auto Click → dấu ⋮ → Cho phép cài đặt bị hạn chế, rồi bật lại."
            )
            Button(onClick = { openAccessibilityPage(context) }) { Text("Mở trang Auto Click") }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onOk: (String) -> Unit,
    initial: String = ""
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onOk(name.trim()) }) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}

// =============================================================================
// Màn hình sửa luồng
// =============================================================================

@Composable
fun EditorScreen(flowId: String, onBack: () -> Unit, onSendToBack: () -> Unit) {
    val flows by FlowStore.flows.collectAsState()
    val flow = flows.firstOrNull { it.id == flowId }
    if (flow == null) {
        LaunchedEffect(Unit) { onBack() }
    } else {
        EditorContent(flow, onBack, onSendToBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(flow: ClickFlow, onBack: () -> Unit, onSendToBack: () -> Unit) {
    val context = LocalContext.current
    var name by remember(flow.id) { mutableStateOf(flow.name) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sửa luồng") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Quay lại") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { text ->
                        name = text
                        if (text.isNotBlank()) {
                            FlowStore.updateFlow(flow.id) { it.copy(name = text) }
                        }
                    },
                    label = { Text("Tên luồng") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                RepeatField(flow = flow, modifier = Modifier.fillMaxWidth())
            }
            item { RepeatBlocksSection(flow) }
            item { BranchesSection(flow, onSendToBack) }
            item {
                Button(
                    onClick = {
                        val service = ClickAccessibilityService.instance
                        if (service == null) {
                            Toast.makeText(
                                context,
                                "Hãy bật dịch vụ Trợ năng trước",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            service.showPositionEditor(flow.id)
                            onSendToBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📍 Đặt / chỉnh vị trí click trên màn hình") }
            }
            if (flow.steps.isEmpty()) {
                item {
                    Text(
                        "Chưa có bước nào. Bấm nút phía trên, mở game, bấm “＋ Điểm” " +
                            "(click), “＋ Giữ” (giữ ngón tay) hoặc “＋ Vuốt” (lướt màn hình), " +
                            "kéo số đến đúng vị trí, " +
                            "chạm vào số để cài đặt, rồi bấm ✔ Xong."
                    )
                }
            }
            itemsIndexed(flow.steps, key = { _, step -> step.id }) { index, step ->
                StepCard(
                    flowId = flow.id,
                    index = index,
                    step = step,
                    total = flow.steps.size
                )
            }
            item {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Xoá luồng này") }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xoá luồng?") },
            text = { Text("Luồng “${flow.name}” và toàn bộ bước click sẽ bị xoá.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    FlowStore.deleteFlow(flow.id)
                    onBack()
                }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Huỷ") } }
        )
    }
}

/** Nhóm lặp: chạy các bước từ A đến B lặp n lần rồi mới sang bước kế tiếp. */
@Composable
private fun RepeatBlocksSection(flow: ClickFlow) {
    val resolved = flow.resolvedRepeats()
    val validIds = resolved.map { it.block.id }.toSet()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Nhóm lặp trong luồng", fontWeight = FontWeight.Bold)
            Text(
                "Ví dụ bước 1–4 lặp 3 lần rồi mới sang bước 5, 6.",
                style = MaterialTheme.typography.bodySmall
            )
            if (flow.repeats.isEmpty()) {
                Text("Chưa có nhóm lặp nào.", style = MaterialTheme.typography.bodySmall)
            }
            flow.repeats.forEach { block ->
                key(block.id) { RepeatBlockRow(flow, block, valid = block.id in validIds) }
            }
            OutlinedButton(
                enabled = flow.steps.isNotEmpty(),
                onClick = {
                    // Mặc định: 2 bước ngay sau nhóm cuối cùng (hoặc từ bước 1).
                    val last = flow.steps.size - 1
                    val start = ((resolved.lastOrNull()?.end ?: -1) + 1).coerceAtMost(last)
                    val end = (start + 1).coerceAtMost(last)
                    FlowStore.upsertRepeat(
                        flow.id,
                        RepeatBlock(
                            startStepId = flow.steps[start].id,
                            endStepId = flow.steps[end].id,
                            count = 2
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("＋ Thêm nhóm lặp") }
        }
    }
}

@Composable
private fun RepeatBlockRow(flow: ClickFlow, block: RepeatBlock, valid: Boolean) {
    val size = flow.steps.size
    val from = flow.steps.indexOfFirst { it.id == block.startStepId } + 1
    val to = flow.steps.indexOfFirst { it.id == block.endStepId } + 1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NumberField("Từ bước", from.coerceAtLeast(1).toLong(), Modifier.weight(1f)) { v ->
                if (size > 0) {
                    val i = v.toInt().coerceIn(1, size)
                    FlowStore.upsertRepeat(flow.id, block.copy(startStepId = flow.steps[i - 1].id))
                }
            }
            NumberField("Đến bước", to.coerceAtLeast(1).toLong(), Modifier.weight(1f)) { v ->
                if (size > 0) {
                    val i = v.toInt().coerceIn(1, size)
                    FlowStore.upsertRepeat(flow.id, block.copy(endStepId = flow.steps[i - 1].id))
                }
            }
            NumberField("Lặp (lần)", block.count.toLong(), Modifier.weight(1f)) { v ->
                FlowStore.upsertRepeat(flow.id, block.copy(count = v.toInt().coerceAtLeast(1)))
            }
            TextButton(onClick = { FlowStore.removeRepeat(flow.id, block.id) }) { Text("Xoá") }
        }
        if (!valid) {
            Text(
                "Nhóm không hợp lệ (đến bước nhỏ hơn từ bước, hoặc chồng lên nhóm khác) — sẽ bị bỏ qua khi chạy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StepCard(flowId: String, index: Int, step: Step, total: Int) {
    fun edit(transform: (Step) -> Step) = FlowStore.updateStep(flowId, step.id, transform)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val kind = when {
                    step.isSwipe -> "Điểm vuốt"
                    step.isHold -> "Điểm giữ"
                    else -> "Bước"
                }
                val end = if (step.isSwipe) " → (${step.endX}, ${step.endY})" else ""
                Text(
                    "$kind ${index + 1}   (x=${step.x}, y=${step.y})$end",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = index > 0,
                    onClick = { FlowStore.moveStep(flowId, step.id, -1) }
                ) { Text("▲") }
                TextButton(
                    enabled = index < total - 1,
                    onClick = { FlowStore.moveStep(flowId, step.id, +1) }
                ) { Text("▼") }
                // Chèn một điểm click mới ngay sau bước này; các bước sau tự dồn số.
                TextButton(
                    onClick = {
                        FlowStore.insertStepAfter(
                            flowId,
                            step.id,
                            Step(x = step.x + 40, y = step.y + 40)
                        )
                    }
                ) { Text("＋ Chèn") }
                TextButton(onClick = { FlowStore.deleteStep(flowId, step.id) }) { Text("Xoá") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("Chờ trước (ms)", step.delayMs, Modifier.weight(1f)) { v ->
                    edit { it.copy(delayMs = v) }
                }
                NumberField("Số lần click", step.clicks.toLong(), Modifier.weight(1f)) { v ->
                    edit { it.copy(clicks = v.toInt().coerceAtLeast(1)) }
                }
                NumberField("Cách nhau (ms)", step.repeatIntervalMs, Modifier.weight(1f)) { v ->
                    edit { it.copy(repeatIntervalMs = v) }
                }
            }

            if (step.isHold) {
                NumberField("Giữ trong (ms)", step.holdMs, Modifier.fillMaxWidth()) { v ->
                    edit { it.copy(holdMs = v.coerceAtLeast(1)) }
                }
            }

            if (step.isSwipe) {
                NumberField("Thời gian vuốt (ms)", step.swipeMs, Modifier.fillMaxWidth()) { v ->
                    edit { it.copy(swipeMs = v.coerceAtLeast(1)) }
                }
            }

            if (index > 0) {
                // Nút sau chỉ kích hoạt khi nút trước (bước ngay trước) đã thực sự chạy.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = step.requirePrevious,
                        onCheckedChange = { checked -> edit { it.copy(requirePrevious = checked) } }
                    )
                    Text("Chỉ chạy khi bước trước đã chạy (nút trước kích hoạt)")
                }
            }

            Text("Chạy vào các ngày: ${step.days.daysLabel()}", style = MaterialTheme.typography.bodySmall)
            DayChips(step.days) { mask -> edit { it.copy(days = mask) } }

            var waitText by remember { mutableStateOf(step.waitText) }
            OutlinedTextField(
                value = waitText,
                onValueChange = { text ->
                    waitText = text
                    edit { it.copy(waitText = text) }
                },
                label = { Text("Chờ chữ hiện ra rồi mới click (bỏ trống = click luôn)") },
                modifier = Modifier.fillMaxWidth()
            )

            if (waitText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = step.exactMatch,
                        onCheckedChange = { checked -> edit { it.copy(exactMatch = checked) } }
                    )
                    Text("Khớp chính xác (mặc định: chỉ cần chứa chữ)")
                }
                // Nút có điều kiện: thấy chữ thì bấm, không thấy thì bỏ qua ngay (không chờ).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = step.skipIfAbsent,
                        onCheckedChange = { checked -> edit { it.copy(skipIfAbsent = checked) } }
                    )
                    Text("Không thấy chữ thì bỏ qua bước (không chờ)")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Timeout (ms, 0=∞)", step.timeoutMs, Modifier.weight(1f)) { v ->
                        edit { it.copy(timeoutMs = v) }
                    }
                    NumberField("Vùng đọc rộng", step.regionW.toLong(), Modifier.weight(1f)) { v ->
                        edit { it.copy(regionW = v.toInt().coerceAtLeast(20)) }
                    }
                    NumberField("Vùng đọc cao", step.regionH.toLong(), Modifier.weight(1f)) { v ->
                        edit { it.copy(regionH = v.toInt().coerceAtLeast(20)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
    onChange: (Long) -> Unit
) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(9)
            text = digits
            digits.toLongOrNull()?.let(onChange)
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = numberKeyboard,
        modifier = modifier
    )
}
