package id.or.oo.hr.setup

import id.or.oo.hr.profile.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupLinesTest {

    private fun status(chatGpt: Boolean = false, provider: String? = null, model: String? = null) =
        SetupStatus(chatGpt, provider, model, null, emptySet(), false, 0)

    @Test
    fun signedInWithCodexShowsTheModel() {
        assertEquals("Signed in · gpt-5", chatGptLine(status(true, "openai-codex", "gpt-5")))
        assertEquals("Signed in", chatGptLine(status(true, "openai-codex")))
    }

    @Test
    fun signedInWithoutAProviderAsksForAModel() {
        assertEquals("Signed in · choose a model", chatGptLine(status(true)))
    }

    @Test
    fun signedInWithAnotherProviderSaysSo() {
        assertEquals("Signed in (another provider is active)", chatGptLine(status(true, "openrouter", "m")))
    }

    @Test
    fun notSignedIn() {
        assertEquals("Not signed in", chatGptLine(status(false, "openai-codex", "gpt-5")))
    }

    @Test
    fun theButtonFollowsTheSignInAndProvider() {
        assertEquals("Change model", chatGptButton(status(true, "openai-codex")))
        assertEquals("Use ChatGPT", chatGptButton(status(true, "openrouter")))
        assertEquals("Use ChatGPT", chatGptButton(status(true)))
        assertEquals("Sign in with ChatGPT", chatGptButton(status(false)))
    }

    @Test
    fun anUnknownProviderIsShown() {
        assertEquals("Active provider: nous · m1", otherProviderLine(status(provider = "nous", model = "m1")))
        assertEquals("Active provider: nous", otherProviderLine(status(provider = "nous")))
    }

    @Test
    fun knownOrAbsentProvidersShowNoOtherLine() {
        assertNull(otherProviderLine(status()))
        assertNull(otherProviderLine(status(provider = "openai-codex")))
        assertNull(otherProviderLine(status(provider = "openrouter")))
        assertNull(otherProviderLine(status(provider = "custom")))
    }
}
