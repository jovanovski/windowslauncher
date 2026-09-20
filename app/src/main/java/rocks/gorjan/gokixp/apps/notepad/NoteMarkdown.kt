package rocks.gorjan.gokixp.apps.notepad

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import kotlin.math.roundToInt

/**
 * Markdown, drawn on a note as it is written.
 *
 * The note stays plain text - it is Notepad's note, and Notepad has never heard of
 * markdown - so nothing here changes a character of it. What changes is how it is set: a heading is set large, a starred word bold, a hyphen at the head of a line is drawn
 * as a bullet and a `- [ ]` as a box to tick.
 *
 * The marks go once they have done their work, the way they do in Notion: type `**bold**`
 * and the stars disappear as the caret moves on. They are still in the note, taking no room,
 * and each one comes back while the caret is at it - so a mark can be seen before it is
 * deleted, and the caret never sits beside something it cannot see. A note that is only
 * being read, with no caret in it, shows none of them.
 *
 * Restyled after every change and every move of the caret rather than parsed once: a note
 * is short, and a styler that only looked at the line being typed would miss a code fence
 * opened ten lines up.
 */
internal object NoteMarkdown {

    /**
     * What the styling is drawn in. Built per window, since the shell's theme can change.
     *
     * Colours rather than a palette object: the note is written on a white page under every
     * one of the desktop's shells - see program_notepad.xml - so only the accent moves with
     * the theme, and the rest are the greys Windows has always drawn its chrome in.
     */
    class Look(
        @param:ColorInt val accent: Int,
        @param:ColorInt val ink: Int,
        @param:ColorInt val subtle: Int,
        @param:ColorInt val shade: Int,
        val bold: Typeface,
        val density: Float
    )

    /**
     * Brings [text]'s styling up to date with what it now says and where [caret] is.
     *
     * [caret] is the selection, or null while the note is not being edited. Only the spans
     * that differ are touched: taking them all off and putting them all back on would be
     * simpler, and would re-lay the whole note on every keystroke.
     */
    fun style(text: Editable, look: Look, caret: IntRange?) {
        val wanted = wants(text.toString(), caret)
        for (span in text.getSpans(0, text.length, Styled::class.java)) {
            val id = idOf(span.key, text.getSpanStart(span), text.getSpanEnd(span))
            if (wanted.remove(id) == null) text.removeSpan(span)
        }
        for (w in wanted.values) text.setSpan(w.make(look, w.key), w.start, w.end, w.flags)
    }

    /** The spans [style] would want on [src], as `key@start:end`. */
    @VisibleForTesting
    internal fun ids(src: String, caret: IntRange?): Set<String> = wants(src, caret).keys

    private fun wants(src: String, caret: IntRange?) =
        LinkedHashMap<String, Want>().also { Parser(src, caret, it).run() }

    /**
     * Where the to-do boxes are drawn on [text], each as the `- [ ]` it is drawn over.
     *
     * Only the ones drawn as boxes: a to-do the caret is at is showing its brackets to be
     * edited, and a tap there belongs to the caret.
     */
    fun boxes(text: Spanned): List<IntRange> =
        text.getSpans(0, text.length, CheckBox::class.java)
            .map { text.getSpanStart(it)..text.getSpanEnd(it) }

    /**
     * Ticks the to-do drawn over [box], or unticks it if it is ticked.
     *
     * An edit like any other, so it is saved, restyled and undone the way typing is.
     */
    fun toggle(text: Editable, box: IntRange) {
        val at = tickAt(text, box) ?: return
        text.replace(at, at + 1, if (text[at] == ' ') "x" else " ")
    }

    /**
     * Where the space or `x` between [box]'s brackets is - or null if what is there is no
     * longer a to-do, because the text moved under the finger.
     */
    @VisibleForTesting
    internal fun tickAt(src: CharSequence, box: IntRange): Int? {
        val end = box.last
        if (end > src.length) return null
        val bracket = src.indexOf('[', box.first)
        if (bracket < 0 || bracket + 2 >= end) return null
        if (src[bracket + 2] != ']' || src[bracket + 1] !in " xX") return null
        return bracket + 1
    }

    /**
     * The first thing a note says, with the markdown taken out.
     *
     * For the second line of a note's row in the list, where "# Shopping" should read as
     * "Shopping" and a code fence or a rule is not something the note says at all.
     */
    fun preview(content: String): String? {
        var inFence = false
        for (line in content.lineSequence()) {
            if (FENCE.matches(line)) {
                inFence = !inFence
                continue
            }
            if (line.isBlank() || (!inFence && RULE.matches(line))) continue
            val said = if (inFence) line.trim() else plain(line)
            if (said.isNotBlank()) return said
        }
        return null
    }

