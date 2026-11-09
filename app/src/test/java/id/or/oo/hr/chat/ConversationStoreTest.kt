package id.or.oo.hr.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class ConversationStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir by lazy { File(tmp.root, "chats") }
    private val store by lazy { ConversationStore(dir) }

    private fun conversation(id: String, updatedAt: Long = 1L, messages: List<ChatMessage> = listOf(ChatMessage(true, "hi"))) =
        Conversation(id, "session-$id", "Title $id", updatedAt, messages)

    @Test
    fun aSavedConversationLoadsBack() {
        val saved = Conversation(
            "abc-1",
            "s-9",
            "Halo dunia",
            1_700_000_000_123L,
            listOf(
                ChatMessage(true, "Apa kabar? 日本語 😀"),
                ChatMessage(false, "Baik\nterima kasih", listOf("terminal", "read_file"), true),
                ChatMessage(false, "plain"),
            ),
        )
        store.save(saved)

        assertEquals(saved, store.load("abc-1"))
    }

    @Test
    fun omittedToolsAndInterruptedDefaultOnRead() {
        dir.mkdirs()
        File(dir, "a.json").writeText(
            """{"id":"a","sessionId":"s","title":"t","updatedAt":5,"messages":[{"fromUser":false,"text":"x"}]}""",
        )

        assertEquals(listOf(ChatMessage(false, "x")), store.load("a")!!.messages)
    }

    @Test
    fun theListIsNewestFirstWithoutMessages() {
        store.save(conversation("old", 10))
        store.save(conversation("new", 30))
        store.save(conversation("mid", 20))

        val listed = store.list()

        assertEquals(listOf("new", "mid", "old"), listed.map { it.id })
        assertTrue(listed.all { it.messages.isEmpty() })
        assertEquals("Title new", listed[0].title)
        assertEquals("session-new", listed[0].sessionId)
        assertEquals(30L, listed[0].updatedAt)
    }

    @Test
    fun aMissingDirectoryListsNothing() {
        assertEquals(emptyList<Conversation>(), store.list())
    }

    @Test
    fun aCorruptFileIsSkipped() {
        store.save(conversation("good"))
        File(dir, "bad.json").writeText("{not json")

        assertEquals(listOf("good"), store.list().map { it.id })
        assertNull(store.load("bad"))
    }

    @Test
    fun malformedContentIsRejectedWhole() {
        store.save(conversation("good"))
        val bodies = mapOf(
            "m1" to """{"id":"m1","sessionId":"s","title":"t","updatedAt":1,"messages":[{"fromUser":true,"text":"ok"},{"fromUser":"yes","text":"x"}]}""",
            "m2" to """{"id":"m2","sessionId":"s","title":"t","updatedAt":1,"messages":[{"fromUser":true,"text":5}]}""",
            "m3" to """{"id":"m3","sessionId":"s","title":"t","updatedAt":1,"messages":[{"fromUser":true,"text":"a","tools":[1]}]}""",
            "m4" to """{"id":"m4","sessionId":"s","title":"t","updatedAt":1,"messages":[{"fromUser":true,"text":"a","interrupted":"no"}]}""",
            "m5" to """{"id":"m5","sessionId":"s","title":"t","updatedAt":1,"messages":["x"]}""",
            "m6" to """{"id":"m6","sessionId":"s","title":"t","updatedAt":1}""",
            "m7" to """{"id":"m7","sessionId":"s","title":"t","updatedAt":"1","messages":[]}""",
            "m8" to """{"id":"m8","title":"t","updatedAt":1,"messages":[]}""",
            "m9" to """{"id":"m9","sessionId":"s","updatedAt":1,"messages":[]}""",
            "m10" to """{"id":"m10","sessionId":"s","title":"t","updatedAt":1,"messages":[{"fromUser":true,"text":"a","tools":"x"}]}""",
        )
        bodies.forEach { (id, body) -> File(dir, "$id.json").writeText(body) }

        bodies.keys.forEach { assertNull(it, store.load(it)) }
        assertEquals(listOf("good"), store.list().map { it.id })
    }

    @Test
    fun pendingTurnsRoundTripAndReadTheLegacyField() {
        val pending = conversation("p").copy(pendingTurns = listOf("0f6c3a52-7d1e-4b8a-9c3f-2e5d6a7b8c9d", "b2"))
        store.save(pending)

        assertEquals(pending, store.load("p"))
        assertEquals(listOf("0f6c3a52-7d1e-4b8a-9c3f-2e5d6a7b8c9d", "b2"), store.list().single().pendingTurns)
        assertFalse(File(dir, "p.json").readText().contains("\"pendingTurn\""))

        store.save(pending.copy(pendingTurns = emptyList()))

        assertEquals(emptyList<String>(), store.load("p")!!.pendingTurns)
        assertFalse(File(dir, "p.json").readText().contains("pendingTurn"))

        val head = """{"id":"ID","sessionId":"s","title":"t","updatedAt":1,"messages":[]"""
        File(dir, "l.json").writeText(head.replace("ID", "l") + ""","pendingTurn":"old"}""")
        File(dir, "w.json").writeText(head.replace("ID", "w") + ""","pendingTurn":"old","pendingTurns":["a","b"]}""")

        assertEquals(listOf("old"), store.load("l")!!.pendingTurns)
        assertEquals(listOf("a", "b"), store.load("w")!!.pendingTurns)

        store.save(store.load("l")!!)

        val rewritten = File(dir, "l.json").readText()
        assertFalse(rewritten.contains("\"pendingTurn\""))
        assertEquals(listOf("old"), store.load("l")!!.pendingTurns)
    }

    @Test
    fun aNonStringPendingTurnIsMalformed() {
        dir.mkdirs()
        val head = """{"id":"ID","sessionId":"s","title":"t","updatedAt":1,"messages":[]"""
        File(dir, "n.json").writeText(head.replace("ID", "n") + ""","pendingTurn":5}""")
        File(dir, "o.json").writeText(head.replace("ID", "o") + ""","pendingTurn":{"id":"x"}}""")
        File(dir, "z.json").writeText(head.replace("ID", "z") + ""","pendingTurn":null}""")
        File(dir, "a.json").writeText(head.replace("ID", "a") + ""","pendingTurns":["x",1]}""")
        File(dir, "s.json").writeText(head.replace("ID", "s") + ""","pendingTurns":"x"}""")
        File(dir, "e.json").writeText(head.replace("ID", "e") + ""","pendingTurns":null}""")

        assertNull(store.load("n"))
        assertNull(store.load("o"))
        assertNull(store.load("a"))
        assertNull(store.load("s"))
        assertEquals(emptyList<String>(), store.load("z")!!.pendingTurns)
        assertEquals(emptyList<String>(), store.load("e")!!.pendingTurns)
        assertEquals(listOf("e", "z"), store.list().map { it.id }.sorted())
    }

    @Test
    fun aFileWhoseIdDiffersFromItsNameIsIgnored() {
        dir.mkdirs()
        File(dir, "one.json").writeText("""{"id":"two","sessionId":"s","title":"t","updatedAt":1,"messages":[]}""")

        assertNull(store.load("one"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun filesWithInvalidNamesAndTempFilesAreNotListed() {
        store.save(conversation("good"))
        val body = """{"id":"x y","sessionId":"s","title":"t","updatedAt":1,"messages":[]}"""
        File(dir, "x y.json").writeText(body)
        File(dir, ".good.json.tmp").writeText(body)
        File(dir, "notes.txt").writeText(body)
        File(dir, "sub.json").mkdirs()

        assertEquals(listOf("good"), store.list().map { it.id })
    }

    @Test
    fun filesAreOwnerOnly() {
        store.save(conversation("mine"))

        val permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dir, "mine.json").toPath()))

        assertEquals("rw-------", permissions)
    }

    @Test
    fun savingAgainReplacesTheFile() {
        store.save(conversation("same", 1))
        store.save(conversation("same", 2, listOf(ChatMessage(true, "a"), ChatMessage(false, "b"))))

        assertEquals(2, store.load("same")!!.messages.size)
        assertEquals(1, store.list().size)
    }

    @Test
    fun deleteRemovesTheFile() {
        store.save(conversation("gone"))

        store.delete("gone")

        assertNull(store.load("gone"))
        assertTrue(store.list().isEmpty())
        assertTrue(!File(dir, "gone.json").exists())
    }

    @Test
    fun deletingAMissingConversationDoesNothing() {
        store.delete("never")
        assertNotNull(store)
    }

    @Test
    fun anIdWithPathCharactersIsRefused() {
        val bad = listOf("../x", "a/b", "", "a.b", "a b", "x".repeat(65))
        bad.forEach { id ->
            assertThrows(id, IllegalArgumentException::class.java) { store.save(conversation(id)) }
            assertThrows(id, IllegalArgumentException::class.java) { store.load(id) }
            assertThrows(id, IllegalArgumentException::class.java) { store.delete(id) }
        }
        store.save(conversation("x".repeat(64)))
        assertNotNull(store.load("x".repeat(64)))
    }

    @Test
    fun titlesAreShortened() {
        assertEquals("Hello world", titleFor("  Hello \n\t world  "))
        assertEquals("a".repeat(40), titleFor("a".repeat(40)))
        assertEquals("a".repeat(40) + "…", titleFor("a".repeat(41)))
        assertEquals("New chat", titleFor(" \n\t "))
        assertEquals("New chat", titleFor(""))
    }

    @Test
    fun aTitleIsNotCutInsideASurrogatePair() {
        val text = "a".repeat(39) + "😀" + "tail"
        val title = titleFor(text)

        assertEquals("a".repeat(39) + "…", title)
        assertEquals("a".repeat(38) + "😀" + "…", titleFor("a".repeat(38) + "😀" + "tail"))
    }
}
