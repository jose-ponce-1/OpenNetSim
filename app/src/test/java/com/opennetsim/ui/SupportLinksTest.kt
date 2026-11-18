package com.opennetsim.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportLinksTest {

    @Test
    fun googlePlayInstallsHideTheDonationLink() {
        assertFalse(SupportLinks.donationLinkAllowed("com.android.vending"))
        assertFalse(SupportLinks.donationLinkAllowed("com.google.android.feedback"))
    }

    @Test
    fun otherInstallsShowTheDonationLink() {
        assertTrue("APK de GitHub abierto desde el gestor de archivos", SupportLinks.donationLinkAllowed(null))
        assertTrue(SupportLinks.donationLinkAllowed("com.google.android.packageinstaller"))
        assertTrue("Instalado con adb", SupportLinks.donationLinkAllowed("com.android.shell"))
    }

    @Test
    fun linksUseHttps() {
        assertTrue(SupportLinks.SOURCE_CODE_URL.startsWith("https://github.com/"))
        assertTrue(SupportLinks.KO_FI_URL.startsWith("https://ko-fi.com/"))
        assertTrue("Página de un usuario, no la portada", SupportLinks.KO_FI_URL.length > "https://ko-fi.com/".length)
    }
}
