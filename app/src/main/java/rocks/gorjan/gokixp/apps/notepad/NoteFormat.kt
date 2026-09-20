package rocks.gorjan.gokixp.apps.notepad

import rocks.gorjan.gokixp.apps.notepad.NoteMarkdown.Kind
import rocks.gorjan.gokixp.apps.notepad.NoteMarkdown.Run

/**
 * What Notepad's Format menu does to a note.
 *
 * Every command is worked out here against the note as a string and a selection, and comes
 * back as the edits to make and where the selection goes after them; the editor only
 * carries them out. Kept apart from the view so that the parts that are easy to get wrong -
 * what bold means with nothing selected, which marks belong to which words - can be tested
 * without a device.
 *
 * Each command works on the selection if there is one, and otherwise on what is typed next.
 * Bold with nothing selected puts down an empty pair of marks with the caret between them;
 * bold again with the caret at the end of those words steps back out past the closing pair,
 * so typing carries on plain. A second press straight after the first takes the empty pair
 * away again.
 */
internal object NoteFormat {

    enum class Tool { BOLD, ITALIC, UNDERLINE, H1, H2, H3, NORMAL, BULLETS, NUMBERS, LINK }

    /** Replace [start]..[end] of the note as it was with [text]. */
    data class Change(val start: Int, val end: Int, val text: String)

    /**
     * What a command comes to.
     *
     * [changes] are against the note as it was, in the order to make them: from the end of
     * the note backwards, so that making one does not move where the next one goes. The
     * selection afterwards is [selStart]..[selEnd].
     */
    class Result(changes: List<Change>, val selStart: Int, val selEnd: Int) {
        val changes: List<Change> = changes.sortedWith(
            compareByDescending<Change> { it.start }.thenByDescending { it.end }
        )
    }

    /** A link: `[` at [start], its words [bodyStart]..[bodyEnd], then `](url)` to [end]. */
    class Link(val start: Int, val bodyStart: Int, val bodyEnd: Int, val end: Int, val url: String)

    /** What pressing return does in a list item. See [onReturn]. */
    sealed class Continue {
        /** The list carries on, and the new line starts with [mark]. */
        class Next(val mark: String) : Continue()

        /** The item was empty, so the list ends there: its [mark], at [start], comes off. */
        class End(val start: Int, val mark: String) : Continue()
    }

    /** Carries out [tool] on [src] with [s]..[e] selected. Not the link, which needs an address: see [link]. */
    fun apply(tool: Tool, src: String, s: Int, e: Int): Result? = when (tool) {
        Tool.BOLD -> toggle(src, s, e, Style.BOLD)
        Tool.ITALIC -> toggle(src, s, e, Style.ITALIC)
        Tool.UNDERLINE -> toggle(src, s, e, Style.UNDERLINE)
        Tool.H1 -> heading(src, s, e, 1)
        Tool.H2 -> heading(src, s, e, 2)
        Tool.H3 -> heading(src, s, e, 3)
        Tool.NORMAL -> heading(src, s, e, 0)
        Tool.BULLETS -> list(src, s, e, numbered = false)
        Tool.NUMBERS -> list(src, s, e, numbered = true)
        Tool.LINK -> null
    }

    /** Which commands are in force at [s]..[e], for the menu to tick. */
    fun active(src: String, s: Int, e: Int): Set<Tool> {
        val on = mutableSetOf<Tool>()
        if (inStyle(src, s, e, Style.BOLD)) on += Tool.BOLD
        if (inStyle(src, s, e, Style.ITALIC)) on += Tool.ITALIC
        if (inStyle(src, s, e, Style.UNDERLINE)) on += Tool.UNDERLINE
        val line = lineAt(src, s)
        when (line.kind) {
            LineKind.HEADING -> when (line.level) {
                1 -> on += Tool.H1
                2 -> on += Tool.H2
                3 -> on += Tool.H3
            }
            LineKind.BULLET, LineKind.TASK -> on += Tool.BULLETS
            LineKind.NUMBER -> on += Tool.NUMBERS
            LineKind.PLAIN -> Unit
        }
        if (linkAt(src, s, e) != null) on += Tool.LINK
        return on
    }

    // ---------------------------------------------------------------- the styles

    private enum class Style(val open: String, val close: String, val kinds: Set<Kind>) {
        BOLD("**", "**", setOf(Kind.BOLD, Kind.BOLD_ITALIC)),
        ITALIC("*", "*", setOf(Kind.ITALIC, Kind.BOLD_ITALIC)),
        UNDERLINE("<u>", "</u>", setOf(Kind.UNDERLINE))
    }

