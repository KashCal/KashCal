package org.onekash.kashcal.ui.print

import org.onekash.kashcal.ui.components.pickers.argbToHex

/**
 * Renders [week] as one self-contained HTML document for the WebView to print.
 *
 * The document loads nothing: no script, no URL, and a Content-Security-Policy that allows only
 * its own inline style. Every user or server text is HTML-escaped, and text placed in CSS
 * `content` strings is CSS-escaped. It never sets an `@page` size, which would override the
 * paper the user picks; the week page instead sizes itself to the printed page (`100vh`, a size
 * container, positions in `cqh`, text cut at whole lines with `round(down, ..., 1lh)`), so the
 * print dialog's paper and orientation drive the layout.
 */
fun renderPrintHtml(week: PrintWeek, strings: PrintStrings): String {
    val out = StringBuilder()
    out.append("<!doctype html>")
    out.append("<html lang=\"").append(esc(strings.languageTag)).append("\" dir=\"")
        .append(if (strings.rtl) "rtl" else "ltr").append("\"><head><meta charset=\"utf-8\">")
    out.append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">")
    out.append("<title>").append(esc(strings.documentTitle)).append("</title>")
    out.append("<style>").append(css(strings)).append("</style></head>")
    out.append("<body").append(if (week.numbered) "" else " class=\"nonum\"").append(">")
    if (week.includeGrid) weekPage(out, week, strings)
    if (week.includeDetails) detailsPage(out, week, strings)
    out.append("</body></html>")
    return out.toString()
}

private fun header(out: StringBuilder, title: String, sub: String, week: PrintWeek) {
    out.append("<header class=\"doc-head\"><div><h1>").append(esc(title)).append("</h1>")
        .append("<div class=\"sub\">").append(esc(sub)).append("</div></div>")
    out.append("<div class=\"legend\">")
    for (c in week.calendars) {
        out.append("<span><i style=\"background:").append(hex(c.color)).append("\"></i>")
            .append("<span dir=\"auto\">").append(esc(c.name)).append("</span></span>")
    }
    out.append("</div></header>")
}

private fun weekPage(out: StringBuilder, week: PrintWeek, strings: PrintStrings) {
    val span = (week.hourEnd - week.hourStart) * 60
    val hours = week.hourEnd - week.hourStart
    val before = if (strings.rtl) "▸" else "◂"
    val after = if (strings.rtl) "◂" else "▸"
    fun titled(title: String, continuesBefore: Boolean, continuesAfter: Boolean) =
        (if (continuesBefore) "$before " else "") + esc(title) + (if (continuesAfter) " $after" else "")

    out.append("<section class=\"page1\">")
    header(out, strings.weekRange, "${strings.weekLabel} · ${strings.calendarsSummary}", week)

    out.append("<div class=\"wk\"><div></div>")
    for (day in week.days) {
        out.append("<div class=\"dh").append(if (day.isWeekend) " weekend" else "").append("\">")
            .append(esc(day.heading)).append("</div>")
    }
    out.append("<div class=\"ad lblcell\"><span class=\"lbl\">").append(esc(strings.allDay)).append("</span></div>")
    for (day in week.days) {
        out.append("<div class=\"ad\">")
        for (item in day.stripItems) {
            out.append("<div class=\"chip").append(statusClass(item.declined, item.cancelled)).append("\" style=\"background:")
                .append(hex(tint(item.color, 0.18f))).append(";border-inline-start-color:").append(hex(item.color)).append("\">")
            number(out, item.number)
            out.append("<span class=\"t\" dir=\"auto\">")
                .append(titled(item.title, item.continuesBefore, item.continuesAfter)).append("</span></div>")
        }
        out.append("</div>")
    }
    out.append("</div>")

    out.append("<div class=\"cols\" style=\"--span:").append(span).append(";--hours:").append(hours).append("\">")
    out.append("<div class=\"hours\">")
    for ((hour, label) in week.hourLabels) {
        val top = (hour - week.hourStart).toFloat() / hours * 100f
        out.append("<span style=\"top:").append(top).append("%\">").append(esc(label)).append("</span>")
    }
    out.append("</div>")
    for (day in week.days) {
        out.append("<div class=\"col\">")
        for (part in day.parts) {
            out.append("<div class=\"blk").append(statusClass(part.declined, part.cancelled)).append("\" style=\"")
                .append("--s:").append(part.startMinute - week.hourStart * 60)
                .append(";--d:").append(part.endMinute - part.startMinute)
                .append(";--t:").append(part.textEndMinute - part.startMinute).append(";")
                .append("inset-inline-start:calc(").append(part.leftFraction * 100f).append("% + 0.3mm);")
                .append("width:calc(").append(part.widthFraction * 100f).append("% - 0.6mm);")
                .append("background:").append(hex(tint(part.color, 0.16f))).append(";")
                .append("border-inline-start-color:").append(hex(part.color)).append(";")
                .append("z-index:").append(part.depth + 1).append("\"><div class=\"clip\">")
            number(out, part.number)
            out.append("<span class=\"t\" dir=\"auto\">")
                .append(titled(part.title, part.continuesBefore, part.continuesAfter)).append("</span>")
                .append("<div class=\"bt\">").append(esc(part.timeText)).append("</div></div></div>")
        }
        out.append("</div>")
    }
    out.append("</div>")

    out.append("<div class=\"foot\"><span>")
    if (week.numbered) out.append(esc(strings.numbersNote))
    out.append("</span><span>").append(esc(strings.timesShownIn)).append("</span></div>")
    out.append("</section>")
}

