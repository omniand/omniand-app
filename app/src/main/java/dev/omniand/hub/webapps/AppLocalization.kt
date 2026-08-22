package dev.omniand.hub.webapps

import java.util.Locale
import org.json.JSONObject

/** Validates bounded manifest translations and resolves them using HTTP/Android language tags. */
object AppLocalization {
    private const val MAX_LOCALES = 20
    private val validLanguageTag = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")

    fun strings(document: JSONObject, member: String, maxLength: Int): Map<String, String> {
        val locales = document.optJSONObject("locales") ?: return emptyMap()
        check(locales.length() <= MAX_LOCALES) { "Too many application locales" }
        return buildMap {
            locales.keys().forEach { rawTag ->
                check(validLanguageTag.matches(rawTag)) { "Invalid application locale" }
                val localized = locales.opt(rawTag)
                check(localized is JSONObject) { "Invalid application locale metadata" }
                val rawValue = localized.opt(member)
                check(rawValue == null || rawValue is String) {
                    "Invalid localized application $member"
                }
                val value = (rawValue as? String)?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    check(value.length <= maxLength) { "Invalid localized application $member" }
                    val tag = Locale.forLanguageTag(rawTag).toLanguageTag().lowercase()
                    check(tag !in this) { "Duplicate application locale" }
                    put(tag, value)
                }
            }
        }
    }

    fun select(fallback: String, localized: Map<String, String>, languageTags: String?): String {
        if (localized.isEmpty() || languageTags.isNullOrBlank()) return fallback
        val ranges =
            runCatching { Locale.LanguageRange.parse(languageTags) }.getOrNull() ?: return fallback
        val locales = localized.keys.map(Locale::forLanguageTag)
        val selected = Locale.lookup(ranges, locales)?.toLanguageTag()?.lowercase()
        return selected?.let(localized::get) ?: fallback
    }
}