    /** Part of one line - the line starting at [line] - from [a] to [b]. */
    private class Seg(val line: Int, val a: Int, val b: Int)

    private fun toggle(src: String, s: Int, e: Int, style: Style): Result {
        if (s == e) return toggleAt(src, s, style)
        val segments = segments(src, s, e)
        if (segments.isEmpty()) return Result(emptyList(), s, e)
        val runs = segments.map { runAround(src, it, style) }
        // All of it already in the style: it comes off. Any of it not: it goes on the rest,
        // the way a word processor settles a selection that is only half bold.
        val off = runs.all { it != null }
        val changes = segments.zip(runs).flatMap { (seg, run) ->
            when {
                off -> unstyle(src, seg, run!!, style)
                run == null -> wrap(src, seg, style)
                else -> emptyList()
            }
        }
        // The words stay selected, and only the words: not the spaces the marks went inside.
        return Result(
            changes,
            moved(segments.first().a, changes),
            moved(segments.last().b, changes, after = false)
        )
    }

    /** The same with nothing selected: about what is typed next. */
    private fun toggleAt(src: String, p: Int, style: Style): Result {
        val ls = lineStart(src, p)
        val runs = runsOn(src, ls).filter { it.kind in style.kinds }
        val inside = runs.filter { p >= it.bodyStart && p <= it.bodyEnd }.minByOrNull { it.end - it.start }
        if (inside != null) return when (p) {
            // At the end of the words: out past the style's closing marks, so what is typed
            // next is not in it. At the start of them: out in front of the opening ones.
            inside.bodyEnd -> caret(p + closeLen(inside, style))
            inside.bodyStart -> caret(p - openLen(inside, style))
            // In the middle of them: the style comes off the whole run, the way a word
            // processor takes bold off the word the caret is in.
            else -> unstyle(src, Seg(ls, inside.bodyStart, inside.bodyEnd), inside, style)
                .let { Result(it, moved(p, it), moved(p, it)) }
        }
        // Just after a run or just before one: back into it, rather than a second pair of
        // marks up against the first that the parser would read as one run with stars in it.
        runs.firstOrNull { it.end == p }?.let { return caret(p - closeLen(it, style)) }
        runs.firstOrNull { it.start == p }?.let { return caret(p + openLen(it, style)) }
        // Between the empty pair the last press put down: take it away again.
        if (emptyPair(src, p, style)) {
            val open = style.open.length
            return caret(p - open, listOf(Change(p - open, p, ""), Change(p, p + style.close.length, "")))
        }
        // An empty pair with the caret between, so that what is typed next is in the style.
        return caret(p + style.open.length, listOf(Change(p, p, style.open + style.close)))
    }

    private fun caret(p: Int, changes: List<Change> = emptyList()) = Result(changes, p, p)

    /** Whether [s]..[e] is in [style] - for a caret, whether what is typed there would be. */
    private fun inStyle(src: String, s: Int, e: Int, style: Style): Boolean {
        if (s != e) {
            val segments = segments(src, s, e)
            return segments.isNotEmpty() && segments.all { runAround(src, it, style) != null }
        }
        val runs = runsOn(src, lineStart(src, s)).filter { it.kind in style.kinds }
        if (runs.any { s >= it.bodyStart && s <= it.bodyEnd }) return true
        return runs.none { it.start == s || it.end == s } && emptyPair(src, s, style)
    }

    /**
     * Whether [p] is between an empty pair of [style]'s marks - what a press with nothing
     * selected puts down, and which the parser does not see as a run, having no words in it.
     */
    private fun emptyPair(src: String, p: Int, style: Style): Boolean {
        if (style == Style.UNDERLINE) {
            val open = style.open.length
            return p >= open && src.startsWith(style.open, p - open) && src.startsWith(style.close, p)
        }
        // Stars are counted rather than matched, because bold and italic share them: two on
        // either side is an empty bold, one an empty italic, and three the two at once.
        val before = stars(src, p, -1)
        val after = stars(src, p, 1)
        return if (style == Style.BOLD) before >= 2 && after >= 2
        else before % 2 == 1 && after % 2 == 1
    }