    /** One line of markdown as the words it stands for. */
    private fun plain(line: String): String {
        var s = line.trim()
        HEADING.find(s)?.let { s = s.substring(it.range.last + 1) }
        QUOTE.find(s)?.let { s = s.substring(it.range.last + 1) }
        LIST.find(s)?.let { s = s.substring(it.range.last + 1) }
        s = LINK.replace(s) { it.groupValues[1] }
        s = CODE.replace(s) { it.groupValues[1] }
        for (e in EMPHASIS) s = e.regex.replace(s) { it.groupValues[e.content] }
        return s.trim()
    }

    internal enum class Kind { CODE, LINK, BOLD_ITALIC, BOLD, ITALIC, STRIKE, UNDERLINE }

    /**
     * One piece of inline markdown on a line, as offsets into the line: its opening marks
     * are [start]..[bodyStart], its words [bodyStart]..[bodyEnd], its closing marks
     * [bodyEnd]..[end]. A link's closing mark is the whole of `](address)`.
     */
    internal class Run(
        val kind: Kind,
        val start: Int,
        val bodyStart: Int,
        val bodyEnd: Int,
        val end: Int
    )

    /**
     * The inline markdown on [line], from [from] on.
     *
     * Each kind is found in turn on a working copy of the line, and the marks each one
     * uses up are blanked out of the copy before the next looks - so the stars of a bold
     * word are not found again as the start of an italic one, and nothing inside
     * backticks or a link's address is read as markdown at all.
     *
     * Shared by the styling and by the Format menu, which has to know which marks
     * belong to which words before it can take a style off them - see NoteFormat.
     */
    internal fun runs(line: String, from: Int = 0): List<Run> {
        if (from >= line.length) return emptyList()
        val work = StringBuilder(line)
        fun mask(first: Int, until: Int) {
            for (i in first until until) work.setCharAt(i, MASK)
        }
        mask(0, from)
        val out = mutableListOf<Run>()

        for (m in CODE.findAll(work).toList()) {
            val r = m.range
            out += Run(Kind.CODE, r.first, r.first + 1, r.last, r.last + 1)
            mask(r.first, r.last + 1)
        }

        for (m in LINK.findAll(work).toList()) {
            val r = m.range
            val words = m.groups[1]!!.range
            out += Run(Kind.LINK, r.first, words.first, words.last + 1, r.last + 1)
            mask(r.first, words.first)
            mask(words.last + 1, r.last + 1)
        }

        for (e in EMPHASIS) {
            for (m in e.regex.findAll(work).toList()) {
                val r = m.range
                val body = m.groups[e.content]!!.range
                // Marks with nothing but marks inside: "******" is not a bold star, it is empty
                // pairs the Format menu puts down, stacked, waiting for words.
                if ((body.first..body.last).all { work[it] in MARKS_ONLY }) continue
                out += Run(e.kind, r.first, body.first, body.last + 1, r.last + 1)
                mask(r.first, body.first)
                mask(body.last + 1, r.last + 1)
            }
        }
        return out
    }

    // ------------------------------------------------------------------- the parser

    private class Want(
        val key: String,
        val start: Int,
        val end: Int,
        val flags: Int,
        val make: Look.(String) -> Styled
    )

    private fun idOf(key: String, start: Int, end: Int) = "$key@$start:$end"

