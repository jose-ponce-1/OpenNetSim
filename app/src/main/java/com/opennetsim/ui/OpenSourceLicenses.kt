package com.opennetsim.ui

import java.io.InputStream

/** Artefacto individual de terceros empaquetado en la aplicación. */
data class LicenseArtifact(
    val group: String,
    val artifact: String,
    val name: String,
    val version: String,
    val licenseId: String,
    val url: String
)

/** Agrupación legible de artefactos del mismo grupo y licencia para mostrar en la interfaz. */
data class LicenseGroup(
    val id: String,
    val title: String,
    val licenseId: String,
    val versionsSummary: String,
    val artifacts: List<LicenseArtifact>
)

/**
 * Parsea el inventario TSV generado en compilación (`licenses/libraries.tsv`) y lo agrupa por grupo
 * Maven y licencia, conservando un orden determinista.
 */
object OpenSourceLicenses {
    const val LIBRARIES_ASSET_PATH = "licenses/libraries.tsv"
    const val APACHE_2_0_ID = "Apache-2.0"
    const val APACHE_2_0_ASSET_PATH = "licenses/apache-2.0.txt"

    private const val COLUMN_COUNT = 6
    private const val COL_GROUP = 0
    private const val COL_ARTIFACT = 1
    private const val COL_NAME = 2
    private const val COL_VERSION = 3
    private const val COL_LICENSE_ID = 4
    private const val COL_URL = 5

    fun parse(input: InputStream): List<LicenseArtifact> =
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            parse(reader.readText())
        }

    fun parse(tsvContent: String): List<LicenseArtifact> =
        tsvContent.lineSequence()
            .filter { line -> line.isNotBlank() }
            .mapNotNull(::parseLine)
            .sortedWith(compareBy(LicenseArtifact::group, LicenseArtifact::artifact, LicenseArtifact::version))
            .toList()

    fun group(artifacts: List<LicenseArtifact>): List<LicenseGroup> =
        artifacts
            .groupBy { artifact -> artifact.group to artifact.licenseId }
            .entries
            .sortedWith(compareBy({ entry -> entry.key.first }, { entry -> entry.key.second }))
            .map { (key, items) ->
                val (groupName, licenseId) = key
                val sortedItems = items.sortedWith(compareBy(LicenseArtifact::artifact, LicenseArtifact::version))
                val title = if (sortedItems.size == 1) sortedItems.first().name else groupName
                val versions = sortedItems.map { item -> item.version }.distinct().joinToString(" · ")
                LicenseGroup(
                    id = "$groupName:$licenseId",
                    title = title,
                    licenseId = licenseId,
                    versionsSummary = versions,
                    artifacts = sortedItems
                )
            }

    fun licenseAssetPath(licenseId: String): String? =
        if (licenseId == APACHE_2_0_ID) APACHE_2_0_ASSET_PATH else null

    private fun parseLine(line: String): LicenseArtifact? {
        val parts = line.trimEnd('\r').split('\t')
        if (parts.size < COLUMN_COUNT) return null
        val group = parts[COL_GROUP].trim()
        val artifact = parts[COL_ARTIFACT].trim()
        val version = parts[COL_VERSION].trim()
        val licenseId = parts[COL_LICENSE_ID].trim()
        val valid = group.isNotEmpty() && artifact.isNotEmpty() && version.isNotEmpty() && licenseId.isNotEmpty()
        return if (valid) {
            LicenseArtifact(
                group = group,
                artifact = artifact,
                name = parts[COL_NAME].trim().ifEmpty { artifact },
                version = version,
                licenseId = licenseId,
                url = parts[COL_URL].trim()
            )
        } else {
            null
        }
    }
}
