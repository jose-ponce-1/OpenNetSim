package com.opennetsim.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSourceLicensesTest {

    @Test
    fun parsesSortedArtifactsFromInputStreamAndSkipsMalformedLines() {
        val tsv = listOf(
            "org.jetbrains.kotlin\tkotlin-stdlib\tKotlin Stdlib\t2.4.21\tApache-2.0\thttps://kotlinlang.org/",
            "",
            "invalid-short-line",
            "\tmissing-group\tName\t1.0\tApache-2.0\thttps://example.com",
            "androidx.core\tcore-ktx\tCore Kotlin Extensions\t1.19.1\tApache-2.0\thttps://example.org/",
            "androidx.core\tcore\t\t1.19.1\tApache-2.0\t"
        ).joinToString("\n")

        val parsed = OpenSourceLicenses.parse(tsv.byteInputStream(Charsets.UTF_8))

        assertEquals(3, parsed.size)
        assertEquals("core", parsed[0].artifact)
        assertEquals("core", parsed[0].name)
        assertEquals("core-ktx", parsed[1].artifact)
        assertEquals("Core Kotlin Extensions", parsed[1].name)
        assertEquals("kotlin-stdlib", parsed[2].artifact)
    }

    @Test
    fun groupsByMavenGroupAndLicenseWithReadableTitlesAndDeduplicatedVersions() {
        val artifacts = OpenSourceLicenses.parse(
            listOf(
                "androidx.lifecycle\tlifecycle-viewmodel-android\tLifecycle ViewModel\t2.11.1\tApache-2.0\thttps://a",
                "androidx.lifecycle\tlifecycle-runtime-android\tLifecycle Runtime\t2.11.1\tApache-2.0\thttps://a",
                "org.jetbrains.kotlin\tkotlin-stdlib\tKotlin Stdlib\t2.4.21\tApache-2.0\thttps://kotlinlang.org/",
                "androidx.compose.material\tmaterial-icons-extended-android\tIcons\t1.7.8\tApache-2.0\thttps://a",
                "androidx.compose.material\tmaterial-ripple-android\tRipple\t1.13.0\tApache-2.0\thttps://a"
            ).joinToString("\n")
        )

        val groups = OpenSourceLicenses.group(artifacts)

        assertEquals(3, groups.size)

        assertEquals("androidx.compose.material", groups[0].title)
        assertEquals("1.7.8 · 1.13.0", groups[0].versionsSummary)
        assertEquals(2, groups[0].artifacts.size)

        assertEquals("androidx.lifecycle", groups[1].title)
        assertEquals("2.11.1", groups[1].versionsSummary)
        assertEquals(
            listOf("lifecycle-runtime-android", "lifecycle-viewmodel-android"),
            groups[1].artifacts.map { it.artifact }
        )

        assertEquals("Kotlin Stdlib", groups[2].title)
        assertEquals("2.4.21", groups[2].versionsSummary)
        assertEquals(1, groups[2].artifacts.size)
    }

    @Test
    fun resolvesBundledLicenseTextAssetOnlyForKnownBundledLicenses() {
        assertEquals(
            OpenSourceLicenses.APACHE_2_0_ASSET_PATH,
            OpenSourceLicenses.licenseAssetPath(OpenSourceLicenses.APACHE_2_0_ID)
        )
        assertNull(OpenSourceLicenses.licenseAssetPath("MIT"))
    }
}
