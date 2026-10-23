package com.autoclicker.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.util.Log
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.app.data.FlowStore
import com.autoclicker.app.model.ALL_DAYS
import com.autoclicker.app.model.ClickFlow
import com.autoclicker.app.model.DAY_LABELS
import com.autoclicker.app.model.BranchRule
import com.autoclicker.app.model.COMPARE_OPS
import com.autoclicker.app.model.FlowBranch
import com.autoclicker.app.model.NO_MATCH_SKIP_BRANCH_STEPS
import com.autoclicker.app.model.NO_MATCH_STOP
import com.autoclicker.app.model.isNumeric
import com.autoclicker.app.model.Game
import com.autoclicker.app.model.branchPosition
import com.autoclicker.app.model.daysLabel
import com.autoclicker.app.model.gridNode
import com.autoclicker.app.model.hasDay
import com.autoclicker.app.model.parseStepNumbers
import com.autoclicker.app.model.toStepNumberText
import com.autoclicker.app.model.toggledDay
import com.autoclicker.app.model.RepeatBlock
import com.autoclicker.app.model.Step
import com.autoclicker.app.model.resolvedRepeats
import kotlin.math.abs
import kotlin.math.min

/**
 * Các cửa sổ nổi (overlay) vẽ bằng TYPE_ACCESSIBILITY_OVERLAY nên không cần xin
 * thêm quyền "Hiển thị trên ứng dụng khác".
 *
 *  - Chế độ chỉnh vị trí: mỗi bước là một chấm đánh số kéo thả được + thanh công cụ.
 *    Chạm vào chấm để mở bảng cài đặt nổi của điểm đó.
 *  - Bong bóng: danh sách luồng (checkbox, số lần lặp, thứ tự) + nút START, không cần mở app.
 *  - Thanh chạy: nút DỪNG và dòng trạng thái.
 */
class OverlayController(private val service: AccessibilityService) {

    /** Được service gán: bắt đầu chạy với (số vòng, đếm ngược giây). */
    var onStart: ((Int) -> Unit)? = null

    /** Được service gán: đọc thử chữ trong khung (x, y, w, h), trả chữ đọc được qua callback. */
    var onTestRegion: ((Int, Int, Int, Int, (String) -> Unit) -> Unit)? = null

    /** Được service gán: đọc thử mọi nhánh của một rẽ nhánh (flowId, branchId), trả báo cáo qua callback. */
    var onTestBranch: ((String, String, (String) -> Unit) -> Unit)? = null

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = currentMetrics().density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var launcherView: View? = null
    private var launcherWanted = false
    private var launcherIsPanel = false

    /** (flowId, branchId, ruleId?) của khung đọc chữ đang hiện, để dựng lại khi xoay màn hình. */
    private var pendingBranchForFrame: Triple<String, String, String?>? = null
    private var bubbleX = dp(8)
    private var bubbleY = dp(220)
    private var panelX: Int? = null
    private var panelY: Int? = null
    private val expandedFlows = mutableSetOf<String>()

    /** Rẽ nhánh vừa chỉnh khung xong: mở lại đúng bảng sửa rẽ nhánh khi bong bóng hiện lại. */
    private var pendingBranch: Pair<String, String>? = null

    private var regionFrame: View? = null
    private var regionBar: View? = null

    private var runBar: View? = null
    private var runStatus: TextView? = null

    private var editorBar: View? = null
    private var editorFlowId: String? = null
    private val markerViews = LinkedHashMap<String, TextView>()

    /** Các chấm vàng nhỏ báo vị trí các ô giữa hai góc của điểm vuốt bừa (không chạm được). */
    private val gridDots = ArrayList<View>()

    private var settingsPanel: View? = null
    private var settingsStepId: String? = null

    private fun dp(v: Int): Int = (v * density).toInt()

