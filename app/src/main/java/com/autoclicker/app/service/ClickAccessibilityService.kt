package com.autoclicker.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.autoclicker.app.data.FlowStore
import com.autoclicker.app.model.BranchRule
import com.autoclicker.app.model.ClickFlow
import com.autoclicker.app.model.FlowBranch
import com.autoclicker.app.model.NO_MATCH_SKIP_BRANCH_STEPS
import com.autoclicker.app.model.NO_MATCH_STOP
import com.autoclicker.app.model.Step
import com.autoclicker.app.model.branchPosition
import com.autoclicker.app.model.compareNumber
import com.autoclicker.app.model.describe
import com.autoclicker.app.model.gridNode
import com.autoclicker.app.model.isActiveToday
import com.autoclicker.app.model.isConfigured
import com.autoclicker.app.model.isNumeric
import com.autoclicker.app.model.resolvedRepeats
import com.autoclicker.app.model.toStepNumberText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Trái tim của app: chạm vào màn hình bằng dispatchGesture, chụp màn hình bằng
 * takeScreenshot rồi đọc chữ bằng ML Kit để biết khi nào nút hiện ra.
 */
class ClickAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickAccessibilityService? = null
            private set

        private const val TAG = "AutoClick"

        /** Chu kỳ chụp + đọc chữ: chậm hơn một chút để nhẹ máy (takeScreenshot giới hạn ~3 ảnh/giây). */
        private const val POLL_MS = 800L

        /** Chờ quá 10 giây: giãn chu kỳ để đỡ tốn pin. */
        private const val POLL_SLOW_MS = 1200L

        /** Chờ quá 60 giây: giãn thêm. */
        private const val POLL_IDLE_MS = 2000L

        /** Nghỉ ngắn trước khi thử lại một gesture bị huỷ. */
        private const val RETRY_DELAY_MS = 150L

        /** Ảnh phóng to để đọc chữ không rộng quá mức này (px), tránh tốn RAM / thời gian. */
        private const val MAX_ENHANCED_WIDTH = 1600

        /** Thời gian một lần chạm nhanh (ms). */
        private const val TAP_MS = 50L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runJob: Job? = null
    private var overlay: OverlayController? = null
    private var lastCaptureError = 0

    /** Chữ và chữ ký vùng cắt của lần đọc gần nhất, để bỏ qua OCR khi màn hình không đổi. */
    private class CachedOcr(val signature: Long, val result: OcrResult, val complete: Boolean)

    /** Kết quả OCR gần nhất theo từng khung (khoá "trái,trên,rộng,cao"). */
    private val ocrCache = HashMap<String, CachedOcr>()

    private val recognizerLazy = lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val recognizer get() = recognizerLazy.value

    override fun onServiceConnected() {
        super.onServiceConnected()
        FlowStore.init(applicationContext)
        overlay = OverlayController(this).also { controller ->
            controller.onStart = { delaySec -> startRun(delaySec) }
            controller.onTestRegion = { x, y, w, h, done -> testRegion(x, y, w, h, done) }
            controller.onTestBranch = { flowId, branchId, done -> testBranch(flowId, branchId, done) }
        }
        instance = this
        RunState.connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Không cần xử lý sự kiện nào.
    }

    override fun onInterrupt() {
        stopRun()
    }

    /** Màn hình xoay ngang / dọc: dựng lại các cửa sổ nổi để không bị tràn khỏi màn hình. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        FlowStore.flush()
        stopRun()
        overlay?.hideAll()
        overlay = null
        instance = null
        RunState.connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        if (recognizerLazy.isInitialized()) recognizerLazy.value.close()
        instance = null
        RunState.connected.value = false
        super.onDestroy()
    }

    // ------------------------------------------------------------ public API

    /** Mở chế độ kéo thả vị trí click cho một luồng, hiển thị trên mọi app (ví dụ game). */
    fun showPositionEditor(flowId: String) {
        if (runJob?.isActive == true) {
            toast("Đang chạy, hãy dừng trước khi chỉnh vị trí")
            return
        }
        overlay?.showEditor(flowId)
    }

    /**
     * Đọc thử chữ trong khung (tâm x, y rộng w cao h) để người dùng biết khung đã bao đúng chữ chưa.
     * [done] nhận thông báo đã soạn sẵn: chữ khung đang thấy, hoặc lý do không đọc được.
     */
    fun testRegion(x: Int, y: Int, w: Int, h: Int, done: (String) -> Unit) {
        scope.launch {
            ocrCache.clear()
            // Mỗi cách đọc (gốc / phóng to / đảo màu) ghi lại chữ đọc được để hiển thị.
            val variants = mutableListOf<Pair<String, String>>()
            var ocr: OcrResult? = null
            try {
                ocr = readRegionAt(x, y, w, h, variantsOut = variants)
                // Android giới hạn tần suất chụp (mã 3): đợi hơn 1 giây rồi thử lại một lần.
                if (ocr == null && lastCaptureError == 3) {
                    delay(1100)
                    variants.clear()
                    ocr = readRegionAt(x, y, w, h, variantsOut = variants)
                }
            } catch (e: Exception) {
                Log.w(TAG, "testRegion failed", e)
            }
            val message = when {
                ocr == null ->
                    "Không chụp được màn hình (mã $lastCaptureError). " +
                        "Nếu game chặn chụp màn hình thì app không đọc được chữ."
                variants.all { it.second.isBlank() } -> "Khung không thấy chữ nào (đã thử 3 cách đọc)"
                else -> "Khung đang thấy:\n" + variants.joinToString("\n") { (label, text) ->
                    val shown = text.replace('\n', ' ').trim().take(80)
                    val number = TextMatcher.parseNumber(text)?.let { "  (số: $it)" } ?: ""
                    "• $label: " + (if (shown.isBlank()) "(trống)" else "“$shown”") + number
                }
            }
            done(message)
        }
    }

    /** Mở khung viền trắng để chỉnh vùng đọc chữ của một điểm rẽ nhánh ngay trên màn hình. */
    fun showRegionEditor(flowId: String, branchId: String, ruleId: String? = null) {
        if (runJob?.isActive == true) {
            toast("Đang chạy, hãy dừng trước")
            return
        }
        overlay?.showRegionEditor(flowId, branchId, ruleId)
    }

    /** Hiện bong bóng nổi: danh sách luồng + START, dùng được trên mọi app. */
    fun showLauncher() {
        if (runJob?.isActive == true) {
            toast("Đang chạy, hãy dừng trước")
            return
        }
        overlay?.showLauncher(expanded = true)
    }

    /**
     * Chạy một lượt tuần tự các luồng đang được tick của game đang chọn (theo thứ tự trong danh sách);
     * mỗi luồng lặp theo số lần lặp riêng của nó.
     * @param startDelaySec đếm ngược trước khi bắt đầu, để kịp mở game
     */
    fun startRun(startDelaySec: Int) {
        if (runJob?.isActive == true) return
        val bar = overlay ?: return

        // Chỉ chạy các luồng đã tick thuộc game đang được chọn (radio).
        val gameId = FlowStore.selectedGameId.value
        val flows = FlowStore.flows.value.filter {
            it.gameId == gameId && it.enabled && it.steps.isNotEmpty()
        }
        if (flows.isEmpty()) {
            toast("Game đang chọn chưa có luồng nào được tick và có điểm click")
            return
        }

        bar.hideEditor()
        bar.hideLauncher(keepWanted = true)
        RunState.running.value = true
        bar.showRunBar { stopRun() }

        runJob = scope.launch {
            try {
                for (s in startDelaySec downTo 1) {
                    bar.setRunStatus("Bắt đầu sau ${s}s — hãy mở game")
                    delay(1000)
                }
                for (flow in flows) {
                    val times = flow.repeatCount.coerceAtLeast(1)
                    val repeats = flow.resolvedRepeats()
                    // Vị trí (chỉ số bước, 0-based) -> rẽ nhánh được xét ngay trước bước đó.
                    val branchAt = flow.branches
                        .mapNotNull { b -> flow.branchPosition(b).takeIf { it >= 0 }?.let { it to b } }
                        .toMap()
                    // Đếm số lần thấy chữ cộng dồn qua mọi lần lặp của luồng.
                    val flowState = FlowRunState()
                    for (rep in 1..times) {
                        val suffix = if (times > 1) " • lần $rep/$times" else ""
                        val pass = PassState(flowState)

                        // Bước đứng đầu một nhóm lặp thì chạy cả nhóm [count] lần rồi nhảy qua nhóm.
                        var i = 0
                        while (i < flow.steps.size && !pass.stopped) {
                            val group = repeats.firstOrNull { it.start == i }
                            if (group == null) {
                                val head = "[${flow.name}] bước ${i + 1}/${flow.steps.size}$suffix"
                                runAt(flow, i, head, branchAt, pass)
                                i++
                            } else {
                                val count = group.block.count.coerceAtLeast(1)
                                // Mỗi vòng của nhóm bắt đầu lại với bộ lọc bước như lúc vào nhóm.
                                val entrySkip = pass.skip
                                for (k in 1..count) {
                                    if (pass.stopped) break
                                    pass.skip = entrySkip
                                    for (j in group.start..group.end) {
                                        if (pass.stopped) break
                                        val head = "[${flow.name}] bước ${j + 1}/${flow.steps.size}" +
                                            " • nhóm lặp $k/$count$suffix"
                                        runAt(flow, j, head, branchAt, pass)
                                    }
                                    // Đã đủ số lần thấy chữ: xong vòng này của nhóm là thôi, không lặp thêm.
                                    if (flowState.finished) break
                                }
                                i = group.end + 1
                            }
                        }
                        // Dừng luồng ngay (bỏ qua các lần lặp còn lại) khi đã đủ số lần thấy chữ.
                        if (flowState.finished) {
                            bar.setRunStatus("[${flow.name}] đã đủ số lần thấy chữ, dừng luồng")
                            break
                        }
                    }
                    // Hết luồng thì nhả ngón tay đang giữ nền (nếu có).
                    releaseHold()
                }
                toast("Đã chạy xong")
            } finally {
                withContext(NonCancellable) { releaseHold() }
                RunState.running.value = false
                bar.hideRunBar()
                bar.restoreLauncher()
            }
        }
    }

    fun stopRun() {
        runJob?.cancel()
        runJob = null
    }

    // ------------------------------------------------------------ chạy 1 bước

    /**
     * Trạng thái của một luồng trong một vòng chạy, giữ nguyên qua các lần lặp luồng:
     * số lần mỗi nhánh đã được chọn và cờ "đã đủ số lần thấy chữ, dừng luồng".
     */
    private class FlowRunState {
        val matchCounts = HashMap<String, Int>()
        var finished = false
    }

    /** Trạng thái một lượt chạy của luồng: bước đang bị bỏ qua (do rẽ nhánh), cờ dừng, bước trước đã chạy chưa. */
    private class PassState(val flowState: FlowRunState) {
        var skip: Set<String> = emptySet()
        var stopped = false

        /** Bước ngay trước đã thực sự chạy? Bước đầu tiên coi như có bước trước (không bị chặn). */
        var prevRan = true
    }

    /**
     * Xét rẽ nhánh (nếu có) đứng ngay trước bước [index], rồi chạy bước đó nếu không bị bỏ qua,
     * đúng ngày trong tuần và (nếu đặt) bước trước đã chạy.
     * @return true nếu bước đã chạy
     */
    private suspend fun runAt(
        flow: ClickFlow,
        index: Int,
        head: String,
        branchAt: Map<Int, FlowBranch>,
        pass: PassState
    ): Boolean {
        branchAt[index]?.let { applyBranch(flow, it, head, pass) }
        if (pass.stopped) return false

        val step = flow.steps[index]
        val skipReason = when {
            step.id in pass.skip -> "bị bỏ qua do rẽ nhánh"
            !step.isActiveToday() -> "hôm nay không nằm trong ngày chạy"
            step.requirePrevious && !pass.prevRan -> "bước trước chưa chạy (đang bật “chỉ chạy khi bước trước chạy”)"
            else -> null
        }
        if (skipReason != null) overlay?.setRunStatus("$head\nbỏ qua bước: $skipReason")
        val due = skipReason == null
        // Kết quả "đã chạy" (đã bấm) của bước này quyết định bước kế tiếp có bị chặn hay không.
        val ran = due && runStep(step, head)
        pass.prevRan = ran
        return ran
    }

    /** Khung đọc của một nhánh: khung riêng nếu có, không thì khung chung của rẽ nhánh. */
    private fun regionOf(branch: FlowBranch, rule: BranchRule): IntArray =
        if (rule.ownRegion) intArrayOf(rule.x, rule.y, rule.w, rule.h)
        else intArrayOf(branch.x, branch.y, branch.w, branch.h)

    /** Điều kiện của nhánh có đúng với chữ OCR [ocr] không (loại chữ: thấy chữ; loại số: so số). */
    private fun ruleHolds(rule: BranchRule, ocr: OcrResult): Boolean =
        if (rule.isNumeric()) {
            TextMatcher.parseNumber(ocr.full)?.let { compareNumber(it, rule.compare, rule.threshold) } == true
        } else {
            TextMatcher.matches(ocr, rule.text, rule.exactMatch)
        }

    /**
     * Đọc màn hình (chụp một lần mỗi lượt, mỗi nhánh đọc khung của nó) và chọn nhánh đầu tiên đúng
     * liên tiếp [FlowBranch.confirmReads] lần đọc; chờ tối đa waitMs, trong lúc chờ đọc lại liên tục.
     * Nhánh được chọn thì các bước thuộc nhánh khác bị bỏ qua. Không nhánh nào đúng thì theo
     * noMatchAction: chạy tiếp tất cả / bỏ qua các bước thuộc nhánh / dừng luồng.
     */
    private suspend fun applyBranch(flow: ClickFlow, branch: FlowBranch, head: String, pass: PassState) {
        val rules = branch.rules.filter { it.isConfigured() }
        if (rules.isEmpty()) return
        // Mọi bước được nhắc tới trong bất kỳ nhánh nào (kể cả nhánh chưa nhập đủ).
        val branchSteps = branch.rules.flatMap { it.stepIds }.toSet()
        val confirm = branch.confirmReads.coerceAtLeast(1)

        val startedAt = SystemClock.elapsedRealtime()
        ocrCache.clear()
        var matched: BranchRule? = null
        var streakRule: BranchRule? = null
        var streak = 0
        while (true) {
            val readings = mutableListOf<String>()
            var candidate: BranchRule? = null
            val shot = captureScreen()
            if (shot != null) {
                try {
                    for (rule in rules) {
                        val (cx, cy, w, h) = regionOf(branch, rule)
                        val ocr = readRegionFrom(shot, cx, cy, w, h, null) { ruleHolds(rule, it) }
                        val holds = ocr != null && ruleHolds(rule, ocr)
                        readings += if (rule.isNumeric()) {
                            val value = ocr?.let { TextMatcher.parseNumber(it.full) }
                            "số=${value ?: "?"} (cần ${rule.compare}${rule.threshold})"
                        } else {
                            "“${rule.text}” ${if (holds) "✓" else "✗"}"
                        }
                        if (holds && candidate == null) candidate = rule
                    }
                } finally {
                    shot.recycle()
                }
            }

            // Đúng liên tiếp đủ số lần đọc mới chọn, để một lần đọc nhầm không làm bấm sai.
            if (candidate != null && candidate.id == streakRule?.id) {
                streak++
            } else {
                streakRule = candidate
                streak = if (candidate != null) 1 else 0
            }
            if (candidate != null && streak >= confirm) {
                matched = candidate
                break
            }
            // Màn hình đã chuyển sang trạng thái khác (vd nút "quay về" hiện ra) có thể làm nhánh trước hết đúng.
            if (SystemClock.elapsedRealtime() - startedAt >= branch.waitMs) break
            overlay?.setRunStatus("$head\nchờ: " + readings.joinToString("  |  "))
            delay(POLL_MS)
        }

        val bar = overlay
        when {
            matched != null -> {
                pass.skip = branchSteps - matched.stepIds.toSet()
                if (matched.jump) {
                    // Nhảy: bỏ luôn các bước từ điểm rẽ tới trước bước đầu của nhánh (trừ bước thuộc nhánh).
                    val chosen = matched.stepIds.toSet()
                    val target = flow.steps.indexOfFirst { it.id in chosen }
                    val from = flow.branchPosition(branch)
                    if (target > from && from >= 0) {
                        pass.skip = pass.skip + flow.steps.subList(from, target).map { it.id }
                            .filterNot { it in chosen }
                    }
                }
                val numbers = matched.stepIds
                    .mapNotNull { id -> flow.steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) }
                    .toStepNumberText()
                var note = if (matched.jump) " (nhảy, bỏ các bước đứng trước)" else ""
                // Đếm số lần nhánh này được chọn; đủ N lần thì lượt này là lượt cuối của luồng.
                if (matched.finishAfter > 0) {
                    val count = (pass.flowState.matchCounts[matched.id] ?: 0) + 1
                    pass.flowState.matchCounts[matched.id] = count
                    note = " (lần $count/${matched.finishAfter})"
                    if (count >= matched.finishAfter) {
                        pass.flowState.finished = true
                        note += " → chạy lần cuối rồi dừng luồng"
                    }
                }
                bar?.setRunStatus(
                    "$head\n${matched.describe()} đúng → chạy bước ${numbers.ifBlank { "(không có)" }}$note"
                )
            }
            branch.noMatchAction == NO_MATCH_STOP -> {
                pass.stopped = true
                bar?.setRunStatus("$head\nkhông nhánh nào đúng, dừng luồng")
            }
            branch.noMatchAction == NO_MATCH_SKIP_BRANCH_STEPS -> {
                pass.skip = branchSteps
                bar?.setRunStatus("$head\nkhông nhánh nào đúng, bỏ qua các bước của nhánh")
            }
            else -> {
                pass.skip = emptySet()
                bar?.setRunStatus("$head\nkhông nhánh nào đúng, chạy tiếp bình thường")
            }
        }
    }

    /**
     * Đọc thử mọi nhánh của một rẽ nhánh ngay lúc này (chụp một lần) và báo từng nhánh đang thấy gì,
     * nhánh nào đang đúng. Dùng cho nút "Thử đọc" để chỉnh khung / ngưỡng.
     */
    fun testBranch(flowId: String, branchId: String, done: (String) -> Unit) {
        val branch = FlowStore.get(flowId)?.branches?.firstOrNull { it.id == branchId }
        if (branch == null) {
            done("Không tìm thấy rẽ nhánh")
            return
        }
        scope.launch {
            ocrCache.clear()
            var shot = captureScreen()
            if (shot == null && lastCaptureError == 3) {
                delay(1100)
                shot = captureScreen()
            }
            if (shot == null) {
                done(
                    "Không chụp được màn hình (mã $lastCaptureError). " +
                        "Nếu game chặn chụp màn hình thì app không đọc được chữ."
                )
                return@launch
            }
            val screen: Bitmap = shot
            val lines = mutableListOf<String>()
            try {
                branch.rules.forEachIndexed { index, rule ->
                    if (!rule.isConfigured()) {
                        lines += "${index + 1}. (chưa đặt chữ)"
                        return@forEachIndexed
                    }
                    val (cx, cy, w, h) = regionOf(branch, rule)
                    val variants = mutableListOf<Pair<String, String>>()
                    readRegionFrom(screen, cx, cy, w, h, variants, null)
                    val holds = variants.any { (_, text) -> ruleHolds(rule, OcrResult(text, text.split('\n'))) }
                    val mark = if (holds) "✓ ĐÚNG" else "✗ chưa"
                    lines += if (rule.isNumeric()) {
                        val values = variants.map { TextMatcher.parseNumber(it.second)?.toString() ?: "?" }
                        "${index + 1}. ${rule.describe()}: đọc được ${values.distinct().joinToString(" / ")} → $mark"
                    } else {
                        val seen = variants.map { it.second.replace('\n', ' ').trim() }
                            .filter { it.isNotBlank() }.distinct().joinToString(" | ").take(80)
                        "${index + 1}. ${rule.describe()}: thấy " +
                            (if (seen.isBlank()) "(trống)" else "“$seen”") + " → $mark"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "testBranch failed", e)
                lines += "Lỗi khi đọc thử"
            } finally {
                screen.recycle()
            }
            done(lines.joinToString("\n"))
        }
    }

    /** Chạy một bước. @return false nếu bước tự bỏ qua vì không thấy chữ cần chờ (chưa bấm). */
    private suspend fun runStep(step: Step, head: String): Boolean {
        val bar = overlay

        if (step.delayMs > 0) {
            bar?.setRunStatus("$head\nchờ ${step.delayMs} ms")
            delay(step.delayMs)
        }

        if (step.waitText.isNotBlank()) {
            if (!waitForText(step, head)) {
                bar?.setRunStatus(
                    "$head\n" + if (step.skipIfAbsent) "không thấy chữ, bỏ qua bước"
                    else "hết thời gian chờ chữ, bỏ qua bước"
                )
                return false
            }
        }

        // Giữ nền: đặt ngón tay xuống một lần rồi không nhả; các lần sau gặp lại bước này thì bỏ qua.
        if (step.isHold && step.holdBackground) {
            if (held?.stepId == step.id) {
                bar?.setRunStatus("$head\nđang giữ nền từ (${step.x}, ${step.y}), bỏ qua bước")
            } else {
                bar?.setRunStatus("$head\ngiữ nền tại (${step.x}, ${step.y}) (không nhả)")
                holdDown(step)
            }
            return true
        }

        val pressMs = when {
            step.isSwipe -> step.swipeMs
            step.isHold -> step.holdMs
            else -> TAP_MS
        }
        val action = when {
            step.isSwipe && step.isScribble -> "vuốt bừa lưới ${step.gridCols}x${step.gridRows}"
            step.isSwipe -> "vuốt → (${step.endX}, ${step.endY}) ${step.swipeMs} ms"
            step.isHold -> "giữ ${step.holdMs} ms"
            else -> "click"
        }
        val n = step.clicks.coerceAtLeast(1)
        // Có chữ để theo dõi + bật "lặp tới khi chữ biến mất": làm lại bước đến khi khung hết chữ.
        val loopWhileText = step.waitText.isNotBlank() && step.repeatWhileText
        val maxLoops = if (loopWhileText) step.loopMax.coerceAtLeast(1) else 1
        var loops = 0
        var readFailures = 0
        while (loops < maxLoops) {
            loops++
            val tag = if (loopWhileText) " • lần $loops/$maxLoops" else ""
            bar?.setRunStatus("$head$tag\n$action từ (${step.x}, ${step.y})")
            for (k in 1..n) {
                // Gesture bị hệ thống huỷ thì thử lại đúng một lần rồi mới bỏ qua.
                if (!perform(step, pressMs)) {
                    Log.w(TAG, "gesture cancelled, retrying once")
                    delay(RETRY_DELAY_MS)
                    perform(step, pressMs)
                }
                if (k < n) delay(step.repeatIntervalMs)
            }
            if (!loopWhileText) break

            // Chờ màn hình cập nhật rồi đọc lại khung: còn thấy chữ thì làm tiếp, mất chữ thì sang bước kế.
            delay(step.repeatIntervalMs.coerceAtLeast(150L))
            ocrCache.clear()
            val ocr = readRegion(step)
            if (ocr == null) {
                // Chụp lỗi (vd bị giới hạn tần suất): thử lại vài lần rồi thôi để không lặp mù.
                if (++readFailures >= 3) break
                delay(POLL_MS)
                loops--
                continue
            }
            readFailures = 0
            if (!TextMatcher.matches(ocr, step.waitText, step.exactMatch)) {
                bar?.setRunStatus("$head\nchữ “${step.waitText}” đã biến mất sau $loops lần, sang bước kế")
                break
            }
        }
        return true
    }

    private suspend fun perform(step: Step, pressMs: Long): Boolean = when {
        step.isSwipe && step.isScribble -> scribble(step)
        step.isSwipe -> click(step.x, step.y, pressMs, step.endX, step.endY)
        else -> click(step.x, step.y, pressMs)
    }

    /**
     * Một thứ tự ngẫu nhiên đi qua ĐỦ mọi ô của lưới [cols] x [rows], mỗi ô một lần, bắt đầu từ ô bất kỳ
     * — như vẽ mật khẩu Android: đi được mọi hướng (kể cả chéo, "nước mã"), nhưng một nét thẳng không
     * được vượt qua ô chưa nối (nếu vượt thì ngón tay thật cũng chạm ô đó, thứ tự sẽ khác dự định).
     * Tìm bằng quay lui ngẫu nhiên; lưới lớn quá không kịp tìm đủ thì trả đường dài nhất tìm được.
     * Ô đánh số theo chỉ số hàng * cols + cột.
     */
    private fun randomCoverPath(cols: Int, rows: Int): List<Pair<Int, Int>> {
        val total = cols * rows
        val visited = BooleanArray(total)
        val path = ArrayList<Int>(total)
        var best: List<Int> = emptyList()
        var budget = 20_000

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        // Các ô nằm đúng trên đoạn thẳng nối a -> b (không tính hai đầu).
        fun between(a: Int, b: Int): List<Int> {
            val ac = a % cols
            val ar = a / cols
            val dc = b % cols - ac
            val dr = b / cols - ar
            val g = gcd(kotlin.math.abs(dc), kotlin.math.abs(dr))
            if (g <= 1) return emptyList()
            return (1 until g).map { k -> (ar + dr / g * k) * cols + (ac + dc / g * k) }
        }

        fun dfs(current: Int): Boolean {
            if (path.size > best.size) best = path.toList()
            if (path.size == total) return true
            if (--budget <= 0) return false
            val next = (0 until total)
                .filter { !visited[it] && between(current, it).all { mid -> visited[mid] } }
                .shuffled()
            for (n in next) {
                visited[n] = true
                path += n
                if (dfs(n)) return true
                path.removeAt(path.size - 1)
                visited[n] = false
                if (budget <= 0) return false
            }
            return false
        }

        val start = Random.nextInt(total)
        visited[start] = true
        path += start
        dfs(start)
        return best.map { it % cols to it / cols }
    }

    /**
     * Vuốt bừa: một nét liền (như một ngón tay không nhấc lên) đi qua đủ mọi ô của lưới theo một thứ tự
     * ngẫu nhiên (xem [randomCoverPath]); mỗi lần gọi là một thứ tự mới.
     */
    private suspend fun scribble(step: Step): Boolean {
        val cols = step.gridCols.coerceAtLeast(1)
        val rows = step.gridRows.coerceAtLeast(1)
        val walk = randomCoverPath(cols, rows)
        if (walk.size < 2) return false

        val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        val maxX = (bounds.width() - 1).toFloat().coerceAtLeast(0f)
        val maxY = (bounds.height() - 1).toFloat().coerceAtLeast(0f)
        val path = Path()
        walk.forEachIndexed { i, (c, r) ->
            val (px, py) = step.gridNode(c, r)
            // Lệch nhẹ vài px như ngón tay thật, nhưng vẫn nằm gọn trong ô.
            val x = (px + Random.nextInt(-4, 5)).coerceIn(0f, maxX)
            val y = (py + Random.nextInt(-4, 5)).coerceIn(0f, maxY)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val duration = (step.swipeMs.coerceAtLeast(30L) * (walk.size - 1))
            .coerceIn(1L, GestureDescription.getMaxGestureDuration())
        overlay?.setRunStatus("vuốt bừa: đi qua ${walk.size}/${cols * rows} ô")
        return dispatchPath(path, duration)
    }

    /** Đọc chữ trong vùng quanh nút cho tới khi khớp. false nếu hết timeout. */
    private suspend fun waitForText(step: Step, head: String): Boolean {
        val bar = overlay
        val startedAt = SystemClock.elapsedRealtime()
        ocrCache.clear()
        while (true) {
            val ocr = readRegion(step)
            if (ocr != null) {
                if (TextMatcher.matches(ocr, step.waitText, step.exactMatch)) return true
                val seen = ocr.full.replace('\n', ' ').take(40)
                bar?.setRunStatus("$head\nchờ \"${step.waitText}\"\nđang thấy: \"$seen\"")
            } else {
                bar?.setRunStatus("$head\nchưa chụp được màn hình (mã $lastCaptureError)")
            }
            // "Chỉ bấm nếu thấy chữ": không thấy ngay lúc này (và không đặt thời gian chờ) thì bỏ qua bước.
            if (step.skipIfAbsent && step.timeoutMs <= 0) return false
            val waited = SystemClock.elapsedRealtime() - startedAt
            if (step.timeoutMs > 0 && waited >= step.timeoutMs) return false
            // Chờ càng lâu thì chụp/đọc càng thưa để đỡ tốn pin.
            delay(
                when {
                    waited < 10_000L -> POLL_MS
                    waited < 60_000L -> POLL_SLOW_MS
                    else -> POLL_IDLE_MS
                }
            )
        }
    }

    // ----------------------------------------------------------------- click

    /**
     * Chạm tại (x, y) trong [pressMs] ms (chạm nhanh = [TAP_MS], điểm giữ = holdMs).
     * Có [endX]/[endY] thì kéo ngón tay tới đó trong [pressMs] ms (vuốt).
     */
    private suspend fun click(
        x: Int,
        y: Int,
        pressMs: Long,
        endX: Int? = null,
        endY: Int? = null
    ): Boolean {
        // Kẹp toạ độ vào màn hình hiện tại (phòng khi xoay màn hình / đổi độ phân giải).
        val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        val maxX = (bounds.width() - 1).coerceAtLeast(0)
        val maxY = (bounds.height() - 1).coerceAtLeast(0)
        val sx = x.coerceIn(0, maxX)
        val sy = y.coerceIn(0, maxY)
        val path = Path().apply {
            moveTo(sx.toFloat(), sy.toFloat())
            if (endX != null && endY != null) {
                val ex = endX.coerceIn(0, maxX)
                val ey = endY.coerceIn(0, maxY)
                if (ex != sx || ey != sy) lineTo(ex.toFloat(), ey.toFloat())
            }
        }
        return dispatchPath(path, pressMs)
    }

    /** Ngón tay đang giữ nền (không nhả): nét hiện tại, vị trí và bước đã đặt nó. */
    private class HeldTouch(var stroke: GestureDescription.StrokeDescription, val x: Int, val y: Int, val stepId: String)

    private var held: HeldTouch? = null

    /**
     * Thực hiện một gesture một nét theo [path] trong [durationMs] ms. Đang giữ nền thì gesture này
     * kèm luôn nét tiếp tục của ngón đang giữ, nên hai ngón cùng chạm: ngón giữ không bị nhả.
     */
    private suspend fun dispatchPath(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val duration = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        val builder = GestureDescription.Builder()
        val current = held
        val continued = current?.let {
            val stay = Path().apply { moveTo(it.x.toFloat(), it.y.toFloat()) }
            it.stroke.continueStroke(stay, 0L, duration, true)
        }
        if (continued != null) builder.addStroke(continued)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
        val dispatched = dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continued != null) current?.stroke = continued
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    // Gesture bị huỷ thì coi như ngón giữ đã bị nhả.
                    if (continued != null) held = null
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )
        if (!dispatched) {
            if (continued != null) held = null
            if (cont.isActive) cont.resume(false)
        }
    }

    /** Đặt ngón tay xuống tại điểm giữ của [step] và không nhả (xem [Step.holdBackground]). */
    private suspend fun holdDown(step: Step) {
        releaseHold()
        val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        val x = step.x.coerceIn(0, (bounds.width() - 1).coerceAtLeast(0))
        val y = step.y.coerceIn(0, (bounds.height() - 1).coerceAtLeast(0))
        val stroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0L, TAP_MS, true
        )
        val ok = suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }
        if (ok) held = HeldTouch(stroke, x, y, step.id)
    }

    /** Nhả ngón tay đang giữ nền (nếu có). */
    private suspend fun releaseHold() {
        val current = held ?: return
        held = null
        val stay = Path().apply { moveTo(current.x.toFloat(), current.y.toFloat()) }
        val last = try {
            current.stroke.continueStroke(stay, 0L, TAP_MS, false)
        } catch (e: Exception) {
            Log.w(TAG, "releaseHold failed", e)
            return
        }
        suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                GestureDescription.Builder().addStroke(last).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    // ------------------------------------------------------------- chụp + OCR

    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )
                    val soft = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    screenshot.hardwareBuffer.close()
                    lastCaptureError = 0
                    if (cont.isActive) cont.resume(soft) else soft?.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    lastCaptureError = errorCode
                    if (cont.isActive) cont.resume(null)
                }
            }
        )
    }

    private suspend fun recognize(bitmap: Bitmap): Text? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    private suspend fun readRegion(step: Step): OcrResult? =
        // Khung riêng thì đọc quanh (regionX, regionY), không thì quanh chính điểm bấm.
        readRegionAt(
            if (step.ownRegion) step.regionX else step.x,
            if (step.ownRegion) step.regionY else step.y,
            step.regionW,
            step.regionH
        ) { ocr ->
            TextMatcher.matches(ocr, step.waitText, step.exactMatch)
        }

    /**
     * Chụp màn hình, cắt vùng rộng [regionW] cao [regionH] có tâm ([cx], [cy]) rồi OCR.
     *
     * Có [accept] (chữ cần tìm): đọc ảnh gốc trước, không khớp mới thử thêm ảnh phóng to + tăng
     * tương phản rồi ảnh đảo màu, dừng ngay khi một cách đọc khớp (đỡ tốn pin khi đã khớp).
     * [variantsOut] (dùng cho "Thử"): nhận chữ đọc được của từng cách để hiển thị cho người dùng.
     * null nếu chụp/đọc thất bại.
     */
    private suspend fun readRegionAt(
        cx: Int,
        cy: Int,
        regionW: Int,
        regionH: Int,
        variantsOut: MutableList<Pair<String, String>>? = null,
        accept: ((OcrResult) -> Boolean)? = null
    ): OcrResult? {
        val shot = captureScreen() ?: return null
        try {
            return readRegionFrom(shot, cx, cy, regionW, regionH, variantsOut, accept)
        } finally {
            shot.recycle()
        }
    }

    /**
     * Như [readRegionAt] nhưng dùng ảnh chụp [shot] có sẵn (không recycle) để một lần chụp phục vụ
     * nhiều khung. Khung không đổi so với lần đọc trước thì dùng lại kết quả, không chạy lại OCR.
     */
    private suspend fun readRegionFrom(
        shot: Bitmap,
        cx: Int,
        cy: Int,
        regionW: Int,
        regionH: Int,
        variantsOut: MutableList<Pair<String, String>>?,
        accept: ((OcrResult) -> Boolean)?
    ): OcrResult? {
        val w = minOf(regionW, shot.width).coerceAtLeast(1)
        val h = minOf(regionH, shot.height).coerceAtLeast(1)
        val left = (cx - w / 2).coerceIn(0, shot.width - w)
        val top = (cy - h / 2).coerceIn(0, shot.height - h)
        val crop = Bitmap.createBitmap(shot, left, top, w, h)
        try {
            val key = "$left,$top,$w,$h"
            val signature = signatureOf(crop)
            val cached = ocrCache[key]
            if (variantsOut == null && cached != null && cached.signature == signature) {
                // Dùng lại nếu cách đọc trước đã đủ (thử hết 3 cách) hoặc đã thoả điều kiện đang tìm.
                if (cached.complete || accept == null || accept(cached.result)) return cached.result
            }

            val (result, complete) = recognizeBest(crop, accept, variantsOut) ?: return null
            ocrCache[key] = CachedOcr(signature, result, complete)
            return result
        } finally {
            crop.recycle()
        }
    }

    /** Chạy OCR trên một ảnh. null nếu ML Kit lỗi. */
    private suspend fun runOcr(bitmap: Bitmap): OcrResult? {
        val text = recognize(bitmap) ?: return null
        return OcrResult(
            full = text.text,
            lines = text.textBlocks.flatMap { block -> block.lines.map { it.text } }
        )
    }

    /**
     * Đọc [crop] bằng tối đa 3 cách (gốc, phóng to + tương phản, đảo màu) để đọc được nhiều kiểu
     * chữ hơn (chữ nhỏ, chữ trắng trên nền tối, chữ nhạt). Trả về kết quả khớp [accept] đầu tiên,
     * nếu không có thì gộp chữ của mọi cách để hiển thị.
     */
    private suspend fun recognizeBest(
        crop: Bitmap,
        accept: ((OcrResult) -> Boolean)?,
        variantsOut: MutableList<Pair<String, String>>?
    ): Pair<OcrResult, Boolean>? {
        val original = runOcr(crop) ?: return null
        variantsOut?.add("Gốc" to original.full)
        // Cặp trả về: (kết quả, đã thử đủ cả 3 cách chưa).
        if (accept != null && accept(original)) return original to false
        // Không cần tìm chữ cụ thể và không cần liệt kê: một lần đọc là đủ.
        if (accept == null && variantsOut == null) return original to false

        val results = mutableListOf(original)
        for ((label, invert) in listOf("Phóng to" to false, "Đảo màu" to true)) {
            val enhanced = enhance(crop, invert)
            try {
                val r = runOcr(enhanced)
                if (r != null) {
                    results += r
                    variantsOut?.add(label to r.full)
                    if (accept != null && accept(r)) return r to false
                }
            } finally {
                enhanced.recycle()
            }
        }
        if (results.size == 1) return original to true
        return OcrResult(
            full = results.map { it.full }.filter { it.isNotBlank() }.distinct().joinToString("\n"),
            lines = results.flatMap { it.lines }.distinct()
        ) to true
    }

    /**
     * Phóng to 2 lần (nếu không quá lớn), chuyển xám và kéo giãn tương phản để chữ nhạt / nhỏ rõ
     * hơn; [invert] đảo sáng-tối để đọc chữ sáng trên nền tối.
     */
    private fun enhance(src: Bitmap, invert: Boolean): Bitmap {
        val factor = if (src.width * 2 <= MAX_ENHANCED_WIDTH) 2 else 1
        val scaled = if (factor == 1) src else Bitmap.createScaledBitmap(
            src, src.width * factor, src.height * factor, true
        )
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()

        var lo = 255
        var hi = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val lum = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            pixels[i] = lum
            if (lum < lo) lo = lum
            if (lum > hi) hi = lum
        }
        val range = (hi - lo).coerceAtLeast(1)
        for (i in pixels.indices) {
            var v = (pixels[i] - lo) * 255 / range
            if (invert) v = 255 - v
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Băm nhanh (lấy mẫu mỗi 5 điểm ảnh) để biết vùng cắt có đổi so với lần chụp trước không. */
    private fun signatureOf(bitmap: Bitmap): Long {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var hash = 1125899906842597L * 31 + w * 100003L + h
        var i = 0
        while (i < pixels.size) {
            hash = 31 * hash + pixels[i]
            i += 5
        }
        return hash
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