    private fun stars(src: String, p: Int, step: Int): Int {
        var n = 0
        var i = if (step < 0) p - 1 else p
        while (i in src.indices && src[i] == '*') {
            n++
            i += step
        }
        return n
    }

    /**
     * How many of [run]'s marks on each side are [style]'s. All of them, except in a run
     * that is bold and italic at once, where the three stars are two of one and one of the
     * other.
     */
    private fun openLen(run: Run, style: Style) =
        if (run.kind == Kind.BOLD_ITALIC) style.open.length else run.bodyStart - run.start

    private fun closeLen(run: Run, style: Style) =
        if (run.kind == Kind.BOLD_ITALIC) style.close.length else run.end - run.bodyEnd

    /** Takes [style] off the part of [run] that [seg] covers. */
    private fun unstyle(src: String, seg: Seg, run: Run, style: Style): List<Change> {
        val open = openLen(run, style)
        val close = closeLen(run, style)
        val a = maxOf(seg.a, run.bodyStart)
        val b = minOf(seg.b, run.bodyEnd)
        // Only some of a plain run's words: the run is closed before them and opened again
        // after them, in its own marks so that __this__ stays underscores. A run that is bold
        // and italic at once cannot be cut that way, and loses the style from all its words.
        val cut = run.kind != Kind.BOLD_ITALIC
        val opening = src.substring(run.bodyStart - open, run.bodyStart)
        val closing = src.substring(run.bodyEnd, run.bodyEnd + close)
        return listOf(
            if (cut && a > run.bodyStart) Change(a, a, closing)
            else Change(run.bodyStart - open, run.bodyStart, ""),
            if (cut && b < run.bodyEnd) Change(b, b, opening)
            else Change(run.bodyEnd, run.bodyEnd + close, "")
        )
    }

    /** Puts [style] on [seg]. */
    private fun wrap(src: String, seg: Seg, style: Style): List<Change> = buildList {
        add(Change(seg.a, seg.a, style.open))
        // The style comes off anything inside that already has it: bold around a word that
        // is bold already is two pairs of stars that the parser cannot pair up.
        for (run in runsOn(src, seg.line)) {
            if (run.kind in style.kinds && run.start >= seg.a && run.end <= seg.b) {
                add(Change(run.bodyStart - openLen(run, style), run.bodyStart, ""))
                add(Change(run.bodyEnd, run.bodyEnd + closeLen(run, style), ""))
            }
        }
        add(Change(seg.b, seg.b, style.close))
    }

    /**
     * The selection, a line at a time and without the spaces at either end: marks go round
     * words, and none of the inline styles reaches from one line to the next.
     */
    private fun segments(src: String, s: Int, e: Int): List<Seg> {
        val out = mutableListOf<Seg>()
        var ls = lineStart(src, s)
        while (ls <= e) {
            val le = lineEnd(src, ls)
            var a = maxOf(s, ls)
            var b = minOf(e, le)
            while (a < b && src[a].isWhitespace()) a++
            while (b > a && src[b - 1].isWhitespace()) b--
            if (a < b) out += Seg(ls, a, b)
            if (le >= src.length) break
            ls = le + 1
        }
        return out
    }

    /** The innermost run of [style] that holds all of [seg], its marks included. */
    private fun runAround(src: String, seg: Seg, style: Style): Run? =
        runsOn(src, seg.line)
            .filter { it.kind in style.kinds && it.start <= seg.a && seg.b <= it.end }
            .minByOrNull { it.end - it.start }

    /** The inline markdown on the line starting at [ls], as offsets into the note. */
    private fun runsOn(src: String, ls: Int): List<Run> =
        NoteMarkdown.runs(src.substring(ls, lineEnd(src, ls)))
            .map { Run(it.kind, ls + it.start, ls + it.bodyStart, ls + it.bodyEnd, ls + it.end) }

    // ----------------------------------------------------------- headings and lists

    private enum class LineKind { PLAIN, HEADING, BULLET, TASK, NUMBER }

    /**
     * A line, and what it opens with: its indent runs [start]..[indentEnd], and any heading
     * or list mark after that runs to [markEnd], where the words start.
     */
    private class Line(
        val start: Int,
        val end: Int,
        val indentEnd: Int,
        val markEnd: Int,
        val kind: LineKind,
        /** A heading's level, or a numbered item's number. */
        val level: Int = 0,
        /** A bullet's own character, or the full stop or bracket after a number. */
        val sign: String = ""
    )

