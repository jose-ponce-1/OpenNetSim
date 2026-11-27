package com.opennetsim.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class SupportLinksTest {

    @Test
    fun linksUseHttps() {
        assertTrue(SupportLinks.SOURCE_CODE_URL.startsWith("https://github.com/"))
        assertTrue(SupportLinks.KO_FI_URL.startsWith("https://ko-fi.com/"))
        assertTrue("Página de un usuario, no la portada", SupportLinks.KO_FI_URL.length > "https://ko-fi.com/".length)
    }
}
