package works.resolve.pathfinder.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import works.resolve.pathfinder.ai.auth.AuthEvent

class ProviderLoginDeviceCodeTest {

    @Test
    fun `device-code actions default to offering browser and code copy`() {
        val event = AuthEvent.DeviceCode("ABCD-EFGH", "https://example.com/device")

        assertTrue(event.offersBrowser)
        assertTrue(event.offersCodeCopy)
    }

    @Test
    fun `device-code actions honor provider overrides`() {
        listOf(false to false, true to false, false to true).forEach { (browser, copy) ->
            val event = AuthEvent.DeviceCode(
                userCode = "ABCD-EFGH",
                verificationUri = "https://example.com/device",
                openBrowserOnConfirm = browser,
                copyCodeOnConfirm = copy
            )

            assertEquals(browser, event.offersBrowser)
            assertEquals(copy, event.offersCodeCopy)
        }
    }
}
