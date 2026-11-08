package id.or.oo.hr

enum class TerminalStart { OPEN, SHOW_ENDED }

fun terminalStart(restored: Boolean, sessionOpen: Boolean): TerminalStart =
    if (restored && !sessionOpen) TerminalStart.SHOW_ENDED else TerminalStart.OPEN
