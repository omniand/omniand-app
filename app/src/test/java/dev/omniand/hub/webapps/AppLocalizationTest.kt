package dev.omniand.hub.webapps

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppLocalizationTest {
    @Test
    fun selectsLocalizedNameFromWeightedLanguageRanges() {
        val names = mapOf("fr" to "Galerie", "fr-ca" to "Galerie canadienne")

        assertEquals(
            "Galerie canadienne",
            AppLocalization.select("Gallery", names, "fr-CA,fr;q=0.9,en;q=0.8"),
        )
        assertEquals("Galerie", AppLocalization.select("Gallery", names, "fr-FR,fr;q=0.9"))
        assertEquals("Gallery", AppLocalization.select("Gallery", names, "en-US,en;q=0.9"))
        assertEquals("Gallery", AppLocalization.select("Gallery", names, "malformed;;;"))
    }

    @Test
    fun parsesBoundedManifestLocalesAndRejectsInvalidTags() {
        val manifest = JSONObject("""{"locales":{"fr":{"name":" Galerie "},"en":{}}}""")
        assertEquals(mapOf("fr" to "Galerie"), AppLocalization.strings(manifest, "name", 80))

        assertThrows(IllegalStateException::class.java) {
            AppLocalization.strings(
                JSONObject("""{"locales":{"../fr":{"name":"Galerie"}}}"""),
                "name",
                80,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            AppLocalization.strings(
                JSONObject("""{"locales":{"fr":"Galerie"}}"""),
                "name",
                80,
            )
        }
    }
}