    private fun heading(src: String, s: Int, e: Int, level: Int): Result {
        val lines = lines(src, s, e)
        // A heading pressed on lines that are all that heading already turns them back into
        // text, like any other toggle.
        val off = level == 0 || lines.all { it.kind == LineKind.HEADING && it.level == level }
        val changes = lines.mapNotNull { line ->
            when {
                // Text: the heading or list mark comes off, and the indent stays.
                off -> if (line.markEnd > line.indentEnd) Change(line.indentEnd, line.markEnd, "") else null
                // A heading is neither indented nor a list item as well: its mark replaces
                // whatever the line opened with.
                else -> Change(line.start, line.markEnd, "#".repeat(level) + " ")
            }
        }
        return Result(changes, moved(s, changes), moved(e, changes))
    }

    private fun list(src: String, s: Int, e: Int, numbered: Boolean): Result {
        val lines = lines(src, s, e)
        if (lines.isEmpty()) return Result(emptyList(), s, e)
        val kinds = if (numbered) setOf(LineKind.NUMBER) else setOf(LineKind.BULLET, LineKind.TASK)
        val off = lines.all { it.kind in kinds }
        // Numbering carries on from a numbered item just above: a list being added to, not
        // a second one starting again at one.
        var n = numberAbove(src, lines.first().start)
        val changes = lines.mapNotNull { line ->
            when {
                off -> Change(line.indentEnd, line.markEnd, "")
                // A to-do is a bullet already, with a box; made a bullet again, it keeps it.
                !numbered && line.kind in kinds -> null
                else -> Change(line.indentEnd, line.markEnd, if (numbered) "${++n}. " else "- ")
            }
        }
        return Result(changes, moved(s, changes), moved(e, changes))
    }

    private fun numberAbove(src: String, first: Int): Int {
        if (first == 0) return 0
        val above = parseLine(src, lineStart(src, first - 1))
        return if (above.kind == LineKind.NUMBER) above.level else 0
    }

    /**
     * What a return typed at [at] - over [at]..[until], if it replaces a selection - means
     * in the line it is typed in.
     *
     * In a list item it carries the list on: the next line starts with the same bullet, the
     * next number, or an empty box. In an item with nothing in it, it ends the list instead,
     * the way it does in any editor with lists: the mark comes off and no line is added.
     * Anywhere else - text, a heading, or inside the mark itself - it is just a new line.
     */
    fun onReturn(text: CharSequence, at: Int, until: Int): Continue? {
        val src = text.toString()
        val line = lineAt(src, at)
        if (line.kind == LineKind.PLAIN || line.kind == LineKind.HEADING) return null
        if (at < line.markEnd) return null
        val words = src.substring(line.markEnd, at)
        val rest = src.substring(minOf(until, line.end), line.end)
        if (words.isBlank() && rest.isBlank()) {
            return Continue.End(line.start, src.substring(line.start, line.markEnd))
        }
        val indent = src.substring(line.start, line.indentEnd)
        return Continue.Next(indent + when (line.kind) {
            LineKind.NUMBER -> "${line.level + 1}${line.sign} "
            LineKind.TASK -> "${line.sign} [ ] "
            else -> "${line.sign} "
        })
    }

    /**
     * The lines [s]..[e] reaches.
     *
     * A selection that ends at the very start of a line does not take that line in. Blank
     * lines inside a longer selection are left as they are; on its own, though, a blank line
     * is where the caret is, and a list or a heading started on it is the point.
     */
    private fun lines(src: String, s: Int, e: Int): List<Line> {
        val last = if (e > s && src[e - 1] == '\n') e - 1 else e
        val out = mutableListOf<Line>()
        var start = lineStart(src, s)
        while (true) {
            val line = parseLine(src, start)
            out += line
            if (line.end >= last || line.end >= src.length) break
            start = line.end + 1
        }
        return if (out.size > 1) out.filter { src.substring(it.start, it.end).isNotBlank() } else out
    }

    private fun lineAt(src: String, pos: Int) = parseLine(src, lineStart(src, pos))

