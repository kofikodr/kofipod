// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.search

/**
 * Apple iTunes Search API storefront (country code). The API's `country=` parameter
 * filters by which national Apple Podcasts catalogue to search — `country=US` will
 * not surface a UK-only show, and vice versa.
 *
 * We deliberately don't auto-detect the device locale: locale detection is
 * platform-specific (Compose Multiplatform has no first-class API), and storefront
 * mistakes are user-visible (the user notices "their" shows aren't found). An
 * explicit picker is a one-time choice in Settings with no ambiguity.
 *
 * The list below covers the storefronts our users overwhelmingly come from. It is
 * NOT every Apple storefront — adding one is two lines, but every entry inflates
 * the Settings picker and most of them have tiny catalogs. Add on demand.
 */
enum class ItunesStorefront(
    /** ISO 3166-1 alpha-2 country code passed verbatim to iTunes' `country=` param. */
    val iso2: String,
    /** Display label shown in the Settings picker row. */
    val label: String,
) {
    UnitedStates("US", "United States"),
    UnitedKingdom("GB", "United Kingdom"),
    Canada("CA", "Canada"),
    Australia("AU", "Australia"),
    Germany("DE", "Germany"),
    France("FR", "France"),
    Netherlands("NL", "Netherlands"),
    Sweden("SE", "Sweden"),
    Italy("IT", "Italy"),
    Spain("ES", "Spain"),
    Japan("JP", "Japan"),
    Brazil("BR", "Brazil"),
    India("IN", "India"),
    SouthAfrica("ZA", "South Africa"),
    Mexico("MX", "Mexico"),
    ;

    companion object {
        /**
         * Default storefront when the user hasn't picked one. US is the broadest
         * catalogue and English-biased — a reasonable starting point that the user
         * can change in one tap.
         */
        val Default: ItunesStorefront = UnitedStates

        fun fromIso2OrDefault(iso2: String?): ItunesStorefront =
            iso2?.let { code -> entries.firstOrNull { it.iso2.equals(code, ignoreCase = true) } }
                ?: Default
    }
}
