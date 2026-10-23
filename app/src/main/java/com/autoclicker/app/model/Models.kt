package com.autoclicker.app.model

import androidx.compose.runtime.Immutable
import java.util.Calendar
import java.util.UUID

/**
 * Một bước click.
 *
 * Thứ tự chạy của bước = thứ tự trong danh sách steps của luồng.
 * Nếu [waitText] khác rỗng: app đọc chữ (OCR) trong vùng quanh (x, y) cho tới khi
 * thấy đúng chữ đó rồi mới click.
 */
@Immutable
data class Step(
    val id: String = UUID.randomUUID().toString(),
    val x: Int,
    val y: Int,
    /** Chờ bao lâu (ms) trước khi bắt đầu bước này. */
    val delayMs: Long = 1000,
    /** Số lần click liên tiếp. */
    val clicks: Int = 1,
    /** Khoảng cách giữa các lần click liên tiếp (ms). */
    val repeatIntervalMs: Long = 300,
    /** Chữ cần thấy trên nút trước khi click. Rỗng = click luôn, không chờ. */
    val waitText: String = "",
    /** true: chữ đọc được phải bằng đúng waitText; false: chỉ cần chứa waitText. */
    val exactMatch: Boolean = false,
    /** Vùng đọc chữ (px), căn giữa tại (x, y). */
    val regionW: Int = 400,
    val regionH: Int = 160,
    /** Thời gian chờ tối đa (ms) cho chữ xuất hiện. 0 = chờ vô hạn. Hết giờ thì bỏ qua bước. */
    val timeoutMs: Long = 0,
    /** true: điểm giữ — đặt ngón tay tại (x, y) trong [holdMs] thay vì chạm nhanh. */
    val isHold: Boolean = false,
    /** Thời gian giữ (ms) của điểm giữ. */
    val holdMs: Long = 1000,
    /** true: điểm vuốt — kéo ngón tay từ (x, y) tới ([endX], [endY]) trong [swipeMs]. */
    val isSwipe: Boolean = false,
    val endX: Int = 0,
    val endY: Int = 0,
    /** Thời gian vuốt (ms) của điểm vuốt. */
    val swipeMs: Long = 400,
    /** Các ngày trong tuần bước này được chạy (bitmask, xem [ALL_DAYS]). */
    val days: Int = ALL_DAYS,
    /**
     * Chỉ dùng khi [waitText] không rỗng. true: không thấy chữ thì bỏ qua bước ngay (không chờ);
     * nếu có [timeoutMs] > 0 thì chờ tối đa chừng đó rồi bỏ qua.
     */
    val skipIfAbsent: Boolean = false,
    /** true: bước này chỉ chạy khi bước ngay trước nó trong luồng đã thực sự chạy (nút trước kích hoạt). */
    val requirePrevious: Boolean = false,
    /**
     * Chỉ dùng cho điểm vuốt. true: "vuốt bừa" — một nét liền đi ngẫu nhiên qua các ô của lưới
     * [gridCols] x [gridRows] có hai góc đối diện là (x, y) và ([endX], [endY]), như tay người nối các ô.
     * Mỗi đoạn giữa hai ô mất [swipeMs] ms.
     */
    val isScribble: Boolean = false,
    val gridCols: Int = 3,
    val gridRows: Int = 3,
    /**
     * Chỉ dùng khi [waitText] không rỗng. true: sau mỗi lần thực hiện bước, đọc lại khung chữ; còn thấy
     * chữ thì làm lại, tới khi chữ biến mất (hoặc đủ [loopMax] lần) mới sang bước kế.
     */
    val repeatWhileText: Boolean = false,
    val loopMax: Int = 200,
    /** true: khung đọc chữ có tâm riêng ([regionX], [regionY]) thay vì quanh (x, y); cỡ vẫn là regionW x regionH. */
    val ownRegion: Boolean = false,
    val regionX: Int = 0,
    val regionY: Int = 0,
    /**
     * Chỉ dùng cho điểm giữ. true: giữ nền — đặt ngón tay xuống rồi KHÔNG nhả, các bước sau vẫn chạy
     * trong lúc đang giữ; các lần sau gặp lại bước này thì bỏ qua (vẫn đang giữ). Nhả khi hết luồng / dừng.
     */
    val holdBackground: Boolean = false
)

/** Toạ độ ô (cột, hàng) của lưới điểm vuốt bừa: các ô nằm đều giữa hai góc (x, y) và (endX, endY). */
fun Step.gridNode(col: Int, row: Int): Pair<Float, Float> {
    val cols = gridCols.coerceAtLeast(1)
    val rows = gridRows.coerceAtLeast(1)
    val px = if (cols == 1) x.toFloat() else x + (endX - x) * col / (cols - 1f)
    val py = if (rows == 1) y.toFloat() else y + (endY - y) * row / (rows - 1f)
    return px to py
}

/** Các phép so sánh của nhánh theo số: đọc số trong khung rồi so với [BranchRule.threshold]. */
val COMPARE_OPS = listOf(">=", "<=", ">", "<", "=")