private fun detailsPage(out: StringBuilder, week: PrintWeek, strings: PrintStrings) {
    out.append("<section class=\"details-page\">")
    header(out, strings.detailsLabel, "${strings.weekRange} · ${strings.calendarsSummary}", week)
    out.append("<div class=\"details\">")
    for (day in week.days) {
        out.append("<div class=\"dday\"><h2").append(if (day.isWeekend) " class=\"weekend\"" else "").append(">")
            .append(esc(day.heading)).append("</h2>")
        if (day.details.isEmpty()) {
            out.append("<div class=\"free\">").append(esc(strings.freeDay)).append("</div>")
        }
        for (d in day.details) {
            out.append("<div class=\"item").append(statusClass(d.declined, d.cancelled)).append("\">")
            number(out, d.number)
            out.append("<div class=\"when\">").append(esc(d.whenText))
            if (d.whenNote != null) out.append("<br>").append(esc(d.whenNote))
            out.append("</div><div><div class=\"t\" dir=\"auto\">").append(esc(d.title))
            if (d.declined) out.append("<span class=\"tag\">").append(esc(strings.declined)).append("</span>")
            if (d.cancelled) out.append("<span class=\"tag\">").append(esc(strings.cancelled)).append("</span>")
            out.append("</div><div class=\"meta\"><i class=\"sw\" style=\"background:").append(hex(d.calendarColor))
                .append("\"></i><span dir=\"auto\">").append(esc(d.calendarName)).append("</span>")
            if (d.location != null) out.append(" · <span dir=\"auto\">").append(esc(d.location)).append("</span>")
            if (d.repeats) out.append(" · ").append(esc(strings.repeats))
            if (d.movedText != null) out.append(" · <span class=\"cue\">").append(esc(d.movedText)).append("</span>")
            out.append("</div></div></div>")
        }
        out.append("</div>")
    }
    out.append("</div></section>")
}

private fun number(out: StringBuilder, number: Int?) {
    if (number != null) out.append("<span class=\"n\">").append(number).append("</span>")
}

private fun statusClass(declined: Boolean, cancelled: Boolean): String =
    (if (declined) " declined" else "") + (if (cancelled) " cancelled" else "")

/** Escapes text for HTML element content and attribute values. */
private fun esc(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** Quotes [text] as a CSS string, escaping what would end or break it. */
private fun cssString(text: String): String {
    val sb = StringBuilder("\"")
    for (ch in text) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\A ")
            '<' -> sb.append("\\3C ")
            else -> sb.append(ch)
        }
    }
    return sb.append('"').toString()
}

/**
 * Builds a CSS `content` value from [template], with `counter(page)` for `%1$s` and
 * `counter(pages)` for `%2$s` wherever the translation puts them.
 */
private fun cssCounterContent(template: String): String {
    val parts = mutableListOf<String>()
    var last = 0
    for (match in Regex("""%([12])\${'$'}s""").findAll(template)) {
        if (match.range.first > last) parts += cssString(template.substring(last, match.range.first))
        parts += if (match.groupValues[1] == "1") "counter(page)" else "counter(pages)"
        last = match.range.last + 1
    }
    if (last < template.length) parts += cssString(template.substring(last))
    return parts.joinToString(" ")
}

private fun hex(color: Int): String = "#" + argbToHex(color)

/** Mixes [color] with white, keeping [alpha] of the colour. */
private fun tint(color: Int, alpha: Float): Int {
    fun mix(c: Int) = (255 - (255 - c) * alpha).toInt().coerceIn(0, 255)
    val r = mix((color shr 16) and 0xFF)
    val g = mix((color shr 8) and 0xFF)
    val b = mix(color and 0xFF)
    return (r shl 16) or (g shl 8) or b
}

