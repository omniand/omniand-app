package dev.omniand.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppDeepLinkTest {
    @Test fun acceptsCanonicalRoutes() {
        assertTrue(WebAppActivity.validRoute("#/thread?id=42"))
        assertTrue(WebAppActivity.validRoute("#/compose?to=%2B331&body=hello+world"))
    }

    @Test fun rejectsNavigationAndScriptRoutes() {
        assertFalse(WebAppActivity.validRoute("https://attacker.example/"))
        assertFalse(WebAppActivity.validRoute("#/thread?id=1&next=https://attacker.example"))
        assertFalse(WebAppActivity.validRoute("#/compose?to=x#javascript:alert(1)"))
    }
}
