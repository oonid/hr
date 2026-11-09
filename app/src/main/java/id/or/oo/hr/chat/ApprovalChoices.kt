package id.or.oo.hr.chat

private val SUPPORTED = listOf("once" to "Allow once", "session" to "Allow for this chat", "deny" to "Deny")

fun offeredChoices(choices: List<String>): List<Pair<String, String>> = SUPPORTED.filter { it.first in choices }
