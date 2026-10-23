package com.autoclicker.app.data

import android.content.Context
import android.util.Log
import com.autoclicker.app.model.ALL_DAYS
import com.autoclicker.app.model.ClickFlow
import com.autoclicker.app.model.BranchRule
import com.autoclicker.app.model.FlowBranch
import com.autoclicker.app.model.Game
import com.autoclicker.app.model.NO_MATCH_CONTINUE
import com.autoclicker.app.model.NO_MATCH_STOP
import com.autoclicker.app.model.RepeatBlock
import com.autoclicker.app.model.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Lưu game + luồng vào một file JSON (filesDir/flows.json).
 * UI và service dùng chung object này trong cùng một process.
 */
object FlowStore {

    private const val TAG = "FlowStore"
    private const val SAVE_DELAY_MS = 300L
    private const val DEFAULT_GAME_NAME = "Game 1"

    /** true nếu file dữ liệu bị hỏng lúc đọc (bản sao lưu nằm ở flows.json.bak). */
    @Volatile
    var recoveredFromCorruption = false
        private set

    private val _flows = MutableStateFlow<List<ClickFlow>>(emptyList())
    val flows: StateFlow<List<ClickFlow>> = _flows.asStateFlow()

    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games.asStateFlow()

    /** Game đang được chọn (radio): chỉ các luồng đã tick của game này mới chạy. */
    private val _selectedGameId = MutableStateFlow("")
    val selectedGameId: StateFlow<String> = _selectedGameId.asStateFlow()

    private var file: File? = null