/**
 * Một nhánh của [FlowBranch]: khi điều kiện đúng thì chỉ chạy các bước trong [stepIds].
 * Điều kiện là một trong hai loại:
 *  - theo chữ ([compare] rỗng): thấy [text] trong khung;
 *  - theo số ([compare] khác rỗng): số đọc được trong khung thoả `số [compare] [threshold]`,
 *    ví dụ thanh nộ >= 100.
 */
@Immutable
data class BranchRule(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    /** true: khung phải chứa nguyên cụm [text], không chịu OCR đọc lệch; false: gần đúng (chịu đọc lệch). */
    val exactMatch: Boolean = false,
    /** Các bước (theo id) sẽ chạy khi điều kiện đúng; luôn chạy theo thứ tự trong luồng. */
    val stepIds: List<String> = emptyList(),
    /**
     * true ("nhảy tới bước"): khi nhánh được chọn, các bước từ điểm rẽ tới trước bước đầu của
     * [stepIds] mà không nằm trong [stepIds] cũng bị bỏ qua, tức là nhảy thẳng tới bước đó
     * (các bước sau [stepIds] vẫn chạy tiếp bình thường).
     */
    val jump: Boolean = false,
    /** Rỗng = điều kiện theo chữ; một trong [COMPARE_OPS] = điều kiện theo số. */
    val compare: String = "",
    val threshold: Int = 0,
    /** true: dùng khung riêng ([x], [y], [w], [h]) thay vì khung chung của [FlowBranch]. */
    val ownRegion: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 400,
    val h: Int = 160,
    /**
     * > 0: khi nhánh này được chọn tới lần thứ N (tính cộng dồn trong một lượt chạy của luồng) thì
     * chạy nốt lần cuối rồi dừng luồng, không cần đủ số lần lặp. 0 = tắt.
     */
    val finishAfter: Int = 0
)

/** Điều kiện theo số? */
fun BranchRule.isNumeric(): Boolean = compare.isNotBlank()

/** Đã nhập đủ để dùng được: có chữ cần thấy (loại chữ) hoặc luôn dùng được (loại số). */
fun BranchRule.isConfigured(): Boolean = if (isNumeric()) true else text.isNotBlank()

/** Mô tả ngắn để hiển thị: “Quay về” hoặc số >= 100. */
fun BranchRule.describe(): String =
    if (isNumeric()) {
        "số $compare $threshold"
    } else {
        "“${text.ifBlank { "(chưa đặt chữ)" }}” (${if (exactMatch) "đúng chữ" else "gần đúng"})"
    }

/** So sánh số đọc được [value] với [threshold] theo phép [op] (một trong [COMPARE_OPS]). */
fun compareNumber(value: Int, op: String, threshold: Int): Boolean = when (op) {
    ">=" -> value >= threshold
    "<=" -> value <= threshold
    ">" -> value > threshold
    "<" -> value < threshold
    "=" -> value == threshold
    else -> false
}

/** Khi không nhánh nào đúng: chạy tiếp tất cả các bước. */
const val NO_MATCH_CONTINUE = 0

/** Khi không nhánh nào đúng: bỏ qua các bước thuộc nhánh, chỉ chạy bước không thuộc nhánh nào. */
const val NO_MATCH_SKIP_BRANCH_STEPS = 1

/** Khi không nhánh nào đúng: dừng phần còn lại của luồng. */
const val NO_MATCH_STOP = 2

/**
 * Điểm rẽ nhánh: ngay sau bước [afterStepId] (rỗng = ngay đầu luồng), app đọc màn hình (chụp một lần,
 * mỗi nhánh đọc khung của nó, mặc định là khung chung tâm ([x], [y]) rộng [w] cao [h]). Nhánh nào
 * đúng trước (theo thứ tự [rules]) thì các bước thuộc nhánh khác bị bỏ qua; bước không thuộc nhánh
 * nào vẫn chạy bình thường.
 */
@Immutable
data class FlowBranch(
    val id: String = UUID.randomUUID().toString(),
    val afterStepId: String = "",
    val x: Int,
    val y: Int,
    val w: Int = 400,
    val h: Int = 160,
    /** Chờ tối đa (ms) cho một nhánh đúng; trong lúc chờ app đọc lại liên tục. */
    val waitMs: Long = 3000,
    /** Xem [NO_MATCH_CONTINUE], [NO_MATCH_SKIP_BRANCH_STEPS], [NO_MATCH_STOP]. */
    val noMatchAction: Int = NO_MATCH_CONTINUE,
    /** Một nhánh phải đúng liên tiếp chừng này lần đọc mới được chọn (tránh đọc nhầm một lần). */
    val confirmReads: Int = 1,
    val rules: List<BranchRule> = emptyList()
)

/** Vị trí rẽ nhánh (0-based): nhánh được xét ngay trước bước ở chỉ số này. -1 nếu bước gốc không còn. */
fun ClickFlow.branchPosition(branch: FlowBranch): Int =
    if (branch.afterStepId.isBlank()) {
        0
    } else {
        val index = steps.indexOfFirst { it.id == branch.afterStepId }
        if (index < 0) -1 else index + 1
    }

