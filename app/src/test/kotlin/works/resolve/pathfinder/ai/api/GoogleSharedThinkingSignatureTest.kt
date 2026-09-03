package works.resolve.pathfinder.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mirrors pi's google-thinking-signature.test.ts: thought-signature detection
 * (`isThinkingPart`) and retention (`retainThoughtSignature`) on streamed parts.
 */
class GoogleSharedThinkingSignatureTest {

    @Test
    fun `thought true marks a part as thinking regardless of signature`() {
        assertTrue(GoogleShared.isThinkingPart(buildJsonObject { put("thought", true) }))
        assertTrue(
            GoogleShared.isThinkingPart(
                buildJsonObject {
                    put("thought", true)
                    put("thoughtSignature", "opaque-signature")
                },
            ),
        )
    }

    @Test
    fun `thoughtSignature alone does not make a part thinking`() {
        // Per Google docs, thoughtSignature is for context replay and can
        // appear on any part type. Only thought === true indicates thinking
        // content. See https://ai.google.dev/gemini-api/docs/thought-signatures
        assertFalse(
            GoogleShared.isThinkingPart(buildJsonObject { put("thoughtSignature", "opaque-signature") }),
        )
        assertFalse(
            GoogleShared.isThinkingPart(
                buildJsonObject {
                    put("thought", false)
                    put("thoughtSignature", "opaque-signature")
                },
            ),
        )
    }

    @Test
    fun `empty or missing signature without thought is not thinking`() {
        assertFalse(GoogleShared.isThinkingPart(buildJsonObject { }))
        assertFalse(
            GoogleShared.isThinkingPart(
                buildJsonObject {
                    put("thought", false)
                    put("thoughtSignature", "")
                },
            ),
        )
    }

    @Test
    fun `retainThoughtSignature keeps the existing signature when deltas omit it`() {
        val first = GoogleShared.retainThoughtSignature(existing = null, incoming = "sig-1")
        assertEquals("sig-1", first)

        val second = GoogleShared.retainThoughtSignature(first, incoming = null)
        assertEquals("sig-1", second)

        val third = GoogleShared.retainThoughtSignature(second, incoming = "")
        assertEquals("sig-1", third)
    }

    @Test
    fun `retainThoughtSignature updates when a new non-empty signature arrives`() {
        assertEquals("sig-2", GoogleShared.retainThoughtSignature("sig-1", "sig-2"))
    }
}