    private fun parseLine(src: String, start: Int): Line {
        val end = lineEnd(src, start)
        val text = src.substring(start, end)
        fun line(m: MatchResult, kind: LineKind, level: Int = 0, sign: String = "") =
            Line(start, end, start + m.groupValues[1].length, start + m.range.last + 1, kind, level, sign)
        HEADING_MARK.find(text)?.let { return line(it, LineKind.HEADING, level = it.groupValues[2].length) }
        TASK_MARK.find(text)?.let { return line(it, LineKind.TASK, sign = it.groupValues[2]) }
        BULLET_MARK.find(text)?.let { return line(it, LineKind.BULLET, sign = it.groupValues[2]) }
        NUMBER_MARK.find(text)?.let {
            return line(it, LineKind.NUMBER, level = it.groupValues[2].toInt(), sign = it.groupValues[3])
        }
        val indent = text.length - text.trimStart(' ', '\t').length
        return Line(start, end, start + indent, start + indent, LineKind.PLAIN)
    }

    private val HEADING_MARK = Regex("""^([ \t]*)(#{1,6})(?:[ \t]+|$)""")
    private val TASK_MARK = Regex("""^([ \t]*)([-*+])[ \t]+\[[ xX]\](?:[ \t]+|$)""")
    private val BULLET_MARK = Regex("""^([ \t]*)([-*+])[ \t]+""")
    private val NUMBER_MARK = Regex("""^([ \t]*)(\d{1,9})([.)])[ \t]+""")

    // ------------------------------------------------------------------- links

    /**
     * The link [s]..[e] is in, if it is in one. A caret counts only strictly inside, so one
     * just after a link is typing after it rather than editing it.
     */
    fun linkAt(src: String, s: Int, e: Int): Link? {
        val ls = lineStart(src, s)
        if (e > lineEnd(src, ls)) return null
        val run = runsOn(src, ls).firstOrNull {
            it.kind == Kind.LINK && it.start <= s && e <= it.end &&
                (s != e || (s > it.start && s < it.end))
        } ?: return null
        // The closing mark is `](url)`: the address is what is between its brackets.
        return Link(run.start, run.bodyStart, run.bodyEnd, run.end,
            src.substring(run.bodyEnd + 2, run.end - 1))
    }

    /**
     * Links [s]..[e] to what was [typed] as an address.
     *
     * Inside a link already, it changes that link's address - or, with nothing typed, takes
     * the link off and leaves its words. With words selected, they become the link. With
     * nothing selected, the address goes in as its own words and is left selected, so that
     * typing puts what the link should say in its place.
     */
    fun link(src: String, s: Int, e: Int, typed: String): Result? {
        val url = address(typed)
        linkAt(src, s, e)?.let { link ->
            val changes = if (url.isEmpty()) {
                listOf(Change(link.start, link.bodyStart, ""), Change(link.bodyEnd, link.end, ""))
            } else {
                listOf(Change(link.bodyEnd + 2, link.end - 1, url))
            }
            return Result(changes, moved(s, changes), moved(e, changes, after = false))
        }
        if (url.isEmpty()) return null
        val seg = segments(src, s, e).firstOrNull()
            ?: return Result(listOf(Change(e, e, "[$url]($url)")), e + 1, e + 1 + url.length)
        return Result(
            listOf(Change(seg.a, seg.a, "["), Change(seg.b, seg.b, "]($url)")),
            seg.a + 1, seg.b + 1
        )
    }

    /**
     * What was typed, as an address a link can hold: https in front of one with no scheme,
     * and nothing in it that would end the link's brackets early.
     */
    private fun address(typed: String): String {
        var url = typed.trim()
        if (url.isEmpty()) return url
        if (!SCHEME.containsMatchIn(url)) url = "https://$url"
        return url.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
    }

    private val SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")

    // ------------------------------------------------------------------ plumbing

    private fun lineStart(src: String, pos: Int) = src.lastIndexOf('\n', pos - 1) + 1

    private fun lineEnd(src: String, pos: Int) =
        src.indexOf('\n', pos).let { if (it < 0) src.length else it }

    /**
     * Where [pos] ends up once [changes] are made.
     *
     * Something put in exactly at [pos] lands before it when [after] is set - a caret or a
     * selection's start goes past a mark put down where it stood - and after it otherwise,
     * which is what a selection's end wants. A [pos] inside something replaced goes to the
     * end of what replaced it.
     */
    private fun moved(pos: Int, changes: List<Change>, after: Boolean = true): Int {
        var out = pos
        for (c in changes) {
            val delta = c.text.length - (c.end - c.start)
            when {
                c.start == c.end -> if (c.start < pos || (c.start == pos && after)) out += delta
                c.end <= pos -> out += delta
                c.start < pos -> out += c.start + c.text.length - pos
            }
        }
        return out
    }
}