    private class Parser(
        private val src: String,
        private val caret: IntRange?,
        private val out: MutableMap<String, Want>
    ) {
        fun run() {
            var start = 0
            var fenceStart = -1
            while (true) {
                val end = src.indexOf('\n', start).let { if (it < 0) src.length else it }
                val line = src.substring(start, end)
                val fence = FENCE.matchEntire(line)
                when {
                    fence != null -> {
                        // The backticks go; a language written after them stays, set back.
                        val ticks = start + fence.groups[1]!!.range.last + 1
                        mark(start, ticks)
                        dim(ticks, end)
                        mono(start, end)
                        if (fenceStart < 0) {
                            fenceStart = start
                        } else {
                            codeBlock(fenceStart, minOf(end + 1, src.length))
                            fenceStart = -1
                        }
                    }
                    fenceStart >= 0 -> mono(start, end)
                    else -> block(line, start)
                }
                if (end >= src.length) break
                start = end + 1
            }
            // A fence that has not been closed yet is still code to the end of the note:
            // that is what the user is in the middle of typing.
            if (fenceStart >= 0) codeBlock(fenceStart, src.length)
        }

        /** One line outside a code fence: what kind of line it is, then what is in it. */
        private fun block(line: String, at: Int) {
            val end = at + line.length

            HEADING.find(line)?.let { m ->
                val level = m.groupValues[1].length
                val from = m.range.last + 1
                // Kept in sight while nothing follows it: a line that was only a hidden "#"
                // would be a tall blank line with no way to tell why.
                if (line.substring(from).isNotBlank()) mark(at, at + from) else dim(at, at + from)
                add("h$level", at, end) { Size(it, HEADING_SIZES[level - 1]) }
                // Bold at every level. Every face the desktop is set in - Micross, Tahoma,
                // Segoe - is carried in one weight beside its bold, and a heading in the body
                // face at body size would not read as a heading at all.
                add("face$level", at + from, end) { Face(it, bold) }
                inline(line, at, from)
                return
            }

            if (RULE.matches(line) && !starsBeingTyped(line, at, end)) {
                // With the dashes hidden the rule runs from the edge of the page; with the
                // caret on them it starts after them instead of being drawn through them.
                val shown = touched(at, end)
                if (shown) dim(at, end) else hide(at, end)
                add(if (shown) "rule-after" else "rule", at, minOf(end + 1, src.length)) {
                    Rule(it, subtle, density, fromEdge = !shown)
                }
                return
            }

            QUOTE.find(line)?.let { m ->
                val bar = at + line.indexOf('>')
                val from = m.range.last + 1
                add("quote", bar, bar + 1) { QuoteBar(it, accent, density) }
                tint(at + from, end)
                inline(line, at, from)
                return
            }

            LIST.find(line)?.let { m ->
                val marker = m.groups[2]!!.range
                val task = m.groups[3]
                val from = m.range.last + 1
                when {
                    task != null -> {
                        // "- [ ]" is one mark, drawn as the box: the hyphen is how markdown
                        // spells a to-do, not a bullet to put in front of one.
                        val ticked = task.value[1] != ' '
                        val box = at + marker.first
                        val boxEnd = at + task.range.first + 3
                        if (touched(box, boxEnd)) {
                            dim(box, boxEnd)
                        } else {
                            add(if (ticked) "ticked" else "box", box, boxEnd) {
                                CheckBox(it, ticked, ink, density)
                            }
                        }
                        // A ticked item is done, and reads as done: struck through and set back.
                        if (ticked) {
                            add("strike", at + from, end) { Strike(it) }
                            tint(at + from, end)
                        }
                    }
                    line[marker.first].isDigit() -> dim(at + marker.first, at + marker.last + 1)
                    else -> add("bullet", at + marker.first, at + marker.first + 1) {
                        Bullet(it, accent)
                    }
                }
                inline(line, at, from)
                return
            }

            inline(line, at, 0)
        }

        /** The styling inside a line, from [from] on. See [runs] for how it is found. */
        private fun inline(line: String, at: Int, from: Int) {
            for (run in runs(line, from)) {
                val body = at + run.bodyStart
                val bodyEnd = at + run.bodyEnd
                mark(at + run.start, body)
                mark(bodyEnd, at + run.end)
                when (run.kind) {
                    Kind.CODE -> {
                        mono(body, bodyEnd)
                        add("code", body, bodyEnd) { Shade(it, shade) }
                    }
                    Kind.LINK -> add("link", body, bodyEnd) { Colour(it, accent) }
                    Kind.BOLD_ITALIC -> {
                        add("bold", body, bodyEnd) { Face(it, bold) }
                        add("italic", body, bodyEnd) { Italic(it) }
                    }
                    Kind.BOLD -> add("bold", body, bodyEnd) { Face(it, bold) }
                    Kind.ITALIC -> add("italic", body, bodyEnd) { Italic(it) }
                    Kind.STRIKE -> add("strike", body, bodyEnd) { Strike(it) }
                    Kind.UNDERLINE -> add("underline", body, bodyEnd) { Underline(it) }
                }
            }
        }

        /** Whether the caret is anywhere in [start]..[end], either end included. */
        private fun touched(start: Int, end: Int) =
            caret != null && caret.first <= end && caret.last >= start

        /**
         * A line of nothing but stars with the caret in the middle of it.
         *
         * That is the empty pair the Format menu's bold puts down on an empty line, not
         * a rule being drawn, and read as a rule the line would turn into one under the caret
         * until the first word was typed into it.
         */
        private fun starsBeingTyped(line: String, at: Int, end: Int) =
            caret != null && caret.first > at && caret.last < end &&
                line.all { it == '*' || it == ' ' || it == '\t' }

        /**
         * A mark of the markdown's own: hidden once it has done its work, and back - set
         * back from the words - while the caret is at it.
         *
         * Either end counts as "at it", which is what keeps the caret from ever sitting
         * beside a hidden mark: the one place it could land next to one is the place that
         * uncovers it.
         */
        private fun mark(start: Int, end: Int) =
            if (touched(start, end)) dim(start, end) else hide(start, end)

        private fun dim(start: Int, end: Int) =
            add("dim", start, end) { Colour(it, subtle) }

        private fun hide(start: Int, end: Int) = add("hide", start, end) { Hidden(it) }

        /**
         * Words set back as a whole - a quotation, a ticked item.
         *
         * At a higher priority than the rest so that it is applied first, and a link or a
         * mark inside it still gets its own colour over the top.
         */
        private fun tint(start: Int, end: Int) =
            add("tint", start, end, 1 shl Spanned.SPAN_PRIORITY_SHIFT) {
                Colour(it, subtle)
            }

        private fun mono(start: Int, end: Int) = add("mono", start, end) { Mono(it) }

        private fun codeBlock(start: Int, end: Int) =
            add("block", start, end) { CodeBlock(it, shade) }

        /**
         * Asks for a span of [key] over [start]..[end].
         *
         * [make] is only called if the note does not already have that exact span - see
         * [style] - and is handed the key to tag what it builds with.
         */
        private fun add(
            key: String,
            start: Int,
            end: Int,
            priority: Int = 0,
            make: Look.(String) -> Styled
        ) {
            if (start >= end) return
            val id = idOf(key, start, end)
            if (id in out) return
            out[id] = Want(key, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or priority, make)
        }
    }

