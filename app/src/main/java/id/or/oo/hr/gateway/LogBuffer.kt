package id.or.oo.hr.gateway

import id.or.oo.hr.MIN_SECRET_LENGTH
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class LogBuffer(
    private val maxLines: Int = MAX_LINES,
    private val maxLineChars: Int = MAX_LINE_CHARS,
    private val secrets: () -> List<String> = { emptyList() },
) {
    private enum class Mode { TEXT, ESCAPE, INTERMEDIATE, CSI, STRING }

    private class Window(val key: String, val start: Int)

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val current = StringBuilder()
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)
    private var mode = Mode.TEXT
    private var sequenceLength = 0
    private var returned = false
    private var known: List<String> = emptyList()
    private var windows: Map<String, List<Window>> = emptyMap()
    private var rawLimit = maxLineChars

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    fun append(buf: ByteArray, off: Int, len: Int) {
        val found = usable(secrets())
        synchronized(lock) {
            remember(found)
            val input = ByteBuffer.allocate(carry.size + len)
            input.put(carry).put(buf, off, len)
            input.flip()
            val output = CharBuffer.allocate(input.remaining())
            decoder.decode(input, output, false)
            carry = ByteArray(input.remaining()).also { input.get(it) }
            output.flip()
            while (output.hasRemaining()) feed(output.get())
            _revision.value += 1
        }
    }

    fun mark(text: String) {
        val found = usable(secrets())
        synchronized(lock) {
            remember(found)
            val open = current.length - trailingSecretPrefix()
            if (open > 0) store(current.substring(0, open))
            current.delete(0, open)
            mode = Mode.TEXT
            returned = false
            carry = ByteArray(0)
            decoder.reset()
            store(found.fold(text) { line, secret -> line.replace(secret, REDACTED) })
            _revision.value += 1
        }
    }

    fun snapshot(): List<String> = synchronized(lock) {
        val live = cut(scrub(current.substring(0, current.length - trailingSecretPrefix())))
        if (live.isEmpty()) lines.toList() else lines.toList() + live
    }

    fun tail(count: Int): List<String> = snapshot().takeLast(count)

    private fun usable(found: List<String>): List<String> =
        found.filter { it.length >= MIN_SECRET_LENGTH }.distinct().sortedByDescending { it.length }

    private fun remember(found: List<String>) {
        if (found == known) return
        known = found
        rawLimit = maxLineChars + (found.maxOfOrNull { it.length - 1 } ?: 0)
        windows = index(found)
    }

    private fun index(found: List<String>): Map<String, List<Window>> {
        val map = HashMap<String, MutableList<Window>>()
        for (key in found) {
            if (key.length < MIN_SCRUBBED) continue
            for (start in 0..key.length - MIN_SECRET_LENGTH) {
                map.getOrPut(key.substring(start, start + MIN_SECRET_LENGTH)) { mutableListOf() }.add(Window(key, start))
            }
        }
        return map
    }

    private fun feed(c: Char) {
        when {
            c == ESC -> {
                mode = Mode.ESCAPE
                sequenceLength = 0
            }
            mode == Mode.TEXT -> text(c)
            else -> {
                sequenceLength += 1
                val limit = if (mode == Mode.STRING) MAX_STRING else MAX_SEQUENCE
                val next = if (sequenceLength > limit || c == '\n') null else step(c)
                mode = next ?: Mode.TEXT
                if (next == null) text(c)
            }
        }
    }

    private fun step(c: Char): Mode? = when (mode) {
        Mode.ESCAPE -> escape(c)
        Mode.INTERMEDIATE -> sequence(c, INTERMEDIATES, ESCAPE_FINALS)
        Mode.CSI -> sequence(c, CSI_MIDDLE, CSI_FINALS)
        else -> if (c == BEL) Mode.TEXT else Mode.STRING
    }

    private fun escape(c: Char): Mode? = when (c) {
        '[' -> Mode.CSI
        ']', 'P', 'X', '^', '_' -> Mode.STRING
        in INTERMEDIATES -> Mode.INTERMEDIATE
        in ESCAPE_FINALS -> Mode.TEXT
        else -> null
    }

    private fun sequence(c: Char, middle: CharRange, finals: CharRange): Mode? = when (c) {
        in middle -> mode
        in finals -> Mode.TEXT
        else -> null
    }

    private fun text(c: Char) {
        when {
            c == '\n' -> endLine()
            c == '\r' -> returned = true
            c == '\b' -> backspace()
            c == '\t' || (c >= ' ' && c != DEL) -> put(c)
        }
    }

    private fun put(c: Char) {
        if (returned) {
            current.setLength(0)
            returned = false
        }
        if (current.length >= rawLimit) return
        current.append(c)
        val secret = known.firstOrNull { current.endsWith(it) } ?: return
        current.setLength(current.length - secret.length)
        current.append(REDACTED)
    }

    private fun backspace() {
        if (returned || current.isEmpty()) return
        val last = current.length - 1
        val pair = last > 0 && current[last].isLowSurrogate() && current[last - 1].isHighSurrogate()
        current.setLength(if (pair) last - 1 else last)
    }

    private fun trailingSecretPrefix(): Int {
        var best = 0
        for (secret in known) {
            var size = minOf(secret.length - 1, current.length)
            while (size >= MIN_HIDDEN && size > best) {
                if (endsWithStartOf(secret, size)) {
                    best = size
                    break
                }
                size--
            }
        }
        return best
    }

    private fun endsWithStartOf(secret: String, size: Int): Boolean {
        val from = current.length - size
        for (i in 0 until size) if (current[from + i] != secret[i]) return false
        return true
    }

    private fun endLine() {
        store(current.toString())
        current.setLength(0)
        returned = false
    }

    private fun store(line: String) {
        lines.addLast(cut(scrub(line)))
        while (lines.size > maxLines) lines.removeFirst()
    }

    private fun scrub(line: String): String {
        if (windows.isEmpty()) return line
        val out = StringBuilder()
        var at = 0
        while (at < line.length) {
            val run = longestRun(line, at)
            if (run >= MIN_SECRET_LENGTH) {
                out.append(REDACTED)
                at += run
            } else {
                out.append(line[at])
                at += 1
            }
        }
        return out.toString()
    }

    private fun longestRun(line: String, at: Int): Int {
        if (at + MIN_SECRET_LENGTH > line.length) return 0
        val candidates = windows[line.substring(at, at + MIN_SECRET_LENGTH)] ?: return 0
        return candidates.maxOf { common(line, at, it.key, it.start) }
    }

    private fun common(line: String, at: Int, key: String, start: Int): Int {
        var size = 0
        while (at + size < line.length && start + size < key.length && line[at + size] == key[start + size]) size += 1
        return size
    }

    private fun cut(line: String): String {
        val end = minOf(line.length, maxLineChars)
        return line.substring(0, if (end > 0 && line[end - 1].isHighSurrogate()) end - 1 else end)
    }

    companion object {
        const val MAX_LINES = 2_000
        const val MAX_LINE_CHARS = 2_000
        private const val MAX_SEQUENCE = 256
        private const val MAX_STRING = 4_096
        private const val MIN_HIDDEN = 4
        private const val MIN_SCRUBBED = 16
        private const val REDACTED = "[redacted]"
        private const val ESC = '\u001b'
        private const val BEL = '\u0007'
        private const val DEL = '\u007f'
        private val INTERMEDIATES = ' '..'/'
        private val ESCAPE_FINALS = '0'..'~'
        private val CSI_MIDDLE = ' '..'?'
        private val CSI_FINALS = '@'..'~'
    }
}