    /** Ghi file trên luồng nền, gom các thay đổi liên tiếp (gõ phím, kéo) thành một lần ghi. */
    private val saver = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "flow-save").apply { isDaemon = true }
    }
    private var pendingSave: ScheduledFuture<*>? = null
    private val saveLock = Any()

    /** Gọi nhiều lần cũng an toàn. */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "flows.json")
        file = f
        load(f)
    }

    fun get(id: String): ClickFlow? = _flows.value.firstOrNull { it.id == id }

    fun flowsOf(gameId: String): List<ClickFlow> = _flows.value.filter { it.gameId == gameId }

    fun update(transform: (List<ClickFlow>) -> List<ClickFlow>) {
        synchronized(this) {
            _flows.value = transform(_flows.value)
            scheduleSave()
        }
    }

    /** Ghi ngay các thay đổi đang chờ (gọi khi app xuống nền hoặc dịch vụ dừng). */
    fun flush() {
        synchronized(this) {
            pendingSave?.cancel(false)
            pendingSave = null
        }
        saveNow()
    }

    private fun scheduleSave() {
        pendingSave?.cancel(false)
        pendingSave = saver.schedule({ saveNow() }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun saveNow() {
        val f = file ?: return
        val games = _games.value
        val selected = _selectedGameId.value
        val flows = _flows.value
        synchronized(saveLock) { save(f, games, selected, flows) }
    }

    // ---------- game ----------

    fun addGame(name: String): String {
        val game = Game(name = name)
        synchronized(this) {
            _games.value = _games.value + game
            if (_selectedGameId.value.isBlank()) _selectedGameId.value = game.id
            scheduleSave()
        }
        return game.id
    }

    fun renameGame(id: String, name: String) {
        synchronized(this) {
            _games.value = _games.value.map { if (it.id == id) it.copy(name = name) else it }
            scheduleSave()
        }
    }

    fun selectGame(id: String) {
        synchronized(this) {
            if (_games.value.none { it.id == id }) return
            _selectedGameId.value = id
            scheduleSave()
        }
    }

    /** Xoá game cùng toàn bộ luồng trong game đó. Luôn còn ít nhất một game. */
    fun deleteGame(id: String) {
        synchronized(this) {
            _games.value = _games.value.filterNot { it.id == id }
            _flows.value = _flows.value.filterNot { it.gameId == id }
            ensureGames()
            scheduleSave()
        }
    }

    /** Đảm bảo luôn có game và game được chọn hợp lệ. Gọi trong synchronized. */
    private fun ensureGames() {
        if (_games.value.isEmpty()) _games.value = listOf(Game(name = DEFAULT_GAME_NAME))
        if (_games.value.none { it.id == _selectedGameId.value }) {
            _selectedGameId.value = _games.value.first().id
        }
    }

    // ---------- luồng ----------

    fun updateFlow(id: String, transform: (ClickFlow) -> ClickFlow) {
        update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun addFlow(name: String, gameId: String): String {
        val flow = ClickFlow(name = name, gameId = gameId)
        update { it + flow }
        return flow.id
    }

    fun renameFlow(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        updateFlow(id) { it.copy(name = trimmed) }
    }

    /** Chuyển luồng sang game khác (xuống cuối danh sách của game đó), giữ nguyên toàn bộ điểm. */
    fun changeGame(flowId: String, gameId: String) {
        if (_games.value.none { it.id == gameId }) return
        update { list ->
            val flow = list.firstOrNull { it.id == flowId } ?: return@update list
            if (flow.gameId == gameId) list
            else list.filterNot { it.id == flowId } + flow.copy(gameId = gameId)
        }
    }

    fun deleteFlow(id: String) {
        update { list -> list.filterNot { it.id == id } }
    }

    /**
     * Sao chép luồng (kèm toàn bộ điểm) sang các game trong [targetGameIds].
     * Bản sao có id mới, chưa tick; sao chép trong cùng game thì thêm hậu tố "(bản sao)".
     * Trả về số bản sao đã tạo.
     */
    fun copyFlow(flowId: String, targetGameIds: List<String>): Int {
        var created = 0
        update { list ->
            val source = list.firstOrNull { it.id == flowId } ?: return@update list
            val validGameIds = _games.value.map { it.id }.toSet()
            val copies = targetGameIds.distinct().filter { it in validGameIds }.map { gameId ->
                // Id mới cho bước; nhóm lặp / rẽ nhánh trỏ sang id bước mới tương ứng.
                val newSteps = source.steps.map { it.copy(id = UUID.randomUUID().toString()) }
                val idMap = source.steps.indices.associate { source.steps[it].id to newSteps[it].id }
                source.copy(
                    id = UUID.randomUUID().toString(),
                    gameId = gameId,
                    name = if (gameId == source.gameId) "${source.name} (bản sao)" else source.name,
                    enabled = false,
                    steps = newSteps,
                    branches = source.branches.map { branch ->
                        branch.copy(
                            id = UUID.randomUUID().toString(),
                            afterStepId = if (branch.afterStepId.isBlank()) "" else idMap[branch.afterStepId] ?: "",
                            rules = branch.rules.map { rule ->
                                rule.copy(
                                    id = UUID.randomUUID().toString(),
                                    stepIds = rule.stepIds.mapNotNull { idMap[it] }
                                )
                            }
                        )
                    },
                    repeats = source.repeats.mapNotNull { block ->
                        val start = idMap[block.startStepId] ?: return@mapNotNull null
                        val end = idMap[block.endStepId] ?: return@mapNotNull null
                        block.copy(
                            id = UUID.randomUUID().toString(),
                            startStepId = start,
                            endStepId = end
                        )
                    }
                )
            }
            created = copies.size
            list + copies
        }
        return created
    }

    /** Đổi thứ tự trong phạm vi cùng một game. */
    fun moveFlow(id: String, delta: Int) {
        update { list ->
            val flow = list.firstOrNull { it.id == id } ?: return@update list
            val sameGame = list.indices.filter { list[it].gameId == flow.gameId }
            val pos = sameGame.indexOfFirst { list[it].id == id }
            val targetPos = pos + delta
            if (pos < 0 || targetPos !in sameGame.indices) {
                list
            } else {
                list.toMutableList().also { Collections.swap(it, sameGame[pos], sameGame[targetPos]) }
            }
        }
    }

    fun updateStep(flowId: String, stepId: String, transform: (Step) -> Step) {
        updateFlow(flowId) { f ->
            f.copy(steps = f.steps.map { if (it.id == stepId) transform(it) else it })
        }
    }

    /**
     * Chèn [step] ngay sau bước [afterStepId] (không thấy bước đó thì thêm vào cuối). Các bước phía sau
     * tự dồn số; nhóm lặp, rẽ nhánh và danh sách bước của nhánh trỏ theo id nên vẫn đúng bước cũ.
     */
    fun insertStepAfter(flowId: String, afterStepId: String, step: Step) {
        updateFlow(flowId) { f ->
            val index = f.steps.indexOfFirst { it.id == afterStepId }
            val at = if (index < 0) f.steps.size else index + 1
            f.copy(steps = f.steps.take(at) + step + f.steps.drop(at))
        }
    }

    fun moveStep(flowId: String, stepId: String, delta: Int) {
        updateFlow(flowId) { f ->
            f.copy(steps = swapped(f.steps, f.steps.indexOfFirst { it.id == stepId }, delta))
        }
    }

    /** Xoá bước; nhóm lặp bị ảnh hưởng thì co lại, hết bước thì bỏ nhóm. */
    fun deleteStep(flowId: String, stepId: String) {
        updateFlow(flowId) { f ->
            val index = f.steps.indexOfFirst { it.id == stepId }
            val repeats = f.repeats.mapNotNull { block ->
                var start = block.startStepId
                var end = block.endStepId
                if (start == stepId) start = f.steps.getOrNull(index + 1)?.id ?: return@mapNotNull null
                if (end == stepId) end = f.steps.getOrNull(index - 1)?.id ?: return@mapNotNull null
                block.copy(startStepId = start, endStepId = end)
            }
            // Rẽ nhánh đứng sau bước bị xoá thì dời về sau bước trước đó (hoặc đầu luồng).
            val previousId = f.steps.getOrNull(index - 1)?.id ?: ""
            val branches = f.branches.map { branch ->
                branch.copy(
                    afterStepId = if (branch.afterStepId == stepId) previousId else branch.afterStepId,
                    rules = branch.rules.map { rule -> rule.copy(stepIds = rule.stepIds.filterNot { it == stepId }) }
                )
            }
            f.copy(steps = f.steps.filterNot { it.id == stepId }, repeats = repeats, branches = branches)
        }
    }

    /** Thêm mới hoặc cập nhật (theo id) một nhóm lặp của luồng. */
    fun upsertRepeat(flowId: String, block: RepeatBlock) {
        updateFlow(flowId) { f ->
            val exists = f.repeats.any { it.id == block.id }
            f.copy(
                repeats = if (exists) {
                    f.repeats.map { if (it.id == block.id) block else it }
                } else {
                    f.repeats + block
                }
            )
        }
    }

    fun removeRepeat(flowId: String, blockId: String) {
        updateFlow(flowId) { f -> f.copy(repeats = f.repeats.filterNot { it.id == blockId }) }
    }

    /** Thêm mới hoặc cập nhật (theo id) một điểm rẽ nhánh theo chữ của luồng. */
    fun upsertBranch(flowId: String, branch: FlowBranch) {
        updateFlow(flowId) { f ->
            val exists = f.branches.any { it.id == branch.id }
            f.copy(
                branches = if (exists) {
                    f.branches.map { if (it.id == branch.id) branch else it }
                } else {
                    f.branches + branch
                }
            )
        }
    }

    fun removeBranch(flowId: String, branchId: String) {
        updateFlow(flowId) { f -> f.copy(branches = f.branches.filterNot { it.id == branchId }) }
    }

    // ---------- helpers ----------

    private fun <T> swapped(list: List<T>, index: Int, delta: Int): List<T> {
        val target = index + delta
        if (index < 0 || target !in list.indices) return list
        return list.toMutableList().also { Collections.swap(it, index, target) }
    }

    /**
     * Đọc file. Định dạng cũ (một mảng luồng, chưa có game) được chuyển vào "Game 1".
     */
    private fun load(f: File) {
        var games = emptyList<Game>()
        var selected = ""
        var flows = emptyList<ClickFlow>()
        try {
            if (f.exists()) {
                val text = f.readText().trim()
                if (text.startsWith("[")) {
                    val arr = JSONArray(text)
                    flows = List(arr.length()) { i -> flowFromJson(arr.getJSONObject(i)) }
                } else {
                    val root = JSONObject(text)
                    val gamesJson = root.optJSONArray("games") ?: JSONArray()
                    games = List(gamesJson.length()) { i ->
                        val g = gamesJson.getJSONObject(i)
                        Game(
                            id = g.optString("id", UUID.randomUUID().toString()),
                            name = g.optString("name", DEFAULT_GAME_NAME)
                        )
                    }
                    selected = root.optString("selectedGameId", "")
                    val flowsJson = root.optJSONArray("flows") ?: JSONArray()
                    flows = List(flowsJson.length()) { i -> flowFromJson(flowsJson.getJSONObject(i)) }
                }
            }
        } catch (e: Exception) {
            // File hỏng: giữ lại bản sao để không bị lần ghi sau ghi đè mất dữ liệu.
            Log.e(TAG, "flows.json unreadable, keeping a .bak copy", e)
            runCatching { f.copyTo(File(f.parentFile, f.name + ".bak"), overwrite = true) }
            recoveredFromCorruption = true
            games = emptyList()
            flows = emptyList()
        }

        synchronized(this) {
            _games.value = games
            _selectedGameId.value = selected
            ensureGames()
            val fallbackGameId = _games.value.first().id
            val validIds = _games.value.map { it.id }.toSet()
            _flows.value = flows.map { flow ->
                if (flow.gameId in validIds) flow else flow.copy(gameId = fallbackGameId)
            }
        }
    }

    /** Ghi ra file tạm rồi đổi tên để file chính không bị hỏng nếu app bị kill giữa chừng. */
    private fun save(f: File, games: List<Game>, selected: String, flows: List<ClickFlow>) {
        try {
            val root = JSONObject()
            root.put("selectedGameId", selected)
            val gamesArr = JSONArray()
            games.forEach { g ->
                gamesArr.put(JSONObject().put("id", g.id).put("name", g.name))
            }
            root.put("games", gamesArr)
            val flowsArr = JSONArray()
            flows.forEach { flowsArr.put(flowToJson(it)) }
            root.put("flows", flowsArr)

            val content = root.toString()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(f)) {
                f.writeText(content)
                tmp.delete()
            }
        } catch (e: Exception) {
            // Lần cập nhật sau sẽ ghi lại.
            Log.e(TAG, "save failed", e)
        }
    }

    private fun flowToJson(flow: ClickFlow): JSONObject {
        val o = JSONObject()
        o.put("id", flow.id)
        o.put("name", flow.name)
        o.put("gameId", flow.gameId)
        o.put("enabled", flow.enabled)
        o.put("repeatCount", flow.repeatCount)
        val steps = JSONArray()
        flow.steps.forEach { steps.put(stepToJson(it)) }
        o.put("steps", steps)
        val repeats = JSONArray()
        flow.repeats.forEach { block ->
            repeats.put(
                JSONObject()
                    .put("id", block.id)
                    .put("startStepId", block.startStepId)
                    .put("endStepId", block.endStepId)
                    .put("count", block.count)
            )
        }
        o.put("repeats", repeats)
        val branches = JSONArray()
        flow.branches.forEach { b ->
            val rules = JSONArray()
            b.rules.forEach { r ->
                rules.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("text", r.text)
                        .put("exactMatch", r.exactMatch)
                        .put("jump", r.jump)
                        .put("stepIds", JSONArray(r.stepIds))
                        .put("compare", r.compare)
                        .put("threshold", r.threshold)
                        .put("ownRegion", r.ownRegion)
                        .put("x", r.x)
                        .put("y", r.y)
                        .put("w", r.w)
                        .put("h", r.h)
                        .put("finishAfter", r.finishAfter)
                )
            }
            branches.put(
                JSONObject()
                    .put("id", b.id)
                    .put("afterStepId", b.afterStepId)
                    .put("x", b.x)
                    .put("y", b.y)
                    .put("w", b.w)
                    .put("h", b.h)
                    .put("waitMs", b.waitMs)
                    .put("noMatchAction", b.noMatchAction)
                    .put("confirmReads", b.confirmReads)
                    .put("rules", rules)
            )
        }
        o.put("branches", branches)
        return o
    }

    private fun stepToJson(s: Step): JSONObject {
        val o = JSONObject()
        o.put("id", s.id)
        o.put("x", s.x)
        o.put("y", s.y)
        o.put("delayMs", s.delayMs)
        o.put("clicks", s.clicks)
        o.put("repeatIntervalMs", s.repeatIntervalMs)
        o.put("waitText", s.waitText)
        o.put("exactMatch", s.exactMatch)
        o.put("regionW", s.regionW)
        o.put("regionH", s.regionH)
        o.put("timeoutMs", s.timeoutMs)
        o.put("isHold", s.isHold)
        o.put("holdMs", s.holdMs)
        o.put("isSwipe", s.isSwipe)
        o.put("endX", s.endX)
        o.put("endY", s.endY)
        o.put("swipeMs", s.swipeMs)
        o.put("days", s.days)
        o.put("skipIfAbsent", s.skipIfAbsent)
        o.put("requirePrevious", s.requirePrevious)
        o.put("isScribble", s.isScribble)
        o.put("gridCols", s.gridCols)
        o.put("gridRows", s.gridRows)
        o.put("repeatWhileText", s.repeatWhileText)
        o.put("loopMax", s.loopMax)
        o.put("ownRegion", s.ownRegion)
        o.put("regionX", s.regionX)
        o.put("regionY", s.regionY)
        o.put("holdBackground", s.holdBackground)
        return o
    }

    private fun flowFromJson(o: JSONObject): ClickFlow {
        val stepsJson = o.optJSONArray("steps") ?: JSONArray()
        val repeatsJson = o.optJSONArray("repeats") ?: JSONArray()
        val branchesJson = o.optJSONArray("branches") ?: JSONArray()
        return ClickFlow(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Luồng"),
            gameId = o.optString("gameId", ""),
            enabled = o.optBoolean("enabled", false),
            repeatCount = o.optInt("repeatCount", 1).coerceAtLeast(1),
            steps = List(stepsJson.length()) { i -> stepFromJson(stepsJson.getJSONObject(i)) },
            repeats = List(repeatsJson.length()) { i ->
                val r = repeatsJson.getJSONObject(i)
                RepeatBlock(
                    id = r.optString("id", UUID.randomUUID().toString()),
                    startStepId = r.optString("startStepId", ""),
                    endStepId = r.optString("endStepId", ""),
                    count = r.optInt("count", 2).coerceAtLeast(1)
                )
            },
            branches = List(branchesJson.length()) { i ->
                val b = branchesJson.getJSONObject(i)
                val rulesJson = b.optJSONArray("rules") ?: JSONArray()
                FlowBranch(
                    id = b.optString("id", UUID.randomUUID().toString()),
                    afterStepId = b.optString("afterStepId", ""),
                    x = b.optInt("x", 0),
                    y = b.optInt("y", 0),
                    w = b.optInt("w", 400).coerceAtLeast(20),
                    h = b.optInt("h", 160).coerceAtLeast(20),
                    waitMs = b.optLong("waitMs", 3000),
                    // Dữ liệu cũ dùng cờ stopIfNoMatch: true tương ứng "dừng luồng".
                    noMatchAction = b.optInt(
                        "noMatchAction",
                        if (b.optBoolean("stopIfNoMatch", false)) NO_MATCH_STOP else NO_MATCH_CONTINUE
                    ),
                    confirmReads = b.optInt("confirmReads", 1).coerceAtLeast(1),
                    rules = List(rulesJson.length()) { j ->
                        val r = rulesJson.getJSONObject(j)
                        val idsJson = r.optJSONArray("stepIds") ?: JSONArray()
                        BranchRule(
                            id = r.optString("id", UUID.randomUUID().toString()),
                            text = r.optString("text", ""),
                            exactMatch = r.optBoolean("exactMatch", false),
                            jump = r.optBoolean("jump", false),
                            compare = r.optString("compare", ""),
                            threshold = r.optInt("threshold", 0),
                            ownRegion = r.optBoolean("ownRegion", false),
                            x = r.optInt("x", 0),
                            y = r.optInt("y", 0),
                            w = r.optInt("w", 400).coerceAtLeast(20),
                            h = r.optInt("h", 160).coerceAtLeast(20),
                            finishAfter = r.optInt("finishAfter", 0).coerceAtLeast(0),
                            stepIds = List(idsJson.length()) { k -> idsJson.getString(k) }
                        )
                    }
                )
            }
        )
    }

    private fun stepFromJson(o: JSONObject): Step = Step(
        id = o.optString("id", UUID.randomUUID().toString()),
        x = o.optInt("x", 0),
        y = o.optInt("y", 0),
        delayMs = o.optLong("delayMs", 1000),
        clicks = o.optInt("clicks", 1),
        repeatIntervalMs = o.optLong("repeatIntervalMs", 300),
        waitText = o.optString("waitText", ""),
        exactMatch = o.optBoolean("exactMatch", false),
        regionW = o.optInt("regionW", 400),
        regionH = o.optInt("regionH", 160),
        timeoutMs = o.optLong("timeoutMs", 0),
        isHold = o.optBoolean("isHold", false),
        holdMs = o.optLong("holdMs", 1000),
        isSwipe = o.optBoolean("isSwipe", false),
        endX = o.optInt("endX", 0),
        endY = o.optInt("endY", 0),
        swipeMs = o.optLong("swipeMs", 400),
        days = o.optInt("days", ALL_DAYS),
        skipIfAbsent = o.optBoolean("skipIfAbsent", false),
        requirePrevious = o.optBoolean("requirePrevious", false),
        isScribble = o.optBoolean("isScribble", false),
        gridCols = o.optInt("gridCols", 3).coerceIn(1, 8),
        gridRows = o.optInt("gridRows", 3).coerceIn(1, 8),
        repeatWhileText = o.optBoolean("repeatWhileText", false),
        loopMax = o.optInt("loopMax", 200).coerceAtLeast(1),
        ownRegion = o.optBoolean("ownRegion", false),
        regionX = o.optInt("regionX", 0),
        regionY = o.optInt("regionY", 0),
        holdBackground = o.optBoolean("holdBackground", false)
    )
}