    // ---------------------------------------------------------------- the patterns

    private class Emphasis(val regex: Regex, val content: Int, val kind: Kind)

    /** Longest marks first, so `***` is not read as `**` with a stray `*`. */
    private val EMPHASIS = listOf(
        // Markdown has no underline of its own. The HTML tag is the one most renderers of it
        // understand, so a note underlined here still reads as underlined anywhere else.
        Emphasis(Regex("""<u>(.+?)</u>"""), 1, Kind.UNDERLINE),
        Emphasis(Regex("""(\*\*\*|___)(?=\S)(.+?)(?<=\S)\1"""), 2, Kind.BOLD_ITALIC),
        Emphasis(Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1"""), 2, Kind.BOLD),
        Emphasis(Regex("""(?<!\*)\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?!\*)"""), 1, Kind.ITALIC),
        // Never inside a word: snake_case_names are not a request for italics.
        Emphasis(Regex("""(?<![_\w])_(?=[^\s_])(.+?)(?<=[^\s_])_(?![_\w])"""), 1, Kind.ITALIC),
        Emphasis(Regex("""~~(?=\S)(.+?)(?<=\S)~~"""), 1, Kind.STRIKE)
    )

    private val FENCE = Regex(""" {0,3}(```|~~~).*""")
    private val HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+|$)""")
    private val RULE = Regex(""" {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*""")
    private val QUOTE = Regex("""^ {0,3}>[ \t]?""")
    private val LIST = Regex("""^([ \t]*)([-*+]|\d{1,9}[.)])[ \t]+(\[[ xX]\](?:[ \t]+|$))?""")
    private val CODE = Regex("""`([^`\n]+)`""")
    private val LINK = Regex("""\[([^\]\n]+)\]\(([^)\s]*)\)""")

    /** What a used-up mark is blanked to: not a space, not a word, not a mark. */
    private const val MASK = '\u0001'

    private const val MARKS_ONLY = "*_~"

    private val HEADING_SIZES = floatArrayOf(1.6f, 1.4f, 1.2f, 1.1f, 1f, 1f)
}

// -------------------------------------------------------------------------- the spans

/**
 * Every span the styler puts on a note, tagged with what it is for.
 *
 * So that the next pass can tell its own spans from the keyboard's - the underline under a
 * word being composed, a spelling suggestion - and only ever takes off the ones it put on.
 */
private interface Styled {
    val key: String
}

private class Colour(override val key: String, @ColorInt colour: Int) :
    ForegroundColorSpan(colour), Styled

private class Shade(override val key: String, @ColorInt colour: Int) :
    BackgroundColorSpan(colour), Styled

private class Face(override val key: String, face: Typeface) : TypefaceSpan(face), Styled

private class Mono(override val key: String) : TypefaceSpan("monospace"), Styled

private class Size(override val key: String, scale: Float) : RelativeSizeSpan(scale), Styled

/** None of the shell's faces is carried with an italic, so this one is slanted by the platform. */
private class Italic(override val key: String) : StyleSpan(Typeface.ITALIC), Styled

private class Strike(override val key: String) : StrikethroughSpan(), Styled

private class Underline(override val key: String) : UnderlineSpan(), Styled

/** A mark that has done its work: still in the note, taking no room on the page. */
private class Hidden(override val key: String) : ReplacementSpan(), Styled {

    override fun getSize(
        paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        // The line keeps its height even when a hidden mark is all that is on it - a code
        // fence, a rule.
        fm?.let { paint.getFontMetricsInt(it) }
        return 0
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) = Unit
}

/** A list's hyphen or star, drawn as the bullet it stands for. */
private class Bullet(override val key: String, @param:ColorInt private val colour: Int) :
    ReplacementSpan(), Styled {

    override fun getSize(
        paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        return paint.measureText(GLYPH).roundToInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val was = paint.color
        paint.color = colour
        canvas.drawText(GLYPH, x, y.toFloat(), paint)
        paint.color = was
    }

    private companion object {
        const val GLYPH = "•"
    }
}

/**
 * A to-do's `- [ ]`, drawn as the box it stands for, with a tick in it for `[x]`.
 *
 * Drawn rather than typed: an outline in the text's colour with a tick in the same stroke,
 * rather than a font's ballot-box character that every typeface draws differently.
 */
private class CheckBox(
    override val key: String,
    private val ticked: Boolean,
    @ColorInt colour: Int,
    density: Float
) : ReplacementSpan(), Styled {

    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = colour
    }
    private val tick = Path()

    override fun getSize(
        paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        return side(paint).roundToInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val inset = pen.strokeWidth / 2
        val side = side(paint) - pen.strokeWidth
        val middle = y + (paint.ascent() + paint.descent()) / 2
        val left = x + inset
        val top0 = middle - side / 2
        canvas.drawRect(left, top0, left + side, top0 + side, pen)
        if (ticked) {
            tick.reset()
            tick.moveTo(left + side * 0.2f, top0 + side * 0.5f)
            tick.lineTo(left + side * 0.42f, top0 + side * 0.72f)
            tick.lineTo(left + side * 0.8f, top0 + side * 0.28f)
            canvas.drawPath(tick, pen)
        }
    }

    /** About as tall as a capital. */
    private fun side(paint: Paint) = paint.textSize * 0.75f
}

/** A quotation's `>`, drawn as the bar down its edge. */
private class QuoteBar(override val key: String, @param:ColorInt private val colour: Int, density: Float) :
    ReplacementSpan(), Styled {

    private val bar = 3f * density

    override fun getSize(
        paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        return (bar * 2).roundToInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val was = paint.color
        paint.color = colour
        canvas.drawRect(x, top.toFloat(), x + bar, bottom.toFloat(), paint)
        paint.color = was
    }
}

/** The ground behind a fenced block of code, the width of the page. */
private class CodeBlock(override val key: String, @param:ColorInt private val colour: Int) :
    LineBackgroundSpan, Styled {

    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int,
        bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        val was = paint.color
        paint.color = colour
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        paint.color = was
    }
}

/**
 * A `---` line, drawn as a rule to the edge of the page.
 *
 * From the page's edge when the dashes are hidden; from just after them while the caret is
 * on the line and they are showing.
 */
private class Rule(
    override val key: String,
    @param:ColorInt private val colour: Int,
    density: Float,
    private val fromEdge: Boolean
) : LineBackgroundSpan, Styled {

    private val thickness = density

    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int,
        bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        var from = left.toFloat()
        if (!fromEdge) {
            var stop = end
            while (stop > start && text[stop - 1].isWhitespace()) stop--
            from += paint.measureText(text, start, stop) + thickness * 8
        }
        if (from >= right) return
        val middle = baseline + (paint.ascent() + paint.descent()) / 2
        val was = paint.color
        paint.color = colour
        canvas.drawRect(from, middle - thickness / 2, right.toFloat(), middle + thickness / 2, paint)
        paint.color = was
    }
}
