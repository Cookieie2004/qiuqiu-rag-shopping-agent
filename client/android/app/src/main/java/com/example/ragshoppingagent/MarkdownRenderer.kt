package com.example.ragshoppingagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Markdown → SpannableStringBuilder 渲染器。
 * 支持标题、列表、引用/高亮块、表格、行内加粗/斜体/代码。
 */
class MarkdownRenderer(private val ctx: Context) {

    private val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    private val mdHeadingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val mdListRegex = Regex("^([-*+]|\\d+[.)])\\s+(.*)$")
    private val mdTableSepRegex = Regex("^:?-{2,}:?$")
    private val mdImageRegex = Regex("^!\\[([^\\]]*)\\]\\(([^)]+)\\)$")

    // ── 颜色常量（与 MainActivity 同源）────────────────────
    private val colorPrimaryDark get() = ctx.getColor(R.color.app_primary_dark)
    private val colorPrimary get() = ctx.getColor(R.color.app_primary)
    private val colorText get() = ctx.getColor(R.color.app_text)
    private val colorBorder get() = ctx.getColor(R.color.app_border)

    /** 全宽背景色 Span：覆盖整行宽度，带圆角和内边距 */
    class FullWidthBackgroundSpan(
        private val color: Int,
        private val paddingPx: Int = 0,
        private val radiusPx: Float = 0f,
    ) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, paint: Paint,
            left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lineNumber: Int,
        ) {
            val spanned = text as? Spanned
            val spanStart = spanned?.getSpanStart(this) ?: 0
            val spanEnd = spanned?.getSpanEnd(this) ?: text.length
            val isFirstLine = start <= spanStart
            val isLastLine = end >= spanEnd

            val oldColor = paint.color
            paint.color = color
            val rect = RectF(
                left.toFloat() - paddingPx,
                top.toFloat() - (if (isFirstLine) paddingPx else 0),
                right.toFloat() + paddingPx,
                bottom.toFloat() + (if (isLastLine) paddingPx else 0),
            )
            if (radiusPx > 0) {
                val path = android.graphics.Path()
                val tl = if (isFirstLine) radiusPx else 0f
                val tr = if (isFirstLine) radiusPx else 0f
                val br = if (isLastLine) radiusPx else 0f
                val bl = if (isLastLine) radiusPx else 0f
                path.addRoundRect(rect, floatArrayOf(tl,tl, tr,tr, br,br, bl,bl), android.graphics.Path.Direction.CW)
                canvas.drawPath(path, paint)
            } else {
                canvas.drawRect(rect, paint)
            }
            paint.color = oldColor
        }
    }

    /** API 28+ 用宽 QuoteSpan，低版本用默认 */
    fun makeQuoteSpan(color: Int): QuoteSpan {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            QuoteSpan(color, dp(4), dp(12))
        } else {
            QuoteSpan(color)
        }
    }

    fun render(md: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val lines = md.split("\n")
        var i = 0
        var pendingGap = false

        fun newlineBefore() {
            if (out.isNotEmpty()) {
                out.append("\n")
                if (pendingGap) {
                    val gapStart = out.length
                    out.append("\n")
                    out.setSpan(RelativeSizeSpan(0.45f), gapStart, out.length, SPAN)
                }
            }
            pendingGap = false
        }

        while (i < lines.size) {
            val line = lines[i].trim()

            // 图片行：渲染为占位文字
            if (line.startsWith("![")) {
                val imgMatch = mdImageRegex.find(line)
                if (imgMatch != null) {
                    newlineBefore()
                    val start = out.length
                    out.append("\uD83D\uDCF7 图片")
                    out.setSpan(ForegroundColorSpan(0xFF999999.toInt()), start, out.length, SPAN)
                    out.setSpan(RelativeSizeSpan(0.85f), start, out.length, SPAN)
                    i++
                    continue
                }
            }

            // 空行 → 段落间距
            if (line.isEmpty()) {
                if (out.isNotEmpty()) pendingGap = true
                i++
                continue
            }

            // 表格块
            if (line.startsWith("|")) {
                val tbl = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    tbl.add(lines[i].trim())
                    i++
                }
                newlineBefore()
                appendTable(out, tbl)
                continue
            }

            // 引用块
            if (line.startsWith(">")) {
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                pendingGap = true
                newlineBefore()

                val quoteText = quote.joinToString("\n")
                val isHighlight = quoteText.matches(Regex("^\\*\\*[^*]+\\*\\*.*", RegexOption.DOT_MATCHES_ALL))

                if (isHighlight && out.isNotEmpty()) {
                    val extraGap = out.length
                    out.append("\n")
                    out.setSpan(RelativeSizeSpan(0.6f), extraGap, out.length, SPAN)
                }

                val start = out.length
                val topPadSize = if (isHighlight) 0.4f else 0.35f
                val padStart = out.length
                out.append("\n")
                out.setSpan(RelativeSizeSpan(topPadSize), padStart, out.length, SPAN)

                if (isHighlight) {
                    out.append("\uD83D\uDCA1 ")
                    val titleEnd = quoteText.indexOf("：")
                    if (titleEnd >= 0 && titleEnd < quoteText.length - 1) {
                        appendInline(out, quoteText.substring(0, titleEnd + 1))
                        out.append("\n")
                        appendInline(out, quoteText.substring(titleEnd + 1))
                    } else {
                        appendInline(out, quoteText)
                    }
                } else {
                    appendInline(out, quoteText)
                }

                val padEnd = out.length
                out.append("\n")
                out.setSpan(RelativeSizeSpan(if (isHighlight) 0.55f else 0.35f), padEnd, out.length, SPAN)
                pendingGap = true

                val starMatch = Regex("★(\\d)").find(quoteText)

                if (isHighlight) {
                    out.setSpan(LeadingMarginSpan.Standard(dp(12), dp(12)), start, out.length, SPAN)
                    out.setSpan(FullWidthBackgroundSpan(0xFFFFFDE7.toInt(), dp(8), dp(10).toFloat()), start, out.length, SPAN)
                    out.setSpan(ForegroundColorSpan(colorText), start, out.length, SPAN)
                } else {
                    out.setSpan(FullWidthBackgroundSpan(0xFFF5F5F5.toInt(), dp(4), dp(8).toFloat()), start, out.length, SPAN)
                    out.setSpan(ForegroundColorSpan(0xFF555555.toInt()), start, out.length, SPAN)
                    val quoteColor = if (starMatch != null && starMatch.groupValues[1].toInt() <= 3) {
                        0xFFFF9500.toInt()
                    } else {
                        colorPrimary
                    }
                    out.setSpan(makeQuoteSpan(quoteColor), start, out.length, SPAN)
                }
                // 给所有 ★N 上色（在渲染后的 SpannableStringBuilder 中搜索，避免偏移量问题）
                val rendered = out.toString()
                var searchFrom = start
                while (searchFrom < out.length) {
                    val idx = rendered.indexOf('★', searchFrom)
                    if (idx < 0 || idx >= out.length) break
                    val matchEnd = idx + 2  // ★ + 数字
                    if (matchEnd <= out.length && idx + 1 < rendered.length && rendered[idx + 1].isDigit()) {
                        out.setSpan(ForegroundColorSpan(0xFFFF9500.toInt()), idx, matchEnd, SPAN)
                        out.setSpan(RelativeSizeSpan(0.85f), idx, matchEnd, SPAN)
                    }
                    searchFrom = idx + 1
                }
                continue
            }

            // 标题
            val heading = mdHeadingRegex.find(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                pendingGap = false
                if (out.isNotEmpty()) {
                    out.append("\n")
                    val gapStart = out.length
                    out.append("\n")
                    val gapSize = when (level) { 1 -> 0.6f; 2 -> 0.45f; else -> 0.2f }
                    out.setSpan(RelativeSizeSpan(gapSize), gapStart, out.length, SPAN)
                }
                val start = out.length
                appendInline(out, heading.groupValues[2].trim())
                val scale = when (level) { 1 -> 1.45f; 2 -> 1.25f; else -> 1.1f }
                out.setSpan(RelativeSizeSpan(scale), start, out.length, SPAN)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                out.setSpan(ForegroundColorSpan(colorPrimaryDark), start, out.length, SPAN)
                i++
                continue
            }

            // 列表项
            val listItem = mdListRegex.find(line)
            if (listItem != null) {
                newlineBefore()
                val marker = listItem.groupValues[1]
                val bullet = if (marker[0].isDigit()) "$marker " else "• "
                val start = out.length
                out.append(bullet)
                appendInline(out, listItem.groupValues[2])
                out.setSpan(LeadingMarginSpan.Standard(0, dp(16)), start, out.length, SPAN)
                i++
                continue
            }

            // 普通段落
            newlineBefore()
            appendInline(out, line)
            i++
        }
        return out
    }

    /** 行内格式：**加粗**、*斜体*、`代码` */
    fun appendInline(out: SpannableStringBuilder, text: String) {
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    val start = out.length
                    appendInline(out, text.substring(i + 2, end))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    val start = out.length
                    out.append(text, i + 1, end)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                    out.setSpan(BackgroundColorSpan(colorBorder), start, out.length, SPAN)
                    i = end + 1
                    continue
                }
            }
            if (text[i] == '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    val start = out.length
                    appendInline(out, text.substring(i + 1, end))
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, SPAN)
                    i = end + 1
                    continue
                }
            }
            out.append(text[i])
            i++
        }
    }

    /** 表格：等宽字体对齐 */
    fun appendTable(out: SpannableStringBuilder, tableLines: List<String>) {
        val rows = tableLines
            .map { row -> row.trim().trim('|').split("|").map { it.trim().replace("**", "") } }
            .filterNot { cells -> cells.all { it.isEmpty() || it.matches(mdTableSepRegex) } }
        if (rows.isEmpty()) return

        val cols = rows.maxOf { it.size }
        val widths = IntArray(cols)
        for (r in rows) for (c in r.indices) widths[c] = maxOf(widths[c], dispWidth(r[c]))

        val start = out.length
        rows.forEachIndexed { ri, cells ->
            if (ri > 0) out.append("\n")
            val rowStart = out.length
            val sb = StringBuilder()
            for (c in 0 until cols) {
                val cell = cells.getOrElse(c) { "" }
                sb.append(cell)
                sb.append(" ".repeat(maxOf(1, widths[c] - dispWidth(cell) + 2)))
            }
            out.append(sb.toString().trimEnd())
            if (ri == 0) out.setSpan(StyleSpan(Typeface.BOLD), rowStart, out.length, SPAN)
        }
        out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
        out.setSpan(RelativeSizeSpan(0.9f), start, out.length, SPAN)
    }

    /** 显示宽度估算：CJK 按 2 列，ASCII 按 1 列 */
    fun dispWidth(s: String): Int {
        var w = 0
        for (ch in s) w += if (ch.code >= 0x1100) 2 else 1
        return w
    }

    private fun dp(value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
