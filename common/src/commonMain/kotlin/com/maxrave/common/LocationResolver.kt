package com.maxrave.common

/**
 * Resolves a supported YouTube Music content-country (gl) code from the device's locale.
 *
 * The setting the user picks in Settings is one of [SUPPORTED_LOCATION.items]; this resolver
 * bridges between device locale data and that list.
 */
object LocationResolver {
    /** Map from a language code to its most common home country, for devices whose country is unsupported. */
    private val languageDefaultLocation: Map<String, String> =
        mapOf(
            "en" to "US",
            "vi" to "VN",
            "it" to "IT",
            "de" to "DE",
            "ru" to "RU",
            "tr" to "TR",
            "fi" to "FI",
            "pl" to "PL",
            "pt" to "BR",
            "fr" to "FR",
            "es" to "ES",
            "zh" to "TW",
            "id" to "ID",
            "ar" to "SA",
            "ja" to "JP",
            "uk" to "UA",
            "he" to "IL",
            "az" to "AZ",
            "hi" to "IN",
            "th" to "TH",
            "nl" to "NL",
            "ko" to "KR",
            "ca" to "ES",
            "fa" to "AE",
            "bg" to "BG",
            "hu" to "HU",
            "ro" to "RO",
            "cs" to "CZ",
            "sv" to "SE",
            "da" to "DK",
            "no" to "NO",
            "el" to "GR",
            "hr" to "HR",
            "sk" to "SK",
            "lt" to "LT",
            "lv" to "LV",
            "et" to "EE",
            "sl" to "SI",
            "ms" to "MY",
            "fil" to "PH",
            "bn" to "BD",
            "ne" to "NP",
            "ur" to "PK",
            "ta" to "IN",
            "te" to "IN",
            "ml" to "IN",
            "gu" to "IN",
            "mr" to "IN",
            "sw" to "KE",
            "af" to "ZA",
        )

    private fun isSupported(code: String): Boolean = SUPPORTED_LOCATION.items.any { it == code }

    /**
     * Normalizes a user-supplied location value to a supported ISO-3166 alpha-2 code,
     * or null when it cannot be normalized (the caller then keeps the raw value).
     */
    fun normalizeCountryCode(countryCode: String): String? {
        val normalized = countryCode.trim().uppercase()
        if (normalized.length != 2 || !isSupported(normalized)) {
            return null
        }
        return normalized
    }

    /**
     * Best-effort default content country: the device country when supported, otherwise the
     * region embedded in the device language tag, otherwise the language's home country,
     * otherwise US.
     */
    fun resolveDefaultLocation(
        country: String?,
        language: String?,
    ): String {
        val deviceCountry = country?.trim()?.uppercase()
        if (deviceCountry != null && deviceCountry.length == 2 && isSupported(deviceCountry)) {
            return deviceCountry
        }
        val languageRegion = language?.substringAfter('-', "")?.trim()?.uppercase()
        if (languageRegion != null && languageRegion.length == 2 && isSupported(languageRegion)) {
            return languageRegion
        }
        val languageCode = language?.substringBefore('-')?.lowercase()
        val languageCountry = languageCode?.let { languageDefaultLocation[it] }
        if (languageCountry != null && isSupported(languageCountry)) {
            return languageCountry
        }
        return DEFAULT_LOCATION
    }
}

const val DEFAULT_LOCATION = "US"

/** The device's country code (ISO-3166 alpha-2), or null when unknown. */
expect fun getDeviceCountry(): String?

/** The device's primary language code (ISO-639-1), or null when unknown. */
expect fun getDeviceLanguage(): String?
