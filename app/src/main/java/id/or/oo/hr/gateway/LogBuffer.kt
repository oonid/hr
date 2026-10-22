package id.or.oo.hr.gateway

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
    private enum class Mode { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE, CHARSET }

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val current = StringBuilder()
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)
    private var mode = Mode.TEXT

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    fun append(buf: ByteArray, off: Int, len: Int) {
        synchronized(lock) {
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
        synchronized(lock) {
            if (current.isNotEmpty()) endLine()
            mode = Mode.TEXT
            carry = ByteArray(0)
            decoder.reset()
            current.append(text)
            endLine()
            _revision.value += 1
        }
    }

    fun snapshot(): List<String> = synchronized(lock) {
        if (current.isEmpty()) lines.toList() else lines.toList() + redact(current.toString())
    }

    fun tail(count: Int): List<String> = snapshot().takeLast(count)

    private fun feed(c: Char) {
        mode = when (mode) {
            Mode.TEXT -> text(c)
            Mode.ESCAPE -> when (c) {
                '[' -> Mode.CSI
                ']' -> Mode.OSC
                '(', ')', '*', '+' -> Mode.CHARSET
                else -> Mode.TEXT
            }
            Mode.CSI -> if (c in '@'..'~') Mode.TEXT else Mode.CSI
            Mode.OSC -> when (c) {
                BEL -> Mode.TEXT
                ESC -> Mode.OSC_ESCAPE
                else -> Mode.OSC
            }
            Mode.OSC_ESCAPE, Mode.CHARSET -> Mode.TEXT
        }
    }

    private fun text(c: Char): Mode {
        when {
            c == ESC -> return Mode.ESCAPE
            c == '\n' -> endLine()
            c == '\t' || (c >= ' ' && c != DEL) -> if (current.length < maxLineChars) current.append(c)
        }
        return Mode.TEXT
    }

    private fun endLine() {
        lines.addLast(redact(current.toString()))
        current.setLength(0)
        while (lines.size > maxLines) lines.removeFirst()
    }

    private fun redact(line: String): String =
        secrets().filter { it.length >= MIN_SECRET }.fold(line) { text, secret -> text.replace(secret, REDACTED) }

    companion object {
        const val MAX_LINES = 2_000
        const val MAX_LINE_CHARS = 2_000
        private const val MIN_SECRET = 8
        private const val REDACTED = "[redacted]"
        private const val ESC = '\u001b'
        private const val BEL = '\u0007'
        private const val DEL = '\u007f'
    }
}