/**
 * Đọc danh sách bước người dùng gõ ("4-8", "4,5,6", "4 8 9") thành các số bước 1-based, không
 * trùng, tăng dần, nằm trong 1..[size].
 */
fun parseStepNumbers(text: String, size: Int): List<Int> {
    val result = sortedSetOf<Int>()
    text.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
        val range = token.split('-', '–', '—')
        val from = range.getOrNull(0)?.toIntOrNull()
        val to = if (range.size > 1) range[1].toIntOrNull() else from
        if (from != null && to != null) {
            for (n in minOf(from, to)..maxOf(from, to)) {
                if (n in 1..size) result += n
            }
        }
    }
    return result.toList()
}

/** Ngược lại của [parseStepNumbers]: [4,5,6,7,8,10] -> "4-8,10". */
fun List<Int>.toStepNumberText(): String {
    val sorted = this.distinct().sorted()
    if (sorted.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var start = sorted[0]
    var prev = start
    for (n in sorted.drop(1) + (Int.MAX_VALUE)) {
        if (n == prev + 1) {
            prev = n
            continue
        }
        parts += if (start == prev) "$start" else if (prev == start + 1) "$start,$prev" else "$start-$prev"
        start = n
        prev = n
    }
    return parts.joinToString(",")
}

/** Một game: cấp cha của các luồng, để dễ nhận dạng. Chỉ game đang chọn (radio) mới chạy. */
@Immutable
data class Game(
    val id: String = UUID.randomUUID().toString(),
    val name: String
)

/** Bitmask 7 ngày: bit 0 = Thứ 2 ... bit 6 = Chủ nhật. */
const val ALL_DAYS = 0b1111111

val DAY_LABELS = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

/** Bit của ngày hôm nay theo đồng hồ thiết bị. */
fun todayDayBit(): Int {
    // Calendar: CN = 1, T2 = 2 ... T7 = 7 -> đổi về T2 = 0 ... CN = 6.
    val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return 1 shl ((dow + 5) % 7)
}

fun Int.hasDay(index: Int): Boolean = this and (1 shl index) != 0

fun Int.toggledDay(index: Int): Int = this xor (1 shl index)

/** Mô tả ngắn: "Mọi ngày" hoặc "T2, T3, CN". */
fun Int.daysLabel(): String = when {
    this and ALL_DAYS == ALL_DAYS -> "Mọi ngày"
    this and ALL_DAYS == 0 -> "Không ngày nào"
    else -> DAY_LABELS.filterIndexed { i, _ -> hasDay(i) }.joinToString(", ")
}

fun Step.isActiveToday(): Boolean = days and todayDayBit() != 0

/** Một luồng click có tên. [enabled] chính là checkbox ở màn hình chính. */
@Immutable
data class ClickFlow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Game chứa luồng này. */
    val gameId: String = "",
    val enabled: Boolean = false,
    /** Số lần lặp lại luồng này (liên tiếp) trước khi sang luồng kế tiếp. Tối thiểu 1. */
    val repeatCount: Int = 1,
    val steps: List<Step> = emptyList(),
    /** Các nhóm bước lặp lại ngay trong luồng (xem [RepeatBlock]). */
    val repeats: List<RepeatBlock> = emptyList(),
    /** Các điểm rẽ nhánh theo chữ của luồng (xem [FlowBranch]). */
    val branches: List<FlowBranch> = emptyList()
)

/**
 * Nhóm bước lặp: chạy các bước từ [startStepId] tới [endStepId] (theo thứ tự trong luồng)
 * [count] lần liên tiếp rồi mới sang bước kế tiếp. Dùng id bước nên không lệch khi đổi thứ tự.
 */
@Immutable
data class RepeatBlock(
    val id: String = UUID.randomUUID().toString(),
    val startStepId: String,
    val endStepId: String,
    val count: Int = 2
)

/** Nhóm lặp đã quy ra chỉ số bước (0-based, gồm cả [start] và [end]). */
data class ResolvedRepeat(val block: RepeatBlock, val start: Int, val end: Int)

/**
 * Các nhóm lặp hợp lệ, xếp theo vị trí. Nhóm trỏ tới bước không còn, bị đảo ngược, hoặc
 * chồng lên nhóm đứng trước sẽ bị bỏ qua.
 */
fun ClickFlow.resolvedRepeats(): List<ResolvedRepeat> {
    val result = mutableListOf<ResolvedRepeat>()
    var lastEnd = -1
    repeats
        .mapNotNull { block ->
            val start = steps.indexOfFirst { it.id == block.startStepId }
            val end = steps.indexOfFirst { it.id == block.endStepId }
            if (start < 0 || end < start) null else ResolvedRepeat(block, start, end)
        }
        .sortedBy { it.start }
        .forEach { r ->
            if (r.start > lastEnd) {
                result += r
                lastEnd = r.end
            }
        }
    return result
}
