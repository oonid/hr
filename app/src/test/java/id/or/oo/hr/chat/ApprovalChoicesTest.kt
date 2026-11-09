package id.or.oo.hr.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalChoicesTest {

    @Test
    fun onceAndDenyAreOffered() {
        assertEquals(listOf("once" to "Allow once", "deny" to "Deny"), offeredChoices(listOf("deny", "once")))
    }

    @Test
    fun alwaysIsNeverOffered() {
        assertEquals(
            listOf("once", "session", "deny"),
            offeredChoices(listOf("deny", "always", "session", "once")).map { it.first },
        )
    }

    @Test
    fun onlyAlwaysOffersNothing() {
        assertEquals(emptyList<Pair<String, String>>(), offeredChoices(listOf("always")))
    }
}