private fun css(strings: PrintStrings): String = """
:root { --ink: #1b1b1f; --muted: #5f6068; --rule: #c9c9cf; --faint: #e6e6ea; --weekend: #b3261e; }
* { box-sizing: border-box; print-color-adjust: exact; -webkit-print-color-adjust: exact; }
html { font-family: Roboto, "Noto Sans", sans-serif; color: var(--ink); }
body { margin: 0; font-size: 9pt; line-height: 1.25; font-variant-numeric: tabular-nums; }
@page { margin: 10mm 10mm 12mm;
  @bottom-left { content: ${cssString(strings.printedOn)}; font: 7pt sans-serif; color: #777 }
  @bottom-right { content: ${cssCounterContent(strings.pageOf)}; font: 7pt sans-serif; color: #777 } }
.doc-head { display: flex; align-items: baseline; justify-content: space-between; gap: 6mm; margin: 0 0 3mm; }
.doc-head h1 { font-size: 15pt; font-weight: 600; margin: 0; }
.doc-head .sub { color: var(--muted); font-size: 8pt }
.legend { display: flex; flex-wrap: wrap; gap: 1mm 4mm; font-size: 7.5pt; color: var(--muted); }
.legend i { display: inline-block; width: 2.6mm; height: 2.6mm; border-radius: 0.6mm; margin-inline-end: 1.2mm; vertical-align: -0.3mm }
.declined .t, .cancelled .t { text-decoration: line-through; color: var(--muted) }
.declined, .cancelled { opacity: .75 }
.tag { font-size: 6.8pt; font-weight: 600; color: var(--muted); border: 0.25mm solid var(--rule); border-radius: 0.8mm; padding: 0 0.8mm; margin-inline-start: 1mm; white-space: nowrap; text-decoration: none; display: inline-block }
.cue { color: var(--muted); font-style: italic }
.n { display: inline-block; min-width: 3mm; height: 3mm; line-height: 3mm; text-align: center; border-radius: 1.5mm; background: #fff; color: var(--ink); font-size: 6pt; font-weight: 700; margin-inline-end: 0.6mm; border: 0.2mm solid var(--rule); padding: 0 0.4mm; vertical-align: 0.1mm }
.page1 { break-after: page; height: 100vh; display: flex; flex-direction: column }
.page1:last-child { break-after: auto }
.wk { display: grid; grid-template-columns: 11mm repeat(7, 1fr); }
.wk .dh { text-align: center; font-weight: 600; font-size: 9pt; padding: 1mm 0 1.2mm; border-bottom: 0.3mm solid var(--rule) }
.wk .dh.weekend { color: var(--weekend) }
.ad { border-bottom: 0.3mm solid var(--rule); padding: 0.8mm 0.5mm; display: flex; flex-direction: column; gap: 0.6mm; border-inline-start: 0.2mm solid var(--faint) }
.ad.lblcell { border-inline-start: none }
.ad .lbl { font-size: 6.8pt; color: var(--muted) }
.chip { font-size: 7.5pt; font-weight: 500; border-radius: 0.8mm; padding: 0.3mm 1mm; overflow-wrap: anywhere; border-inline-start: 0.8mm solid }
.cols { position: relative; display: grid; grid-template-columns: 11mm repeat(7, 1fr); container-type: size; flex: 1; min-height: 55vh }
.hours { position: relative }
.hours span { position: absolute; inset-inline-end: 1.2mm; font-size: 6.8pt; color: var(--muted); transform: translateY(-50%) }
.col { position: relative; border-inline-start: 0.2mm solid var(--faint);
  background-image: repeating-linear-gradient(to bottom, var(--rule) 0 0.2mm, transparent 0.2mm calc(100cqh / var(--hours))); }
.blk { position: absolute; top: calc(var(--s) / var(--span) * 100cqh); height: max(calc(1lh + 0.6mm), calc(var(--d) / var(--span) * 100cqh));
  border-radius: 0.8mm; padding: 0.3mm 0.6mm 0.3mm 0.9mm; overflow: hidden; font-size: 7pt; line-height: 1.18;
  border-inline-start: 0.8mm solid; box-shadow: 0 0 0 0.3mm #fff }
.blk .clip { max-height: max(1lh, round(down, calc(var(--t) / var(--span) * 100cqh - 0.6mm), 1lh)); overflow: hidden }
.blk .t { font-weight: 500 }
.blk .bt { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--muted) }
.foot { display: flex; justify-content: space-between; margin-top: 2mm; font-size: 7.5pt; color: var(--muted) }
.details { columns: 3; column-gap: 6mm; }
@media print and (orientation: portrait) { .details { columns: 2 } }
.details h2 { font-size: 10.5pt; font-weight: 600; margin: 0 0 1.5mm; padding-bottom: 0.8mm; border-bottom: 0.3mm solid var(--rule); break-after: avoid }
.details h2.weekend { color: var(--weekend) }
.dday { margin: 0 0 4mm }
.free { color: var(--muted); font-size: 8pt; padding: 0.8mm 0 }
.item { display: grid; grid-template-columns: 5mm 19mm 1fr; column-gap: 1.2mm; padding: 0.8mm 0; border-bottom: 0.2mm solid var(--faint); break-inside: avoid }
.nonum .item { grid-template-columns: 19mm 1fr }
.item .when { font-size: 8pt; color: var(--muted) }
.item .t { font-weight: 600 }
.item .meta { font-size: 7.8pt; color: var(--muted) }
.item i.sw { display: inline-block; width: 2.2mm; height: 2.2mm; border-radius: 0.5mm; margin-inline-end: 1mm; vertical-align: -0.2mm }
"""
