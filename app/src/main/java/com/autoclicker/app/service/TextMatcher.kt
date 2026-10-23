package com.autoclicker.app.service

import java.text.Normalizer

/** Kết quả OCR của một vùng: toàn bộ chữ và từng dòng. */
data class OcrResult(val full: String, val lines: List<String>)

object TextMatcher {

    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    /**
     * Chuẩn hoá để so khớp: bỏ hoa/thường, bỏ dấu tiếng Việt, bỏ mọi khoảng trắng.
     * "Bắt Đầu" và "bat dau" sẽ cho cùng một kết quả.
     */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val noMarks = marks.replace(decomposed, "").replace('đ', 'd')
        return spaces.replace(noMarks, "")
    }

    /**
     * Như [normalize] nhưng còn bỏ ký tự không phải chữ/số và gộp các ký tự OCR hay đọc nhầm
     * (0↔o, 1/i/|↔l, 5↔s, 2↔z, 8↔b) về cùng một ký tự, để "VlCT0RY" vẫn khớp "Victory".
     */
    private fun fold(s: String): String {
        val base = normalize(s)
        val out = StringBuilder(base.length)
        for (c in base) {
            when {
                c == '0' -> out.append('o')
                c == '1' || c == 'i' || c == '|' || c == '!' -> out.append('l')
                c == '5' || c == '$' -> out.append('s')
                c == '2' -> out.append('z')
                c == '8' -> out.append('b')
                c.isLetterOrDigit() -> out.append(c)
                // Bỏ dấu câu / ký hiệu lạ.
            }
        }
        return out.toString()
    }

    /** Ký tự có thể là một chữ số bị đọc nhầm; dùng khi lấy số từ khung (thanh nộ, điểm...). */
    private const val NUMBER_CHARS = "0123456789oOlI|SBZ"

    private fun digitFor(c: Char): Char = when (c) {
        'o', 'O' -> '0'
        'l', 'I', '|' -> '1'
        'S' -> '5'
        'B' -> '8'
        'Z' -> '2'
        else -> c
    }

    /**
     * Lấy một số nguyên từ chữ OCR đọc được trong khung, ví dụ "88", "88/150" (lấy 88), "8 8" (88),
     * "Nộ 14". Chịu các ký tự hay bị đọc nhầm thành số (O→0, l/I→1, S→5, B→8, Z→2) nhưng chỉ khi
     * chúng nằm sát chữ số nên "Level 5" vẫn đọc ra 5. null nếu không thấy số nào.
     */
    fun parseNumber(text: String): Int? {
        // "88/150": chỉ lấy phần trước dấu gạch, đó là giá trị hiện tại.
        val head = text.substringBefore('/')
        // Khung chỉ chứa số bị OCR tách khoảng trắng ("1 4") thì gộp lại.
        val compact = if (head.all { it.isWhitespace() || it in NUMBER_CHARS }) {
            head.filterNot { it.isWhitespace() }
        } else {
            head
        }
        val token = Regex("[$NUMBER_CHARS]+").findAll(compact)
            .map { it.value }
            .firstOrNull { t -> t.any { it.isDigit() } }
            ?: return null
        return token.map { digitFor(it) }.joinToString("").take(9).toIntOrNull()
    }

    /** Số ký tự sai lệch cho phép: chữ ngắn phải khớp đúng, chữ dài được lệch 1–2 ký tự. */
    private fun allowedEdits(length: Int): Int = when {
        length < 4 -> 0
        length < 8 -> 1
        else -> 2
    }

    /**
     * Kiểm tra [wanted] có xuất hiện trong [ocr] không. Hai chế độ:
     *  - [exact] = true ("đúng chữ"): khung phải chứa nguyên cụm [wanted] sau chuẩn hoá ([normalize]);
     *    chữ dính thêm xung quanh (vd "(5sac)") không sao, nhưng không gộp ký tự hay nhầm và
     *    không cho lệch ký tự nào.
     *  - [exact] = false ("gần đúng"): như trên, nếu không khớp thì thử tiếp: khớp sau khi gộp ký tự
     *    hay nhầm -> khớp mờ (lệch vài ký tự).
     */
    fun matches(ocr: OcrResult, wanted: String, exact: Boolean): Boolean {
        val w = normalize(wanted)
        if (w.isEmpty()) return false

        val full = normalize(ocr.full)
        if (full.contains(w)) return true
        if (exact) return false

        val fw = fold(wanted)
        if (fw.isEmpty()) return false
        val fFull = fold(ocr.full)
        val edits = allowedEdits(fw.length)
        return fFull.contains(fw) || (edits > 0 && approxContains(fFull, fw, edits))
    }

    /** true nếu [pattern] xuất hiện trong [text] với tối đa [maxEdits] ký tự sai lệch (Sellers). */
    private fun approxContains(text: String, pattern: String, maxEdits: Int): Boolean {
        val m = pattern.length
        if (m == 0) return true
        var prev = IntArray(m + 1) { it }
        for (c in text) {
            val cur = IntArray(m + 1)
            cur[0] = 0
            for (j in 1..m) {
                val cost = if (pattern[j - 1] == c) 0 else 1
                cur[j] = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
            }
            if (cur[m] <= maxEdits) return true
            prev = cur
        }
        return false
    }
}
