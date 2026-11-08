package id.or.oo.hr.setup

import id.or.oo.hr.Hermes
import id.or.oo.pr.session.Bind
import id.or.oo.pr.session.DistroCommand

private val ANSI_SEQUENCE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

fun stripAnsi(line: String): String = ANSI_SEQUENCE.replace(line, "")

class HermesCommand(
    private val command: DistroCommand,
    private val profile: Bind,
) {
    suspend fun run(script: String, onLine: (String) -> Unit): Int =
        command.run(
            listOf(
                "login", Hermes.CONTAINER, "--user", "root",
                "--custom-bind", "${profile.hostPath}:${profile.guestPath}",
                "--", script,
            ),
        ) { onLine(stripAnsi(it)) }
}