    /**
     * Kích thước màn hình hiện tại (đã tính xoay ngang / dọc) lấy từ cửa sổ thật, không dùng
     * resources vì có thể chưa cập nhật ngay sau khi xoay.
     */
    private fun currentMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        metrics.setTo(service.resources.getDisplayMetrics())
        val bounds = wm.currentWindowMetrics.bounds
        metrics.widthPixels = bounds.width()
        metrics.heightPixels = bounds.height()
        return metrics
    }

    // ---------------------------------------------------------------- run bar

    fun showRunBar(onStop: () -> Unit) {
        hideRunBar()

        val stop = TextView(service).apply {
            text = "■ DỪNG"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = pill(0xFFE53935.toInt(), 16)
            setOnClickListener { onStop() }
        }
        val status = TextView(service).apply {
            text = "Đang chuẩn bị…"
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 4
            maxWidth = dp(210)
            setPadding(dp(10), 0, dp(6), 0)
        }
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = pill(0xCC212121.toInt(), 24)
            addView(stop)
            addView(status)
        }
        val lp = overlayParams(WRAP, WRAP).apply {
            x = dp(8)
            y = dp(48)
        }
        attachDrag(status, root, lp) {}
        safeAdd(root, lp)
        runBar = root
        runStatus = status
    }

    fun setRunStatus(text: String) {
        val view = runStatus ?: return
        if (view.text.toString() != text) view.text = text
    }

    fun hideRunBar() {
        safeRemove(runBar)
        runBar = null
        runStatus = null
    }

    // ----------------------------------------------------------------- editor

    fun showEditor(flowId: String, quiet: Boolean = false) {
        hideEditor()
        if (FlowStore.get(flowId) == null) return
        hideLauncher(keepWanted = true)
        editorFlowId = flowId

        renderMarkers()

        val handle = TextView(service).apply {
            text = "☰"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val add = button("＋ Điểm", 0xFF1E88E5.toInt(), padH = 9) { addPoint() }
        val addHold = button("＋ Giữ", 0xFFFB8C00.toInt(), padH = 9) { addPoint(hold = true) }
        val addSwipe = button("＋ Vuốt", 0xFF8E24AA.toInt(), padH = 9) { addPoint(swipe = true) }
        val done = button("✔ Xong", 0xFF43A047.toInt(), padH = 9) { finishEditor() }

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = pill(0xDD212121.toInt(), 24)
            addView(handle)
            addView(add, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(2) })
            addView(addHold, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) })
            addView(addSwipe, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) })
            addView(done, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) })
        }
        val lp = overlayParams(WRAP, WRAP).apply {
            x = dp(16)
            y = dp(96)
        }
        attachDrag(handle, root, lp) {}
        safeAdd(root, lp)
        editorBar = root

        if (!quiet) {
            Toast.makeText(
                service,
                "＋ Điểm / Giữ / Vuốt để thêm • chạm vào số để cài đặt • kéo để di chuyển",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun hideEditor() {
        closeSettings()
        clearMarkers()
        safeRemove(editorBar)
        editorBar = null
        editorFlowId = null
    }

    fun hideAll() {
        hideRunBar()
        hideEditor()
        hideRegionEditor()
        hideLauncher()
    }

    // ----------------------------------------------------- khung đọc chữ (rẽ nhánh)

    /**
     * Hiện khung viền trắng để chỉnh vùng đọc chữ của một điểm rẽ nhánh: kéo khung để di chuyển,
     * kéo nút ⤡ ở góc dưới phải để đổi kích thước. Lưu xong thì quay lại bảng sửa rẽ nhánh.
     */
    fun showRegionEditor(
        flowId: String,
        branchId: String,
        ruleId: String? = null,
        quiet: Boolean = false,
        stepId: String? = null
    ) {
        // stepId != null: chỉnh khung đọc chữ riêng của một điểm (không liên quan rẽ nhánh).
        val step = stepId?.let { id -> FlowStore.get(flowId)?.steps?.firstOrNull { it.id == id } }
        if (stepId != null && step == null) return
        val branch = if (step == null) {
            FlowStore.get(flowId)?.branches?.firstOrNull { it.id == branchId } ?: return
        } else {
            null
        }
        // ruleId != null: chỉnh khung riêng của một nhánh; không thì chỉnh khung chung của rẽ nhánh.
        val ruleIndex = if (ruleId == null || branch == null) -1 else branch.rules.indexOfFirst { it.id == ruleId }
        val rule = branch?.rules?.getOrNull(ruleIndex)
        hideEditor()
        hideRegionEditor()
        hideLauncher(keepWanted = true)
        pendingBranchForFrame = if (step == null) Triple(flowId, branchId, ruleId) else null

        // Khung bắt đầu từ khung riêng đã lưu nếu có, không thì từ khung chung / quanh điểm bấm.
        val start = when {
            step != null -> intArrayOf(
                if (step.ownRegion) step.regionX else step.x,
                if (step.ownRegion) step.regionY else step.y,
                step.regionW,
                step.regionH
            )
            rule != null && rule.ownRegion -> intArrayOf(rule.x, rule.y, rule.w, rule.h)
            else -> intArrayOf(branch!!.x, branch.y, branch.w, branch.h)
        }
        val metrics = currentMetrics()
        val minW = dp(60)
        val minH = dp(40)
        val w = start[2].coerceIn(minW, metrics.widthPixels)
        val h = start[3].coerceIn(minH, metrics.heightPixels)

        val caption = TextView(service).apply {
            text = when {
                step != null -> "Khung chữ của điểm"
                rule != null -> "Khung nhánh ${ruleIndex + 1}"
                else -> "Khung đọc chữ"
            }
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = pill(0xAA000000.toInt(), 6)
        }
        val handle = TextView(service).apply {
            text = "⤡"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEEFFFFFF.toInt())
            }
        }
        val frame = FrameLayout(service).apply {
            background = GradientDrawable().apply {
                setColor(0x14FFFFFF)
                setStroke(dp(2), Color.WHITE)
            }
            addView(caption, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
            addView(handle, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
        }
        val frameLp = overlayParams(w, h).apply {
            x = (start[0] - w / 2).coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
            y = (start[1] - h / 2).coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
        }
        attachDrag(frame, frame, frameLp) {}

        var startW = 0
        var startH = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = frameLp.width
                    startH = frameLp.height
                    downX = e.rawX
                    downY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    frameLp.width = (startW + (e.rawX - downX).toInt())
                        .coerceIn(minW, (metrics.widthPixels - frameLp.x).coerceAtLeast(minW))
                    frameLp.height = (startH + (e.rawY - downY).toInt())
                        .coerceIn(minH, (metrics.heightPixels - frameLp.y).coerceAtLeast(minH))
                    try {
                        wm.updateViewLayout(frame, frameLp)
                    } catch (e2: Exception) {
                        Log.w(TAG, "resize failed", e2)
                    }
                    true
                }
                else -> false
            }
        }

        fun backToPanel() {
            hideRegionEditor()
            if (stepId != null) {
                // Quay lại trình chỉnh điểm, mở lại bảng cài đặt của điểm vừa chỉnh khung.
                showEditor(flowId, quiet = true)
                showSettings(stepId)
                return
            }
            pendingBranch = flowId to branchId
            expandedFlows.add(flowId)
            showLauncher(expanded = true)
        }

        val grip = TextView(service).apply {
            text = "☰"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        // Dòng kết quả đọc thử: hiện ngay trên thanh công cụ, giữ nguyên tới lần thử sau.
        val seenView = TextView(service).apply {
            text = "Bấm 👁 Thử để xem khung đang thấy chữ gì"
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 12f
            maxLines = 4
            setPadding(dp(8), dp(4), dp(8), dp(2))
        }

        val save = button("✔ Lưu khung", 0xFF43A047.toInt(), padH = 9) {
            val cx = frameLp.x + frameLp.width / 2
            val cy = frameLp.y + frameLp.height / 2
            if (stepId != null) {
                FlowStore.updateStep(flowId, stepId) {
                    it.copy(ownRegion = true, regionX = cx, regionY = cy, regionW = frameLp.width, regionH = frameLp.height)
                }
                backToPanel()
                return@button
            }
            FlowStore.get(flowId)?.branches?.firstOrNull { it.id == branchId }?.let { latest ->
                val saved = if (ruleId == null) {
                    latest.copy(x = cx, y = cy, w = frameLp.width, h = frameLp.height)
                } else {
                    latest.copy(
                        rules = latest.rules.map {
                            if (it.id == ruleId) {
                                it.copy(ownRegion = true, x = cx, y = cy, w = frameLp.width, h = frameLp.height)
                            } else {
                                it
                            }
                        }
                    )
                }
                FlowStore.upsertBranch(flowId, saved)
            }
            backToPanel()
        }
        val test = button("👁 Thử", 0xFF00838F.toInt(), padH = 9) {
            // Ẩn khung + thanh công cụ để ảnh chụp không dính viền trắng, rồi đọc thử.
            val cx = frameLp.x + frameLp.width / 2
            val cy = frameLp.y + frameLp.height / 2
            seenView.text = "Đang đọc…"
            frame.visibility = View.INVISIBLE
            regionBar?.visibility = View.INVISIBLE
            frame.postDelayed({
                val started = onTestRegion?.invoke(cx, cy, frameLp.width, frameLp.height) { message ->
                    frame.visibility = View.VISIBLE
                    regionBar?.visibility = View.VISIBLE
                    seenView.text = message
                }
                if (started == null) {
                    frame.visibility = View.VISIBLE
                    regionBar?.visibility = View.VISIBLE
                    seenView.text = "Chưa đọc thử được, hãy thử lại"
                }
            }, 250)
        }
        val cancel = button("✕", 0xFF616161.toInt(), padH = 12) { backToPanel() }

        val buttonsRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(grip)
            addView(save, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(2) })
            addView(test, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) })
            addView(cancel, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) })
        }
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = pill(0xDD212121.toInt(), 16)
            addView(buttonsRow)
            addView(seenView)
        }
        val barLp = overlayParams(min(dp(300), metrics.widthPixels - dp(16)), WRAP).apply {
            x = dp(8)
            y = dp(8)
        }
        attachDrag(grip, bar, barLp) {}

        safeAdd(frame, frameLp)
        safeAdd(bar, barLp)
        regionFrame = frame
        regionBar = bar

        if (!quiet) {
            Toast.makeText(
                service,
                "Kéo khung vào đúng chữ cần nhận biết, kéo ⤡ để đổi kích thước, rồi bấm ✔ Lưu khung",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hideRegionEditor() {
        safeRemove(regionFrame)
        safeRemove(regionBar)
        regionFrame = null
        regionBar = null
        pendingBranchForFrame = null
    }

    /**
     * Thêm một điểm. Mặc định thêm vào cuối luồng; có [afterStepId] thì chèn ngay sau bước đó (đặt cạnh
     * bước đó), các điểm phía sau tự dồn số và mở luôn bảng cài đặt của điểm mới.
     */
    private fun addPoint(hold: Boolean = false, swipe: Boolean = false, afterStepId: String? = null) {
        val flowId = editorFlowId ?: return
        val flow = FlowStore.get(flowId) ?: return
        val metrics = currentMetrics()
        val reference = afterStepId?.let { id -> flow.steps.firstOrNull { it.id == id } }
        val x: Int
        val y: Int
        if (reference != null) {
            x = (reference.x + dp(40)).coerceIn(dp(24), (metrics.widthPixels - dp(24)).coerceAtLeast(dp(24)))
            y = (reference.y + dp(40)).coerceIn(dp(24), (metrics.heightPixels - dp(24)).coerceAtLeast(dp(24)))
        } else {
            val offset = (flow.steps.size % 6) * dp(28)
            x = metrics.widthPixels / 2 + offset
            y = metrics.heightPixels / 2 + offset
        }
        val step = Step(
            x = x,
            y = y,
            isHold = hold,
            isSwipe = swipe,
            // Điểm vuốt: mặc định vuốt lên 140dp, kéo chấm "→" để đổi đích.
            endX = x,
            endY = (y - dp(140)).coerceAtLeast(dp(24))
        )
        if (reference != null) {
            FlowStore.insertStepAfter(flowId, reference.id, step)
            // Đánh số lại các chấm phía sau rồi mở cài đặt của điểm vừa chèn.
            renderMarkers()
            showSettings(step.id)
        } else {
            FlowStore.updateFlow(flowId) { it.copy(steps = it.steps + step) }
            addMarker(step, flow.steps.size + 1)
        }
    }

    private fun clearMarkers() {
        markerViews.values.forEach { safeRemove(it) }
        markerViews.clear()
        clearGridDots()
    }

    private fun clearGridDots() {
        gridDots.forEach { safeRemove(it) }
        gridDots.clear()
    }

    /** Vẽ lại toàn bộ chấm theo danh sách bước hiện tại (đánh số lại sau khi xoá). */
    private fun renderMarkers() {
        clearMarkers()
        val flow = editorFlowId?.let { FlowStore.get(it) } ?: return
        flow.steps.forEachIndexed { i, step -> addMarker(step, i + 1) }
        renderGridDots()
    }

    /** Vẽ các ô ở giữa của mọi điểm vuốt bừa trong luồng đang sửa (hai góc đã có chấm số riêng). */
    private fun renderGridDots() {
        clearGridDots()
        val flow = editorFlowId?.let { FlowStore.get(it) } ?: return
        val size = dp(14)
        flow.steps.filter { it.isSwipe && it.isScribble }.forEach { step ->
            val cols = step.gridCols.coerceAtLeast(1)
            val rows = step.gridRows.coerceAtLeast(1)
            for (c in 0 until cols) for (r in 0 until rows) {
                if ((c == 0 && r == 0) || (c == cols - 1 && r == rows - 1)) continue
                val (px, py) = step.gridNode(c, r)
                val dot = View(service).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xCCFFEB3B.toInt())
                        setStroke(dp(1), Color.BLACK)
                    }
                }
                val lp = overlayParams(size, size).apply {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    x = px.toInt() - size / 2
                    y = py.toInt() - size / 2
                }
                safeAdd(dot, lp)
                gridDots += dot
            }
        }
    }

    private fun addMarker(step: Step, number: Int) {
        val size = dp(44)
        val tv = TextView(service).apply {
            text = number.toString()
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = markerBackground(step, selected = step.id == settingsStepId)
        }
        val lp = overlayParams(size, size).apply {
            // Kẹp vào màn hình để chấm không bị mất khi xoay / đổi độ phân giải.
            x = step.x.coerceIn(0, currentMetrics().widthPixels) - size / 2
            y = step.y.coerceIn(0, currentMetrics().heightPixels) - size / 2
        }
        attachDrag(
            tv, tv, lp,
            onTap = { toggleSettings(step.id) }
        ) {
            onMarkerMoved(step.id, lp.x + size / 2, lp.y + size / 2)
        }
        safeAdd(tv, lp)
        markerViews[step.id] = tv

        if (step.isSwipe) addEndMarker(step, number)
    }

    /** Chấm đích của điểm vuốt (nhỏ hơn, ghi "→số"); kéo để đổi điểm kết thúc. */
    private fun addEndMarker(step: Step, number: Int) {
        val size = dp(36)
        val tv = TextView(service).apply {
            text = "→$number"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = markerBackground(step, selected = step.id == settingsStepId)
        }
        val lp = overlayParams(size, size).apply {
            x = step.endX.coerceIn(0, currentMetrics().widthPixels) - size / 2
            y = step.endY.coerceIn(0, currentMetrics().heightPixels) - size / 2
        }
        attachDrag(
            tv, tv, lp,
            onTap = { toggleSettings(step.id) }
        ) {
            onEndMoved(step.id, lp.x + size / 2, lp.y + size / 2)
        }
        safeAdd(tv, lp)
        markerViews[step.id + END_SUFFIX] = tv
    }

    /** Đỏ = click, cam = giữ, tím = vuốt; viền vàng = đang chọn. */
    private fun markerBackground(step: Step?, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            val color = when {
                step?.isSwipe == true -> 0xCC8E24AA
                step?.isHold == true -> 0xCCFB8C00
                else -> 0xCCE53935
            }
            setColor(color.toInt())
            if (selected) setStroke(dp(3), 0xFFFFEB3B.toInt()) else setStroke(dp(2), Color.WHITE)
        }

    private fun highlightMarker(selectedId: String?) {
        val steps = editorFlowId?.let { FlowStore.get(it)?.steps }.orEmpty()
        markerViews.forEach { (key, view) ->
            val id = key.removeSuffix(END_SUFFIX)
            val step = steps.firstOrNull { it.id == id }
            view.background = markerBackground(step, selected = id == selectedId)
        }
    }

    private fun onMarkerMoved(stepId: String, cx: Int, cy: Int) {
        val flowId = editorFlowId ?: return
        FlowStore.updateStep(flowId, stepId) { it.copy(x = cx, y = cy) }
        renderGridDots()
    }

    private fun onEndMoved(stepId: String, cx: Int, cy: Int) {
        val flowId = editorFlowId ?: return
        FlowStore.updateStep(flowId, stepId) { it.copy(endX = cx, endY = cy) }
        renderGridDots()
    }

    private fun finishEditor() {
        hideEditor()
        showLauncher(expanded = true)
    }

    /** Đóng hẳn app: lưu dữ liệu, gỡ mọi cửa sổ nổi rồi kill tiến trình (dừng luôn lượt chạy nếu có). */
    private fun closeApp() {
        FlowStore.flush()
        hideAll()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // --------------------------------------------------------- settings panel

    private fun toggleSettings(stepId: String) {
        if (settingsStepId == stepId) closeSettings() else showSettings(stepId)
    }

    private fun closeSettings() {
        safeRemove(settingsPanel)
        settingsPanel = null
        settingsStepId = null
        highlightMarker(null)
    }

    /** Bảng cài đặt nổi của một điểm; sửa là lưu ngay vào FlowStore. */
    private fun showSettings(stepId: String) {
        val flowId = editorFlowId ?: return
        val flow = FlowStore.get(flowId) ?: return
        val index = flow.steps.indexOfFirst { it.id == stepId }
        if (index < 0) return
        val step = flow.steps[index]

        closeSettings()
        settingsStepId = stepId
        highlightMarker(stepId)

        fun edit(transform: (Step) -> Step) = FlowStore.updateStep(flowId, stepId, transform)

        val title = TextView(service).apply {
            val kind = when {
                step.isSwipe -> "Điểm vuốt "
                step.isHold -> "Điểm giữ "
                else -> "Điểm "
            }
            text = kind + (index + 1) + "   ☰"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        val close = TextView(service).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setOnClickListener { closeSettings() }
        }
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(close)
        }

        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Sắp xếp lại: chèn điểm mới ngay sau điểm này (các điểm sau tự dồn số, không phải đặt lại) hoặc dời lên / xuống.
        content.addView(fieldLabel("Chèn điểm mới ngay sau điểm ${index + 1}"))
        content.addView(
            LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    button("＋ Điểm", 0xFF1E88E5.toInt(), padH = 6) { addPoint(afterStepId = stepId) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) }
                )
                addView(
                    button("＋ Giữ", 0xFFFB8C00.toInt(), padH = 6) { addPoint(hold = true, afterStepId = stepId) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) }
                )
                addView(
                    button("＋ Vuốt", 0xFF8E24AA.toInt(), padH = 6) { addPoint(swipe = true, afterStepId = stepId) },
                    LinearLayout.LayoutParams(0, WRAP, 1f)
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP).apply { topMargin = dp(2) }
        )
        fun moveThis(delta: Int) {
            FlowStore.moveStep(flowId, stepId, delta)
            renderMarkers()
            showSettings(stepId)
        }
        content.addView(
            LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    button("▲ Dời lên", 0xFF546E7A.toInt(), padH = 6) { moveThis(-1) }
                        .apply { alpha = if (index > 0) 1f else 0.4f; isEnabled = index > 0 },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) }
                )
                addView(
                    button("▼ Dời xuống", 0xFF546E7A.toInt(), padH = 6) { moveThis(+1) }
                        .apply {
                            alpha = if (index < flow.steps.size - 1) 1f else 0.4f
                            isEnabled = index < flow.steps.size - 1
                        },
                    LinearLayout.LayoutParams(0, WRAP, 1f)
                )
            },
            rowParams()
        )

        content.addView(
            fieldRow(
                numberInput("Chờ trước (ms)", step.delayMs) { v -> edit { it.copy(delayMs = v) } },
                numberInput("Số lần", step.clicks.toLong()) { v ->
                    edit { it.copy(clicks = v.toInt().coerceAtLeast(1)) }
                },
                numberInput("Cách nhau (ms)", step.repeatIntervalMs) { v ->
                    edit { it.copy(repeatIntervalMs = v) }
                }
            )
        )
        fun checkBox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(service).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            buttonTintList = ColorStateList.valueOf(Color.WHITE)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        if (step.isHold) {
            content.addView(
                fieldRow(
                    numberInput("Giữ trong (ms)", step.holdMs) { v ->
                        edit { it.copy(holdMs = v.coerceAtLeast(1)) }
                    }
                ),
                rowParams()
            )
            // Giữ nền: ngón tay đặt xuống rồi không nhả, các bước sau vẫn chạy khi đang giữ.
            content.addView(
                checkBox(
                    "Giữ nền: đặt ngón tay xuống rồi không nhả, các bước sau vẫn chạy (lần sau bỏ qua bước này)",
                    step.holdBackground
                ) { checked -> edit { it.copy(holdBackground = checked) } },
                rowParams()
            )
        }
        if (step.isSwipe) {
            content.addView(
                fieldRow(
                    numberInput(
                        if (step.isScribble) "Mỗi đoạn giữa 2 ô (ms)" else "Thời gian vuốt (ms)",
                        step.swipeMs
                    ) { v -> edit { it.copy(swipeMs = v.coerceAtLeast(1)) } }
                ),
                rowParams()
            )
            // Vuốt bừa: một nét liền đi ngẫu nhiên qua các ô của lưới giữa điểm đầu và chấm →.
            content.addView(
                checkBox(
                    "Vuốt bừa: một nét liền đi qua ĐỦ các ô của lưới theo thứ tự ngẫu nhiên, như vẽ mật khẩu (góc đầu = điểm này, góc cuối = chấm →)",
                    step.isScribble
                ) { checked ->
                    edit {
                        it.copy(
                            isScribble = checked,
                            // Đổi kiểu thì đặt lại tốc độ cho hợp: vuốt bừa nhanh theo từng đoạn, vuốt thẳng chậm hơn.
                            swipeMs = if (checked && it.swipeMs >= 300) 120 else if (!checked && it.swipeMs < 300) 400 else it.swipeMs
                        )
                    }
                    renderGridDots()
                    showSettings(stepId)
                },
                rowParams()
            )
            if (step.isScribble) {
                content.addView(
                    fieldRow(
                        numberInput("Số cột", step.gridCols.toLong()) { v ->
                            edit { it.copy(gridCols = v.toInt().coerceIn(1, 8)) }
                            renderGridDots()
                        },
                        numberInput("Số hàng", step.gridRows.toLong()) { v ->
                            edit { it.copy(gridRows = v.toInt().coerceIn(1, 8)) }
                            renderGridDots()
                        }
                    ),
                    rowParams()
                )
                content.addView(
                    TextView(service).apply {
                        text = "Chấm vàng = vị trí các ô. Kéo điểm đầu và chấm → vào tâm ô ở hai góc đối diện của lưới."
                        setTextColor(0xFFBDBDBD.toInt())
                        textSize = 11f
                    },
                    rowParams()
                )
            }
        }

        val advanced = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (step.waitText.isBlank()) View.GONE else View.VISIBLE
        }
        val exact = CheckBox(service).apply {
            text = "Đúng chữ (không chịu OCR đọc lệch; mặc định: gần đúng)"
            setTextColor(Color.WHITE)
            textSize = 12f
            buttonTintList = ColorStateList.valueOf(Color.WHITE)
            isChecked = step.exactMatch
            setOnCheckedChangeListener { _, checked -> edit { it.copy(exactMatch = checked) } }
        }
        advanced.addView(exact)
        // Nút có điều kiện: thấy chữ thì bấm, không thấy thì bỏ qua ngay (không chờ).
        val skipIfAbsent = CheckBox(service).apply {
            text = "Không thấy chữ thì bỏ qua bước (không chờ)"
            setTextColor(Color.WHITE)
            textSize = 12f
            buttonTintList = ColorStateList.valueOf(Color.WHITE)
            isChecked = step.skipIfAbsent
            setOnCheckedChangeListener { _, checked -> edit { it.copy(skipIfAbsent = checked) } }
        }
        advanced.addView(skipIfAbsent)
        // Lặp tới khi chữ biến mất: làm lại bước, mỗi lần xong đọc lại khung, hết chữ mới sang bước kế.
        advanced.addView(
            checkBox(
                "Lặp lại bước cho tới khi chữ biến mất (đọc lại sau mỗi lần)",
                step.repeatWhileText
            ) { checked -> edit { it.copy(repeatWhileText = checked) } }
        )
        advanced.addView(
            fieldRow(
                numberInput("Lặp tối đa (lần)", step.loopMax.toLong()) { v ->
                    edit { it.copy(loopMax = v.toInt().coerceAtLeast(1)) }
                }
            )
        )
        // Khung đọc chữ riêng: chữ nằm chỗ khác với điểm bấm (vd chữ ở dưới, lưới ở giữa).
        val ownFrameButton = button(
            if (step.ownRegion) "▢ Khung chữ riêng (đã đặt)" else "▢ Đặt khung chữ riêng",
            0xFF0277BD.toInt(),
            padH = 8
        ) { showRegionEditor(flowId, "", stepId = stepId) }
        val ownFrameReset = TextView(service).apply {
            text = "↺"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(8), dp(6), dp(6), dp(6))
            visibility = if (step.ownRegion) View.VISIBLE else View.GONE
            setOnClickListener {
                edit { it.copy(ownRegion = false) }
                showSettings(stepId)
            }
        }
        advanced.addView(
            LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ownFrameButton)
                addView(ownFrameReset)
            },
            rowParams()
        )
        advanced.addView(
            fieldRow(
                numberInput("Timeout (ms, 0=∞)", step.timeoutMs) { v ->
                    edit { it.copy(timeoutMs = v) }
                },
                numberInput("Vùng rộng", step.regionW.toLong()) { v ->
                    edit { it.copy(regionW = v.toInt().coerceAtLeast(20)) }
                },
                numberInput("Vùng cao", step.regionH.toLong()) { v ->
                    edit { it.copy(regionH = v.toInt().coerceAtLeast(20)) }
                }
            )
        )

        val waitInput = EditText(service).apply {
            setText(step.waitText)
            hint = "Chờ chữ hiện ra rồi mới click (trống = click luôn)"
            setHintTextColor(0xFF9E9E9E.toInt())
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine()
            enableTyping()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = pill(0xFF424242.toInt(), 8)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    edit { it.copy(waitText = text) }
                    advanced.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                }
            })
        }
        content.addView(fieldLabel("Chờ chữ"), rowParams())
        content.addView(waitInput)
        content.addView(advanced, rowParams())

        // Nút sau chỉ kích hoạt khi nút trước (bước ngay trước) đã thực sự chạy.
        if (index > 0) {
            val requirePrevious = CheckBox(service).apply {
                text = "Chỉ chạy khi bước trước đã chạy (nút trước kích hoạt)"
                setTextColor(Color.WHITE)
                textSize = 12f
                buttonTintList = ColorStateList.valueOf(Color.WHITE)
                isChecked = step.requirePrevious
                setOnCheckedChangeListener { _, checked -> edit { it.copy(requirePrevious = checked) } }
            }
            content.addView(requirePrevious, rowParams())
        }

        content.addView(fieldLabel("Chạy vào các ngày (theo ngày của thiết bị)"), rowParams())
        content.addView(dayChips(step.days) { mask -> edit { it.copy(days = mask) } })

        val delete = button("🗑 Xoá điểm", 0xFFE53935.toInt()) {
            FlowStore.deleteStep(flowId, stepId)
            closeSettings()
            renderMarkers()
        }
        val done = button("Đóng", 0xFF616161.toInt()) { closeSettings() }
        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(delete, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
            addView(done, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        // Phần cố định (tiêu đề + nút) luôn nằm trong màn hình; chỉ phần nội dung cuộn, kể cả khi xoay ngang.
        val metrics = currentMetrics()
        val top = if (metrics.widthPixels > metrics.heightPixels) dp(4) else dp(40)
        val scroll = boundedScroll((metrics.heightPixels - top - dp(FIXED_PANEL_DP)).coerceAtLeast(dp(80)))
            .apply { addView(content) }
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            background = pill(0xEE212121.toInt(), 16)
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP))
            addView(actions, rowParams())
            releaseFocusOnOutsideTouch()
        }

        val width = min(dp(340), metrics.widthPixels - dp(16))
        val lp = panelParams(width).apply {
            x = (metrics.widthPixels - width) / 2
            y = top
        }
        attachDrag(title, root, lp) {}
        safeAdd(root, lp)
        settingsPanel = root
    }

    /** Hàng 7 nút T2…CN bật/tắt từng ngày; [onChange] nhận bitmask mới. */
    private fun dayChips(initial: Int, onChange: (Int) -> Unit): View {
        var mask = initial
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        DAY_LABELS.forEachIndexed { index, label ->
            val chip = TextView(service).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(7), 0, dp(7))
            }
            fun paint() {
                val on = mask.hasDay(index)
                chip.setTextColor(if (on) Color.WHITE else 0xFF9E9E9E.toInt())
                chip.background = pill(if (on) 0xFF1E88E5.toInt() else 0xFF424242.toInt(), 10)
            }
            chip.setOnClickListener {
                mask = mask.toggledDay(index)
                paint()
                onChange(mask)
            }
            paint()
            row.addView(
                chip,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (index < 6) rightMargin = dp(4) }
            )
        }
        return row
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        WRAP
    ).apply { topMargin = dp(8) }

    private fun fieldLabel(text: String) = TextView(service).apply {
        this.text = text
        setTextColor(0xFFBDBDBD.toInt())
        textSize = 11f
        maxLines = 1
    }

    /** Một hàng các ô, chia đều chiều ngang. */
    private fun fieldRow(vararg fields: View): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        fields.forEachIndexed { i, field ->
            addView(
                field,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (i < fields.size - 1) rightMargin = dp(6)
                }
            )
        }
    }

    /** Ô nhập số kèm nhãn; chỉ gọi [onChange] khi nội dung là số hợp lệ. */
    private fun numberInput(label: String, value: Long, onChange: (Long) -> Unit): View {
        val input = EditText(service).apply {
            setText(value.toString())
            setTextColor(Color.WHITE)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(9))
            setSingleLine()
            enableTyping()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = pill(0xFF424242.toInt(), 8)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.toLongOrNull()?.let(onChange)
                }
            })
        }
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldLabel(label))
            addView(input)
        }
    }

    // --------------------------------------------------------------- launcher

    /** Hiện bong bóng; [expanded] = true mở luôn bảng danh sách luồng. */
    fun showLauncher(expanded: Boolean) {
        hideLauncher(keepWanted = true)
        launcherWanted = true
        launcherIsPanel = expanded
        if (expanded) showLauncherPanel() else showBubble()
    }

    /** [keepWanted] = true: chỉ ẩn tạm, [restoreLauncher] sẽ hiện lại (ví dụ sau khi chạy xong). */
    fun hideLauncher(keepWanted: Boolean = false) {
        safeRemove(launcherView)
        launcherView = null
        if (!keepWanted) launcherWanted = false
    }

    /**
     * Gọi khi màn hình xoay: dựng lại các cửa sổ nổi theo kích thước mới để không bị tràn khỏi
     * màn hình (bảng, thanh công cụ, chấm điểm, khung đọc chữ).
     */
    fun onScreenChanged() {
        val m = currentMetrics()
        // Kẹp vị trí đã nhớ vào màn hình mới.
        bubbleX = bubbleX.coerceIn(0, (m.widthPixels - dp(52)).coerceAtLeast(0))
        bubbleY = bubbleY.coerceIn(0, (m.heightPixels - dp(52)).coerceAtLeast(0))
        panelX = null
        panelY = null

        val flowId = editorFlowId
        val frameShown = regionFrame != null
        when {
            frameShown -> {
                // Khung đọc chữ: dựng lại từ dữ liệu đã lưu (thay đổi chưa lưu sẽ mất).
                val pending = pendingBranchForFrame
                if (pending != null) showRegionEditor(pending.first, pending.second, pending.third, quiet = true)
            }
            flowId != null -> showEditor(flowId, quiet = true)
            launcherView != null -> showLauncher(expanded = launcherIsPanel)
        }
    }

    fun restoreLauncher() {
        if (launcherWanted && editorBar == null && regionFrame == null) showLauncher(expanded = false)
    }

    private fun showBubble() {
        val size = dp(52)
        val bubble = TextView(service).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE43A047.toInt())
                setStroke(dp(2), Color.WHITE)
            }
        }
        val lp = overlayParams(size, size).apply {
            x = bubbleX
            y = bubbleY
        }
        attachDrag(bubble, bubble, lp, onTap = { bubble.post { showLauncher(expanded = true) } }) {
            bubbleX = lp.x
            bubbleY = lp.y
        }
        safeAdd(bubble, lp)
        launcherView = bubble
    }

    private fun showLauncherPanel() {
        val metrics = currentMetrics()

        val title = TextView(service).apply {
            text = "Luồng click   ☰"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        val collapse = TextView(service).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setOnClickListener { showLauncher(expanded = false) }
        }
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(collapse)
        }

        val list = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val gamesList = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        var rebuild: () -> Unit = {}

        // Dòng phụ (số điểm + "chạy thứ N") của các luồng đang hiện, để cập nhật tại chỗ khi tick/bỏ tick.
        val subViews = HashMap<String, TextView>()
        fun refreshOrderLabels() {
            val selected = FlowStore.selectedGameId.value
            val gameFlows = FlowStore.flows.value.filter { it.gameId == selected }
            val order = gameFlows.filter { it.enabled }
                .mapIndexed { index, flow -> flow.id to index + 1 }
                .toMap()
            gameFlows.forEach { flow ->
                subViews[flow.id]?.text = subLabel(flow, order[flow.id], flow.id in expandedFlows)
            }
        }

        // Nút "＋ Thêm game" đổi thành ô nhập tên + Tạo / Huỷ; [forGameId] != null: thêm luồng vào game đó.
        val addHolder = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        fun renderAdd(editing: Boolean, forGameId: String? = null) {
            addHolder.removeAllViews()
            // Mặc định để trống: nút "＋ Game" nằm ở cột trái, "＋ Luồng" nằm trên danh sách luồng.
            if (!editing) return
            val nameInput = EditText(service).apply {
                hint = if (forGameId == null) "Tên game" else "Tên luồng"
                setHintTextColor(0xFF9E9E9E.toInt())
                setTextColor(Color.WHITE)
                textSize = 13f
                setSingleLine()
                enableTyping()
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = pill(0xFF424242.toInt(), 8)
            }
            val create = button("Tạo", 0xFF43A047.toInt()) {
                val newName = nameInput.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(service, "Hãy nhập tên", Toast.LENGTH_SHORT).show()
                } else if (forGameId == null) {
                    FlowStore.selectGame(FlowStore.addGame(newName))
                    renderAdd(false)
                    rebuild()
                } else {
                    val id = FlowStore.addFlow(newName, forGameId)
                    renderAdd(false)
                    showEditor(id)
                }
            }
            val cancel = button("Huỷ", 0xFF616161.toInt()) { renderAdd(false) }
            addHolder.addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(nameInput, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(create, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
                    addView(cancel, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
                }
            )
            nameInput.post {
                allowTyping(nameInput)
                nameInput.requestFocus()
                val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                nameInput.postDelayed({ imm.showSoftInput(nameInput, 0) }, 150)
            }
        }
        renderAdd(false)

        // Chọn các game đích rồi sao chép luồng (kèm toàn bộ điểm) sang đó.
        fun renderCopy(flow: ClickFlow) {
            addHolder.removeAllViews()
            val chosen = mutableSetOf<String>()
            val title = TextView(service).apply {
                text = "Sao chép “${flow.name}” sang:"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), 0, dp(2), dp(2))
            }
            val boxes = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
            FlowStore.games.value.forEach { game ->
                boxes.addView(
                    CheckBox(service).apply {
                        text = if (game.id == flow.gameId) "${game.name} (game hiện tại)" else game.name
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        buttonTintList = ColorStateList.valueOf(Color.WHITE)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) chosen += game.id else chosen -= game.id
                        }
                    }
                )
            }
            val ok = button("Sao chép", 0xFF43A047.toInt()) {
                if (chosen.isEmpty()) {
                    Toast.makeText(service, "Hãy chọn ít nhất một game", Toast.LENGTH_SHORT).show()
                } else {
                    val count = FlowStore.copyFlow(flow.id, chosen.toList())
                    Toast.makeText(service, "Đã sao chép sang $count game", Toast.LENGTH_SHORT).show()
                    renderAdd(false)
                    rebuild()
                }
            }
            val cancel = button("Huỷ", 0xFF616161.toInt()) { renderAdd(false) }
            addHolder.addView(title)
            addHolder.addView(boundedScroll(dp(150)).apply { addView(boxes) })
            addHolder.addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(ok, LinearLayout.LayoutParams(0, WRAP, 2f).apply { rightMargin = dp(6) })
                    addView(cancel, LinearLayout.LayoutParams(0, WRAP, 1f))
                },
                rowParams()
            )
        }

        // Đổi tên luồng: ô nhập tên + Lưu / Huỷ.
        fun renderRename(flow: ClickFlow) {
            addHolder.removeAllViews()
            val title = TextView(service).apply {
                text = "Đổi tên luồng “${flow.name}”"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), 0, dp(2), dp(2))
            }
            val nameInput = EditText(service).apply {
                setText(flow.name)
                setSelection(flow.name.length)
                hint = "Tên luồng"
                setHintTextColor(0xFF9E9E9E.toInt())
                setTextColor(Color.WHITE)
                textSize = 13f
                setSingleLine()
                enableTyping()
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = pill(0xFF424242.toInt(), 8)
            }
            val save = button("Lưu", 0xFF43A047.toInt()) {
                val newName = nameInput.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(service, "Hãy nhập tên", Toast.LENGTH_SHORT).show()
                } else {
                    FlowStore.renameFlow(flow.id, newName)
                    renderAdd(false)
                    rebuild()
                }
            }
            val cancel = button("Huỷ", 0xFF616161.toInt()) { renderAdd(false) }
            addHolder.addView(title)
            addHolder.addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(nameInput, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(save, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
                    addView(cancel, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
                }
            )
            nameInput.post {
                allowTyping(nameInput)
                nameInput.requestFocus()
                val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                nameInput.postDelayed({ imm.showSoftInput(nameInput, 0) }, 150)
            }
        }

        // Đổi game: bấm một game để chuyển luồng sang đó (luồng không còn ở game cũ).
        fun renderChangeGame(flow: ClickFlow) {
            addHolder.removeAllViews()
            val title = TextView(service).apply {
                text = "Chuyển “${flow.name}” sang game:"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), 0, dp(2), dp(2))
            }
            val others = FlowStore.games.value.filter { it.id != flow.gameId }
            val boxes = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
            if (others.isEmpty()) {
                boxes.addView(
                    TextView(service).apply {
                        text = "Chưa có game nào khác. Hãy thêm game trước."
                        setTextColor(0xFFBDBDBD.toInt())
                        textSize = 11f
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                    }
                )
            }
            others.forEach { game ->
                boxes.addView(
                    button(game.name, 0xFF0277BD.toInt()) {
                        FlowStore.changeGame(flow.id, game.id)
                        expandedFlows.remove(flow.id)
                        Toast.makeText(service, "Đã chuyển sang “${game.name}”", Toast.LENGTH_SHORT).show()
                        renderAdd(false)
                        rebuild()
                    },
                    rowParams()
                )
            }
            val cancel = button("Huỷ", 0xFF616161.toInt()) { renderAdd(false) }
            addHolder.addView(title)
            addHolder.addView(boundedScroll(dp(150)).apply { addView(boxes) })
            addHolder.addView(cancel, rowParams())
        }

        // Xác nhận xoá luồng ngay trên bong bóng (xoá luôn toàn bộ điểm của luồng).
        fun renderDelete(flow: ClickFlow) {
            addHolder.removeAllViews()
            val message = TextView(service).apply {
                text = "Xoá luồng “${flow.name}” cùng ${flow.steps.size} điểm? Không thể hoàn tác."
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), 0, dp(2), dp(4))
            }
            val confirm = button("Xoá", 0xFFE53935.toInt()) {
                FlowStore.deleteFlow(flow.id)
                expandedFlows.remove(flow.id)
                renderAdd(false)
                rebuild()
            }
            val cancel = button("Huỷ", 0xFF616161.toInt()) { renderAdd(false) }
            addHolder.addView(message)
            addHolder.addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(confirm, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
                    addView(cancel, LinearLayout.LayoutParams(0, WRAP, 1f))
                }
            )
        }

        // Đổi tên game đang chọn: ô nhập tên + Lưu / Huỷ.
        fun renderRenameGame(game: Game) {
            addHolder.removeAllViews()
            val title = TextView(service).apply {
                text = "Đổi tên game “${game.name}”"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), 0, dp(2), dp(2))
            }
            val nameInput = EditText(service).apply {
                setText(game.name)
                setSelection(game.name.length)
                hint = "Tên game"
                setHintTextColor(0xFF9E9E9E.toInt())
                setTextColor(Color.WHITE)
                textSize = 13f
                setSingleLine()
                enableTyping()
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = pill(0xFF424242.toInt(), 8)
            }
            val save = button("Lưu", 0xFF43A047.toInt()) {
                val newName = nameInput.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(service, "Hãy nhập tên", Toast.LENGTH_SHORT).show()
                } else {
                    FlowStore.renameGame(game.id, newName)
                    renderAdd(false)
                    rebuild()
                }
            }
            val cancel = button("Huỷ", 0xFF616161.toInt()) { renderAdd(false) }
            addHolder.addView(title)
            addHolder.addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(nameInput, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(save, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
                    addView(cancel, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
                }
            )
            nameInput.post {
                allowTyping(nameInput)
                nameInput.requestFocus()
                val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                nameInput.postDelayed({ imm.showSoftInput(nameInput, 0) }, 150)
            }
        }

        // Xác nhận xoá game đang chọn cùng toàn bộ luồng trong game (luôn còn ít nhất một game).
        fun renderDeleteGame(game: Game) {
            addHolder.removeAllViews()
            val count = FlowStore.flows.value.count { it.gameId == game.id }
            val message = TextView(service).apply {
                text = "Xoá game “${game.name}” cùng $count luồng trong đó? Không thể hoàn tác."
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), 0, dp(2), dp(4))
            }
            val confirm = button("Xoá", 0xFFE53935.toInt()) {
                FlowStore.flows.value.filter { it.gameId == game.id }.forEach { expandedFlows.remove(it.id) }
                FlowStore.deleteGame(game.id)
                renderAdd(false)
                rebuild()
            }
            val cancel = button("Huỷ", 0xFF616161.toInt()) { renderAdd(false) }
            addHolder.addView(message)
            addHolder.addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(confirm, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
                    addView(cancel, LinearLayout.LayoutParams(0, WRAP, 1f))
                }
            )
        }

        // Tạo / sửa / xoá nhóm lặp: bước từ [from] đến [to] lặp [count] lần rồi mới sang bước kế.
        fun renderRepeat(flow: ClickFlow, existing: RepeatBlock?) {
            addHolder.removeAllViews()
            val steps = flow.steps
            if (steps.isEmpty()) {
                Toast.makeText(service, "Luồng chưa có điểm nào", Toast.LENGTH_SHORT).show()
                renderAdd(false)
                return
            }
            val resolved = flow.resolvedRepeats().firstOrNull { it.block.id == existing?.id }
            var from = (resolved?.start ?: 0) + 1
            var to = (resolved?.end ?: minOf(1, steps.size - 1)) + 1
            var count = existing?.count ?: 2

            val title = TextView(service).apply {
                text = (if (existing == null) "Thêm nhóm lặp" else "Sửa nhóm lặp") +
                    " — “${flow.name}” (${steps.size} điểm)"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), 0, dp(2), dp(4))
            }
            val fields = fieldRow(
                numberInput("Từ bước", from.toLong()) { v -> from = v.toInt() },
                numberInput("Đến bước", to.toLong()) { v -> to = v.toInt() },
                numberInput("Lặp (lần)", count.toLong()) { v -> count = v.toInt() }
            )
            val save = button("Lưu", 0xFF43A047.toInt()) {
                val error = when {
                    from < 1 || to > steps.size -> "Bước phải nằm trong 1–${steps.size}"
                    to < from -> "“Đến bước” phải lớn hơn hoặc bằng “Từ bước”"
                    count < 1 -> "Số lần lặp tối thiểu là 1"
                    else -> {
                        val overlap = flow.resolvedRepeats().any {
                            it.block.id != existing?.id && from - 1 <= it.end && to - 1 >= it.start
                        }
                        if (overlap) "Nhóm này chồng lên một nhóm lặp khác" else null
                    }
                }
                if (error != null) {
                    Toast.makeText(service, error, Toast.LENGTH_SHORT).show()
                } else {
                    val block = RepeatBlock(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        startStepId = steps[from - 1].id,
                        endStepId = steps[to - 1].id,
                        count = count
                    )
                    FlowStore.upsertRepeat(flow.id, block)
                    renderAdd(false)
                    rebuild()
                }
            }
            val cancel = button("Huỷ", 0xFF616161.toInt()) { renderAdd(false) }
            val actions = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(save, LinearLayout.LayoutParams(0, WRAP, 2f).apply { rightMargin = dp(6) })
                if (existing != null) {
                    val delete = button("Xoá", 0xFFE53935.toInt()) {
                        FlowStore.removeRepeat(flow.id, existing.id)
                        renderAdd(false)
                        rebuild()
                    }
                    addView(delete, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
                }
                addView(cancel, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            addHolder.addView(title)
            addHolder.addView(fields)
            addHolder.addView(actions, rowParams())
        }

        // Sửa điểm rẽ nhánh theo chữ (sửa là lưu ngay). existing == null: tạo mới rồi mở khung chỉnh.
        // Ví dụ: sau bước 3, thấy “xxx” thì chạy bước 4-8, thấy “yyy” thì chạy bước 5-8.
        fun renderBranch(flow: ClickFlow, existing: FlowBranch?) {
            if (existing == null) {
                val m = currentMetrics()
                // Mặc định đặt sau bước đầu tiên (hoặc đầu luồng nếu chưa có bước).
                val created = FlowBranch(
                    afterStepId = flow.steps.firstOrNull()?.id ?: "",
                    x = m.widthPixels / 2,
                    y = m.heightPixels / 2
                )
                FlowStore.upsertBranch(flow.id, created)
                expandedFlows.add(flow.id)
                showRegionEditor(flow.id, created.id)
                return
            }
            addHolder.removeAllViews()
            var current: FlowBranch = existing
            fun save(change: (FlowBranch) -> FlowBranch) {
                current = change(current)
                FlowStore.upsertBranch(flow.id, current)
            }
            val steps = flow.steps

            val title = TextView(service).apply {
                text = "Rẽ nhánh — “${flow.name}” (${steps.size} bước)"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), 0, dp(2), dp(4))
            }

            val positionNow = flow.branchPosition(existing).coerceAtLeast(0)
            val position = numberInput("Xét sau bước số (0 = đầu luồng)", positionNow.toLong()) { v ->
                val p = v.toInt().coerceIn(0, steps.size)
                save { it.copy(afterStepId = if (p == 0) "" else steps[p - 1].id) }
            }
            val wait = numberInput("Chờ tối đa (ms)", existing.waitMs) { v ->
                save { it.copy(waitMs = v) }
            }
            val confirm = numberInput("Xác nhận (lần đọc liên tiếp)", existing.confirmReads.toLong()) { v ->
                save { it.copy(confirmReads = v.toInt().coerceAtLeast(1)) }
            }

            // Khi không nhánh nào đúng: bấm để đổi giữa 3 cách xử lý.
            fun noMatchLabel(action: Int): String = when (action) {
                NO_MATCH_SKIP_BRANCH_STEPS -> "Không nhánh nào đúng: bỏ qua các bước của nhánh"
                NO_MATCH_STOP -> "Không nhánh nào đúng: dừng luồng"
                else -> "Không nhánh nào đúng: chạy tiếp tất cả"
            }
            val noMatchRef = arrayOfNulls<TextView>(1)
            val noMatchButton = button(noMatchLabel(existing.noMatchAction), 0xFF455A64.toInt()) {
                val next = (current.noMatchAction + 1) % 3
                save { it.copy(noMatchAction = next) }
                noMatchRef[0]?.text = noMatchLabel(next)
            }
            noMatchRef[0] = noMatchButton

            // Mỗi nhánh gồm: loại điều kiện (chữ / số), giá trị, các bước sẽ chạy, khung riêng, dừng sau N lần.
            val rulesBox = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
            fun updateRule(ruleId: String, change: (BranchRule) -> BranchRule) {
                save { b -> b.copy(rules = b.rules.map { if (it.id == ruleId) change(it) else it }) }
            }
            fun plainInput(initial: String, hintText: String, onText: (String) -> Unit): EditText =
                EditText(service).apply {
                    setText(initial)
                    hint = hintText
                    setHintTextColor(0xFF9E9E9E.toInt())
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setSingleLine()
                    enableTyping()
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = pill(0xFF424242.toInt(), 8)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            onText(s?.toString().orEmpty())
                        }
                    })
                }
            fun renderRules() {
                rulesBox.removeAllViews()
                current.rules.forEachIndexed { index, rule ->
                    // Nút đổi loại: Chữ -> số >= -> số <= -> số > -> số < -> số = -> Chữ.
                    val kinds = listOf("") + COMPARE_OPS
                    val kindLabel = if (rule.isNumeric()) "Số ${rule.compare}" else "Chữ"
                    val kindButton = button(kindLabel, 0xFF6A1B9A.toInt(), padH = 8) {
                        val next = kinds[(kinds.indexOf(rule.compare) + 1) % kinds.size]
                        updateRule(rule.id) { it.copy(compare = next) }
                        renderRules()
                    }
                    val valueInput: View = if (rule.isNumeric()) {
                        plainInput(rule.threshold.toString(), "Ngưỡng (vd: 100)") { value ->
                            updateRule(rule.id) { it.copy(threshold = value.trim().toIntOrNull() ?: 0) }
                        }.apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED }
                    } else {
                        plainInput(rule.text, "Chữ (vd: Quay về)") { value ->
                            updateRule(rule.id) { it.copy(text = value) }
                        }
                    }
                    val remove = TextView(service).apply {
                        text = "✕"
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setPadding(dp(10), dp(6), dp(6), dp(6))
                        setOnClickListener {
                            save { b -> b.copy(rules = b.rules.filterNot { it.id == rule.id }) }
                            renderRules()
                        }
                    }

                    val currentNumbers = rule.stepIds
                        .mapNotNull { id -> steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) }
                        .toStepNumberText()
                    val stepsInput = plainInput(currentNumbers, "Bước sẽ chạy (vd: 4-8)") { value ->
                        val ids = parseStepNumbers(value, steps.size).map { steps[it - 1].id }
                        updateRule(rule.id) { it.copy(stepIds = ids) }
                    }
                    // Khung riêng: mở khung viền trắng cho riêng nhánh này; "↺" quay về khung chung.
                    val frameButton = button(
                        if (rule.ownRegion) "▢ Khung riêng" else "▢ Khung chung",
                        0xFF0277BD.toInt(),
                        padH = 8
                    ) { showRegionEditor(flow.id, existing.id, ruleId = rule.id) }
                    val resetFrame = TextView(service).apply {
                        text = "↺"
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setPadding(dp(8), dp(6), dp(6), dp(6))
                        visibility = if (rule.ownRegion) View.VISIBLE else View.GONE
                        setOnClickListener {
                            updateRule(rule.id) { it.copy(ownRegion = false) }
                            renderRules()
                        }
                    }
                    // Chế độ so chữ: bấm để đổi giữa "gần đúng" (chứa chữ, chịu OCR đọc lệch) và "đúng chữ".
                    val matchModeButton = button(
                        if (rule.exactMatch) "= Đúng chữ (bấm để đổi)" else "≈ Gần đúng (bấm để đổi)",
                        if (rule.exactMatch) 0xFFEF6C00.toInt() else 0xFF5E35B1.toInt()
                    ) {
                        updateRule(rule.id) { it.copy(exactMatch = !it.exactMatch) }
                        renderRules()
                    }
                    // Nhảy tới bước: nhánh đúng thì bỏ luôn các bước đứng trước bước đầu của nhánh.
                    val jumpButton = button(
                        if (rule.jump) "↷ Nhảy tới bước trên: BẬT" else "↷ Nhảy tới bước trên: tắt",
                        if (rule.jump) 0xFF2E7D32.toInt() else 0xFF616161.toInt()
                    ) {
                        updateRule(rule.id) { it.copy(jump = !it.jump) }
                        renderRules()
                    }
                    val finishInput = numberInput("Dừng luồng sau N lần thấy (0 = tắt)", rule.finishAfter.toLong()) { v ->
                        updateRule(rule.id) { it.copy(finishAfter = v.toInt().coerceAtLeast(0)) }
                    }

                    rulesBox.addView(
                        TextView(service).apply {
                            text = "Nhánh ${index + 1}"
                            setTextColor(0xFFBDBDBD.toInt())
                            textSize = 11f
                            setPadding(dp(2), dp(8), dp(2), 0)
                        }
                    )
                    rulesBox.addView(
                        LinearLayout(service).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(kindButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
                            addView(valueInput, LinearLayout.LayoutParams(0, WRAP, 1f))
                            addView(remove)
                        },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP)
                            .apply { topMargin = dp(2) }
                    )
                    rulesBox.addView(
                        LinearLayout(service).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(stepsInput, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
                            addView(frameButton)
                            addView(resetFrame)
                        },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP)
                            .apply { topMargin = dp(4) }
                    )
                    if (!rule.isNumeric()) {
                        rulesBox.addView(matchModeButton, LinearLayout.LayoutParams(WRAP, WRAP)
                            .apply { topMargin = dp(4) })
                    }
                    rulesBox.addView(jumpButton, LinearLayout.LayoutParams(WRAP, WRAP)
                        .apply { topMargin = dp(4) })
                    rulesBox.addView(finishInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP)
                        .apply { topMargin = dp(4) })
                }
            }
            if (current.rules.isEmpty()) {
                save { it.copy(rules = listOf(BranchRule())) }
            }
            renderRules()

            val addRule = button("＋ Thêm nhánh", 0xFF2E7D32.toInt()) {
                save { it.copy(rules = it.rules + BranchRule()) }
                renderRules()
            }
            val hint = TextView(service).apply {
                text = "Nhánh nào đúng trước (từ trên xuống) thì các bước thuộc nhánh khác bị bỏ qua; " +
                    "bước không thuộc nhánh nào vẫn chạy. Đặt nhánh “quay về” lên trên nếu muốn ưu tiên. " +
                    "Loại Số: đọc số trong khung (vd 88 hoặc 88/150) rồi so với ngưỡng."
                setTextColor(0xFF9E9E9E.toInt())
                textSize = 10f
                setPadding(dp(2), dp(4), dp(2), dp(4))
            }

            // Kết quả đọc thử hiện ngay trong bảng, giữ tới lần thử sau.
            val seenView = TextView(service).apply {
                text = "Bấm 👁 Thử đọc để xem từng nhánh đang thấy gì"
                setTextColor(0xFFE0E0E0.toInt())
                textSize = 12f
                setPadding(dp(4), dp(6), dp(4), dp(2))
            }

            val frameButton = button("▢ Khung chung", 0xFF0277BD.toInt()) {
                showRegionEditor(flow.id, existing.id)
            }
            val testButton = button("👁 Thử đọc", 0xFF00838F.toInt()) {
                // Ẩn bảng một chút để ảnh chụp không dính chữ của bảng, rồi đọc thử mọi nhánh.
                val panel = launcherView
                seenView.text = "Đang đọc…"
                panel?.visibility = View.INVISIBLE
                seenView.postDelayed({
                    val started = onTestBranch?.invoke(flow.id, existing.id) { message ->
                        panel?.visibility = View.VISIBLE
                        seenView.text = message
                    }
                    if (started == null) {
                        panel?.visibility = View.VISIBLE
                        seenView.text = "Chưa đọc thử được, hãy thử lại"
                    }
                }, 250)
            }
            val deleteButton = button("Xoá", 0xFFE53935.toInt()) {
                FlowStore.removeBranch(flow.id, existing.id)
                renderAdd(false)
                rebuild()
            }
            val closeButton = button("Đóng", 0xFF616161.toInt()) {
                renderAdd(false)
                rebuild()
            }
            val row1 = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(frameButton, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
                addView(testButton, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            val row2 = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(deleteButton, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
                addView(closeButton, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            addHolder.addView(title)
            addHolder.addView(fieldRow(position, wait, confirm))
            addHolder.addView(rulesBox)
            addHolder.addView(addRule, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            addHolder.addView(noMatchButton, rowParams())
            addHolder.addView(hint)
            addHolder.addView(row1)
            addHolder.addView(seenView)
            addHolder.addView(row2, rowParams())
        }

        // Bố cục hai cột: bên trái là các game (radio + tên), bấm vào một game thì bên phải hiện các
        // luồng của game đó (checkbox như cũ).
        rebuild = {
            gamesList.removeAllViews()
            list.removeAllViews()
            val games = FlowStore.games.value
            val selected = FlowStore.selectedGameId.value
            val allFlows = FlowStore.flows.value

            games.forEach { game ->
                gamesList.addView(
                    gameHeader(
                        game,
                        game.id == selected,
                        flowCount = allFlows.count { it.gameId == game.id },
                        onSelect = {
                            FlowStore.selectGame(game.id)
                            renderAdd(false)
                            gamesList.post { rebuild() }
                        }
                    ),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP)
                        .apply { bottomMargin = dp(4) }
                )
            }
            gamesList.addView(
                button("＋ Game", 0xFF1E88E5.toInt(), padH = 6) { renderAdd(true) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP)
                    .apply { topMargin = dp(4) }
            )

            val selectedGame = games.firstOrNull { it.id == selected }
            if (selectedGame != null) {
                // Thao tác với game đang chọn (tên game đã hiện ở cột trái nên không lặp lại ở đây).
                fun chip(label: String, color: Int, onClick: () -> Unit) = TextView(service).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = pill(color, 12)
                    setOnClickListener { onClick() }
                }
                list.addView(
                    LinearLayout(service).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        addView(chip("✎ Tên game", 0xFF00838F.toInt()) { renderRenameGame(selectedGame) })
                        addView(
                            chip("🗑 Xoá game", 0xFFC62828.toInt()) { renderDeleteGame(selectedGame) },
                            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) }
                        )
                        addView(
                            chip("＋ Luồng", 0xFF1E88E5.toInt()) { renderAdd(true, selectedGame.id) },
                            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) }
                        )
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP)
                        .apply { bottomMargin = dp(6) }
                )

                val gameFlows = allFlows.filter { it.gameId == selectedGame.id }
                val order = gameFlows.filter { it.enabled }
                    .mapIndexed { index, flow -> flow.id to index + 1 }
                    .toMap()
                if (gameFlows.isEmpty()) {
                    list.addView(
                        TextView(service).apply {
                            text = "Chưa có luồng. Bấm “＋ Luồng” ở trên."
                            setTextColor(0xFFBDBDBD.toInt())
                            textSize = 11f
                            setPadding(dp(4), dp(2), dp(4), dp(6))
                        }
                    )
                }
                subViews.clear()
                gameFlows.forEach { flow ->
                    val row = flowRow(
                        flow,
                        order[flow.id],
                        onCopy = { renderCopy(flow) },
                        onRename = { renderRename(flow) },
                        onChangeGame = { renderChangeGame(flow) },
                        onRepeat = { block -> renderRepeat(flow, block) },
                        onBranch = { branch -> renderBranch(flow, branch) },
                        onDelete = { renderDelete(flow) },
                        registerSub = { id, view -> subViews[id] = view },
                        onToggle = { refreshOrderLabels() }
                    ) { list.post { rebuild() } }
                    list.addView(
                        row,
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP)
                            .apply { bottomMargin = dp(4) }
                    )
                }
            }
        }
        rebuild()

        // Vừa chỉnh khung xong: mở lại đúng bảng sửa rẽ nhánh đó.
        pendingBranch?.let { (pendingFlowId, pendingBranchId) ->
            pendingBranch = null
            val pendingFlow = FlowStore.get(pendingFlowId)
            val pending = pendingFlow?.branches?.firstOrNull { it.id == pendingBranchId }
            if (pendingFlow != null && pending != null) renderBranch(pendingFlow, pending)
        }

        // Chỉ còn đếm ngược (mặc định 3 giây) bằng bộ −/＋ gọn; bỏ ô số vòng, luôn chạy một lượt.
        val runSettings = stepper("Đếm ngược (giây)", RunState.startDelaySec.value, 0, 60) { v ->
            RunState.startDelaySec.value = v
        }
        val start = button("▶ START", 0xFF43A047.toInt()) {
            onStart?.invoke(RunState.startDelaySec.value)
        }
        val app = button("Đóng app", 0xFFC62828.toInt()) { closeApp() }
        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(start, LinearLayout.LayoutParams(0, WRAP, 2f).apply { rightMargin = dp(6) })
            addView(app, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        // Chỉ phần danh sách + khung sửa cuộn được; tiêu đề, số vòng và nút START / Mở app luôn nằm
        // trong màn hình (kể cả khi xoay ngang, lúc màn hình rất thấp).
        val landscape = metrics.widthPixels > metrics.heightPixels
        val top = (panelY ?: if (landscape) dp(4) else dp(80))
            .coerceIn(0, (metrics.heightPixels - dp(FIXED_LAUNCHER_DP + 80)).coerceAtLeast(0))
        val maxScroll = (metrics.heightPixels - top - dp(FIXED_LAUNCHER_DP)).coerceAtLeast(dp(80))
        // Hai cột cuộn riêng: trái = danh sách game, phải = khung sửa + các luồng của game đang chọn.
        val leftScroll = boundedScroll(maxScroll).apply { addView(gamesList) }
        val rightScroll = boundedScroll(maxScroll).apply {
            addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(addHolder)
                    addView(list)
                }
            )
        }
        // Khung Sửa / Xoá / Sao chép nằm ở đầu cột phải: khi nó xuất hiện thì tự cuộn lên đó để thấy ngay
        // (không phải kéo lên tay), đóng khung xong thì trả về đúng vị trí đang xem.
        var editorOpen = false
        var savedScrollY = 0
        fun syncEditorScroll() {
            val open = addHolder.childCount > 0
            if (open && !editorOpen) {
                editorOpen = true
                savedScrollY = rightScroll.scrollY
                rightScroll.smoothScrollTo(0, 0)
            } else if (!open && editorOpen) {
                editorOpen = false
                rightScroll.scrollTo(0, savedScrollY)
            }
        }
        // post() gộp các lần xoá rồi thêm view liên tiếp (dựng lại khung) thành một lần xét.
        addHolder.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                addHolder.post { syncEditorScroll() }
            }

            override fun onChildViewRemoved(parent: View?, child: View?) {
                addHolder.post { syncEditorScroll() }
            }
        })
        addHolder.post { syncEditorScroll() }

        val columns = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                leftScroll,
                LinearLayout.LayoutParams(dp(LEFT_COLUMN_DP), WRAP).apply { rightMargin = dp(8) }
            )
            addView(rightScroll, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            background = pill(0xEE212121.toInt(), 16)
            addView(header)
            addView(columns, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP))
            addView(runSettings, rowParams())
            addView(actions, rowParams())
            releaseFocusOnOutsideTouch()
        }

        // Rộng hơn để có chỗ cho hai cột (tối đa 460dp, vừa khít màn hình nhỏ).
        val width = min(dp(460), metrics.widthPixels - dp(16))
        val lp = panelParams(width).apply {
            x = (panelX ?: ((metrics.widthPixels - width) / 2))
                .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            y = top
        }
        launcherIsPanel = true
        attachDrag(title, root, lp) {
            panelX = lp.x
            panelY = lp.y
        }
        safeAdd(root, lp)
        launcherView = root
    }

    /** Một game ở cột trái: radio (chọn game chạy) + tên + số luồng; bấm vào để xem các luồng bên phải. */
    private fun gameHeader(
        game: Game,
        selected: Boolean,
        flowCount: Int,
        onSelect: () -> Unit
    ): View {
        val radio = RadioButton(service).apply {
            buttonTintList = ColorStateList.valueOf(if (selected) 0xFF81C784.toInt() else Color.WHITE)
            isChecked = selected
            setOnClickListener { onSelect() }
        }
        val name = TextView(service).apply {
            text = game.name
            setTextColor(if (selected) 0xFF81C784.toInt() else Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val count = TextView(service).apply {
            text = "$flowCount luồng"
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 10f
        }
        val labels = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(count)
        }
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(4), dp(2))
            // Game đang chọn được tô nền để thấy luồng bên phải thuộc game nào.
            background = if (selected) pill(0x3381C784, 10) else null
            addView(radio)
            addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
            setOnClickListener { onSelect() }
        }
    }

    /** Dòng phụ dưới tên luồng: mũi tên mở rộng, số điểm và thứ tự chạy. */
    private fun subLabel(flow: ClickFlow, order: Int?, expanded: Boolean): String =
        (if (expanded) "▾ " else "▸ ") + "${flow.steps.size} điểm" + (order?.let { " • chạy thứ $it" } ?: "")

    /** Bộ chỉnh số bằng nút −/＋ (nhẹ hơn ô nhập). [onChange] nhận giá trị mới trong [min]..[max]. */
    private fun stepper(label: String, initial: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        var value = initial.coerceIn(min, max)
        val valueView = TextView(service).apply {
            text = value.toString()
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(28)
        }
        fun button(sign: String, delta: Int) = TextView(service).apply {
            text = sign
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pill(0xFF424242.toInt(), 8)
            setOnClickListener {
                value = (value + delta).coerceIn(min, max)
                valueView.text = value.toString()
                onChange(value)
            }
        }
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(service).apply {
                    text = label
                    setTextColor(0xFFBDBDBD.toInt())
                    textSize = 11f
                    setPadding(0, 0, dp(6), 0)
                }
            )
            addView(button("−", -1))
            addView(valueView)
            addView(button("＋", +1))
        }
    }

    /** Một dòng luồng: checkbox (tick = chạy), tên, số lần lặp của cả luồng, thứ tự, sửa vị trí. */
    private fun flowRow(
        flow: ClickFlow,
        order: Int?,
        onCopy: () -> Unit,
        onRename: () -> Unit,
        onChangeGame: () -> Unit,
        onRepeat: (RepeatBlock?) -> Unit,
        onBranch: (FlowBranch?) -> Unit,
        onDelete: () -> Unit,
        registerSub: (String, TextView) -> Unit,
        onToggle: () -> Unit,
        onChanged: () -> Unit
    ): View {
        val check = CheckBox(service).apply {
            buttonTintList = ColorStateList.valueOf(Color.WHITE)
            isChecked = flow.enabled
            setOnCheckedChangeListener { _, checked ->
                FlowStore.updateFlow(flow.id) { it.copy(enabled = checked) }
                // Chỉ cập nhật chữ "chạy thứ N" của các dòng, không dựng lại cả danh sách.
                onToggle()
            }
        }
        val name = TextView(service).apply {
            text = flow.name
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        val expanded = flow.id in expandedFlows
        val sub = TextView(service).apply {
            text = subLabel(flow, order, expanded)
            setTextColor(0xFFBDBDBD.toInt())
            textSize = 10f
            setSingleLine()
        }
        registerSub(flow.id, sub)
        // Chạm vào tên / số điểm để xem lại các điểm đã đặt của luồng.
        val info = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(sub)
            setOnClickListener {
                if (!expandedFlows.add(flow.id)) expandedFlows.remove(flow.id)
                onChanged()
            }
        }
        // Bộ −/＋ nhẹ thay cho ô nhập: ít view hơn, cuộn danh sách dài mượt hơn.
        val repeat = stepper("Lặp", flow.repeatCount, 1, 9999) { v ->
            FlowStore.updateFlow(flow.id) { it.copy(repeatCount = v) }
        }

        fun icon(label: String, onClick: () -> Unit) = TextView(service).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { onClick() }
        }

        // Hai dòng cho vừa cột phải: dòng 1 = checkbox + tên; dòng 2 = số lần lặp + các nút thao tác.
        val topRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
            addView(check)
            addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val bottomRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), dp(4))
            addView(repeat, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(View(service), LinearLayout.LayoutParams(0, 1, 1f))
            addView(icon("▲") { FlowStore.moveFlow(flow.id, -1); onChanged() })
            addView(icon("▼") { FlowStore.moveFlow(flow.id, +1); onChanged() })
            addView(icon("📍") { showEditor(flow.id) })
            addView(icon("⧉") { onCopy() })
        }
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(0x22FFFFFF, 10)
            addView(topRow)
            addView(bottomRow)
            if (expanded) addView(stepList(flow, onRename, onChangeGame, onCopy, onRepeat, onBranch, onDelete))
        }
    }

    /** Các điểm đã đặt của luồng; chạm vào một điểm để mở bảng cài đặt của nó trên màn hình. */
    private fun stepList(
        flow: ClickFlow,
        onRename: () -> Unit,
        onChangeGame: () -> Unit,
        onCopy: () -> Unit,
        onRepeat: (RepeatBlock?) -> Unit,
        onBranch: (FlowBranch?) -> Unit,
        onDelete: () -> Unit
    ): View = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(8), dp(6))
        // Các thao tác với cả luồng: đổi tên, đổi game, sao chép, xoá.
        addView(flowActions(onRename, onChangeGame, onCopy, onDelete), rowParams())
        if (flow.steps.isEmpty()) {
            addView(
                TextView(service).apply {
                    text = "Chưa đặt điểm nào. Bấm 📍 để đặt."
                    setTextColor(0xFFBDBDBD.toInt())
                    textSize = 11f
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
            )
            return@apply
        }
        addView(
            TextView(service).apply {
                text = "Chạm vào một điểm để chỉnh trên màn hình"
                setTextColor(0xFF9E9E9E.toInt())
                textSize = 10f
                setPadding(dp(4), 0, dp(4), dp(2))
            }
        )
        val groups = flow.resolvedRepeats()
        flow.steps.forEachIndexed { index, step ->
            val inGroup = groups.any { index in it.start..it.end }
            addView(
                TextView(service).apply {
                    text = (if (inGroup) "↻ " else "") + describeStep(index, step)
                    setTextColor(if (step.isHold) 0xFFFFB74D.toInt() else Color.WHITE)
                    textSize = 11f
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    setOnClickListener {
                        showEditor(flow.id)
                        showSettings(step.id)
                    }
                }
            )
        }

        // Nhóm lặp: chạy các bước từ a đến b n lần rồi mới sang bước kế tiếp.
        val validIds = groups.map { it.block.id }.toSet()
        groups.forEach { g ->
            addView(
                TextView(service).apply {
                    text = "↻ Nhóm lặp: bước ${g.start + 1}–${g.end + 1} × ${g.block.count} lần  (chạm để sửa)"
                    setTextColor(0xFF81C784.toInt())
                    textSize = 11f
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    setOnClickListener { onRepeat(g.block) }
                }
            )
        }
        flow.repeats.filter { it.id !in validIds }.forEach { block ->
            addView(
                TextView(service).apply {
                    text = "⚠ Nhóm lặp không hợp lệ, sẽ bị bỏ qua  (chạm để sửa)"
                    setTextColor(0xFFFFB74D.toInt())
                    textSize = 11f
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    setOnClickListener { onRepeat(block) }
                }
            )
        }
        addView(
            TextView(service).apply {
                text = "＋ Thêm nhóm lặp"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = pill(0xFF2E7D32.toInt(), 12)
                setOnClickListener { onRepeat(null) }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) }
        )

        // Rẽ nhánh theo chữ: sau bước N, thấy chữ nào thì chỉ chạy các bước của nhánh đó.
        flow.branches.forEach { branch ->
            val position = flow.branchPosition(branch)
            val summary = branch.rules.filter { it.text.isNotBlank() }.joinToString("  |  ") { rule ->
                val numbers = rule.stepIds
                    .mapNotNull { id -> flow.steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) }
                    .toStepNumberText()
                "“${rule.text}” → ${numbers.ifBlank { "(chưa chọn bước)" }}"
            }.ifBlank { "(chưa đặt chữ)" }
            addView(
                TextView(service).apply {
                    text = if (position < 0) {
                        "⚠ Rẽ nhánh mất bước gốc  (chạm để sửa)"
                    } else {
                        "◈ Sau bước $position: $summary  (chạm để sửa)"
                    }
                    setTextColor(if (position < 0) 0xFFFFB74D.toInt() else 0xFF4FC3F7.toInt())
                    textSize = 11f
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    setOnClickListener { onBranch(branch) }
                }
            )
        }
        addView(
            TextView(service).apply {
                text = "＋ Thêm rẽ nhánh theo chữ"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = pill(0xFF0277BD.toInt(), 12)
                setOnClickListener { onBranch(null) }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) }
        )

    }

    /** Hai hàng nút: ✎ Đổi tên | ⇄ Đổi game, rồi ⧉ Sao chép | 🗑 Xoá. */
    private fun flowActions(
        onRename: () -> Unit,
        onChangeGame: () -> Unit,
        onCopy: () -> Unit,
        onDelete: () -> Unit
    ): View {
        fun pair(a: View, b: View) = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) })
            addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            addView(pair(button("✎ Đổi tên", 0xFF00838F.toInt(), padH = 6) { onRename() },
                button("⇄ Đổi game", 0xFF0277BD.toInt(), padH = 6) { onChangeGame() }))
            addView(
                pair(button("⧉ Sao chép", 0xFF43A047.toInt(), padH = 6) { onCopy() },
                    button("🗑 Xoá", 0xFFC62828.toInt(), padH = 6) { onDelete() }),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP)
                    .apply { topMargin = dp(4) }
            )
        }
    }

    private fun describeStep(index: Int, step: Step): String {
        val kind = when {
            step.isSwipe -> "Vuốt ${step.swipeMs} ms → (${step.endX}, ${step.endY})"
            step.isHold -> "Giữ ${step.holdMs} ms"
            else -> "Click"
        }
        var text = "${index + 1}. $kind  từ (${step.x}, ${step.y})  • chờ ${step.delayMs} ms"
        if (step.clicks > 1) text += " • ×${step.clicks}"
        if (step.waitText.isNotBlank()) text += " • chữ “${step.waitText}”"
        if (step.days and ALL_DAYS != ALL_DAYS) text += " • ${step.days.daysLabel()}"
        return text
    }

    // ---------------------------------------------------------------- helpers

    /** ScrollView không cao quá [maxHeightPx]. */
    private fun boundedScroll(maxHeightPx: Int): ScrollView = object : ScrollView(service) {
        init {
            // Bỏ hiệu ứng kéo giãn / mờ viền: mỗi khung hình cuộn nhẹ hơn.
            overScrollMode = OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
            )
        }
    }

    /**
     * Cửa sổ bảng: mặc định KHÔNG nhận focus nên chạm ngoài bảng vẫn xuyên xuống app phía sau.
     * Chỉ khi chạm vào ô nhập mới tạm nhận focus để hiện bàn phím (xem [enableTyping]).
     */
    private fun panelParams(w: Int): WindowManager.LayoutParams =
        overlayParams(w, WRAP).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    /** Chạm vào ô nhập thì cho cửa sổ chứa nó nhận focus; xong (Done / chạm ra ngoài) trả lại. */
    private fun EditText.enableTyping() {
        imeOptions = EditorInfo.IME_ACTION_DONE
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) allowTyping(v)
            false
        }
        setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                stopTyping(v.rootView)
                true
            } else {
                false
            }
        }
    }

    private fun allowTyping(v: View) {
        val root = v.rootView
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try {
            wm.updateViewLayout(root, lp)
        } catch (_: Exception) {
        }
    }

    private fun stopTyping(root: View) {
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        root.findFocus()?.clearFocus()
        val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
        if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            wm.updateViewLayout(root, lp)
        } catch (_: Exception) {
        }
    }

    /** Chạm ra ngoài bảng (xuống app phía sau) thì đóng bàn phím và trả lại cửa sổ không-focus. */
    private fun View.releaseFocusOnOutsideTouch() {
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) stopTyping(v)
            false
        }
    }

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Cửa sổ thêm bằng WindowManager mặc định vẽ bằng CPU (rất giật khi cuộn / có độ trong
                // suốt); bật vẽ bằng GPU như Activity.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun pill(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun button(
        label: String,
        color: Int,
        padH: Int = 12,
        onClick: () -> Unit
    ): TextView =
        TextView(service).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(padH), dp(8), dp(padH), dp(8))
            background = pill(color, 16)
            setOnClickListener { onClick() }
        }

    /**
     * Kéo [handle] để di chuyển cả cửa sổ [root].
     * Chạm nhẹ (không kéo) gọi [onTap] nếu có; nhả tay sau khi kéo gọi [onUp].
     */
    private fun attachDrag(
        handle: View,
        root: View,
        lp: WindowManager.LayoutParams,
        onTap: (() -> Unit)? = null,
        onUp: () -> Unit
    ) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                    if (moved) {
                        val nx = startX + dx.toInt()
                        val ny = startY + dy.toInt()
                        if (nx != lp.x || ny != lp.y) {
                            lp.x = nx
                            lp.y = ny
                            try {
                                wm.updateViewLayout(root, lp)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && onTap != null) onTap() else onUp()
                    true
                }
                else -> false
            }
        }
    }

    /** Thêm cửa sổ nổi; dịch vụ đã ngắt thì addView có thể ném lỗi nên không để crash. */
    private fun safeAdd(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
        }
    }

    private fun safeRemove(v: View?) {
        if (v == null) return
        try {
            wm.removeView(v)
        } catch (e: IllegalArgumentException) {
            // View chưa/không còn được gắn vào cửa sổ: bỏ qua.
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
    }

    private companion object {
        const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT
        const val TAG = "AutoClick.Overlay"

        /** Chiều cao (dp) dành cho phần cố định của bảng cài đặt điểm: tiêu đề + hàng nút + khoảng đệm. */
        const val FIXED_PANEL_DP = 130

        /** Như trên cho bảng luồng: tiêu đề + số vòng / đếm ngược + START / Mở app + khoảng đệm. */
        const val FIXED_LAUNCHER_DP = 170

        /** Chiều rộng (dp) cột game bên trái của bảng luồng. */
        const val LEFT_COLUMN_DP = 108

        /** Hậu tố khoá của chấm đích điểm vuốt trong markerViews. */
        const val END_SUFFIX = ":end"
    }
}
