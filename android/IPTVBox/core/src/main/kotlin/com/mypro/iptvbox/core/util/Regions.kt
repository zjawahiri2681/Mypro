package com.mypro.iptvbox.core.util

import com.mypro.iptvbox.core.model.Channel

/**
 * Detects the country and language of a channel from playlist metadata
 * (tvg-country / tvg-language), the XMLTV id suffix ("bbc1.uk") and the
 * prefixes providers put in channel and group names ("UK: BBC One", "|AR| MBC", "FR - TF1").
 */
object Regions {
    data class Country(val code: String, val name: String, val flag: String, val language: String, val aliases: List<String>)

    private fun c(code: String, name: String, flag: String, language: String, vararg aliases: String) =
        Country(code, name, flag, language, (listOf(code.lowercase(), name.lowercase()) + aliases.map { it.lowercase() }).distinct())

    val countries: List<Country> = listOf(
        c("GB", "United Kingdom", "🇬🇧", "English", "uk", "gbr", "britain", "england", "british"),
        c("US", "United States", "🇺🇸", "English", "usa", "america", "united states of america"),
        c("CA", "Canada", "🇨🇦", "English", "can"),
        c("AU", "Australia", "🇦🇺", "English", "aus"),
        c("NZ", "New Zealand", "🇳🇿", "English", "nzl"),
        c("IE", "Ireland", "🇮🇪", "English", "irl"),
        c("SA", "Saudi Arabia", "🇸🇦", "Arabic", "ksa", "sau", "saudi"),
        c("AE", "United Arab Emirates", "🇦🇪", "Arabic", "uae", "emirates", "dubai", "abu dhabi"),
        c("QA", "Qatar", "🇶🇦", "Arabic", "qat"),
        c("KW", "Kuwait", "🇰🇼", "Arabic", "kwt"),
        c("BH", "Bahrain", "🇧🇭", "Arabic", "bhr"),
        c("OM", "Oman", "🇴🇲", "Arabic", "omn"),
        c("YE", "Yemen", "🇾🇪", "Arabic", "yem"),
        c("IQ", "Iraq", "🇮🇶", "Arabic", "irq"),
        c("SY", "Syria", "🇸🇾", "Arabic", "syr"),
        c("LB", "Lebanon", "🇱🇧", "Arabic", "lbn"),
        c("JO", "Jordan", "🇯🇴", "Arabic", "jor"),
        c("PS", "Palestine", "🇵🇸", "Arabic", "pse"),
        c("EG", "Egypt", "🇪🇬", "Arabic", "egy"),
        c("LY", "Libya", "🇱🇾", "Arabic", "lby"),
        c("TN", "Tunisia", "🇹🇳", "Arabic", "tun"),
        c("DZ", "Algeria", "🇩🇿", "Arabic", "dza"),
        c("MA", "Morocco", "🇲🇦", "Arabic", "mar", "maroc"),
        c("SD", "Sudan", "🇸🇩", "Arabic", "sdn"),
        c("SO", "Somalia", "🇸🇴", "Somali", "som"),
        c("TR", "Turkey", "🇹🇷", "Turkish", "tur", "türkiye", "turkiye"),
        c("IR", "Iran", "🇮🇷", "Persian", "irn"),
        c("AF", "Afghanistan", "🇦🇫", "Pashto", "afg"),
        c("PK", "Pakistan", "🇵🇰", "Urdu", "pak"),
        c("IN", "India", "🇮🇳", "Hindi", "ind"),
        c("BD", "Bangladesh", "🇧🇩", "Bengali", "bgd", "bangla"),
        c("LK", "Sri Lanka", "🇱🇰", "Sinhala", "lka"),
        c("NP", "Nepal", "🇳🇵", "Nepali", "npl"),
        c("DE", "Germany", "🇩🇪", "German", "deu", "ger", "deutschland"),
        c("AT", "Austria", "🇦🇹", "German", "aut"),
        c("CH", "Switzerland", "🇨🇭", "German", "che", "swiss"),
        c("FR", "France", "🇫🇷", "French", "fra"),
        c("BE", "Belgium", "🇧🇪", "French", "bel"),
        c("NL", "Netherlands", "🇳🇱", "Dutch", "nld", "holland"),
        c("ES", "Spain", "🇪🇸", "Spanish", "esp", "españa"),
        c("PT", "Portugal", "🇵🇹", "Portuguese", "prt"),
        c("IT", "Italy", "🇮🇹", "Italian", "ita", "italia"),
        c("GR", "Greece", "🇬🇷", "Greek", "grc"),
        c("PL", "Poland", "🇵🇱", "Polish", "pol", "polska"),
        c("CZ", "Czechia", "🇨🇿", "Czech", "cze", "czech republic"),
        c("SK", "Slovakia", "🇸🇰", "Slovak", "svk"),
        c("HU", "Hungary", "🇭🇺", "Hungarian", "hun"),
        c("RO", "Romania", "🇷🇴", "Romanian", "rou"),
        c("BG", "Bulgaria", "🇧🇬", "Bulgarian", "bgr"),
        c("RS", "Serbia", "🇷🇸", "Serbian", "srb"),
        c("HR", "Croatia", "🇭🇷", "Croatian", "hrv"),
        c("BA", "Bosnia and Herzegovina", "🇧🇦", "Bosnian", "bih", "bosnia"),
        c("SI", "Slovenia", "🇸🇮", "Slovenian", "svn"),
        c("MK", "North Macedonia", "🇲🇰", "Macedonian", "mkd", "macedonia"),
        c("AL", "Albania", "🇦🇱", "Albanian", "alb", "shqip"),
        c("SE", "Sweden", "🇸🇪", "Swedish", "swe"),
        c("NO", "Norway", "🇳🇴", "Norwegian", "nor"),
        c("DK", "Denmark", "🇩🇰", "Danish", "dnk"),
        c("FI", "Finland", "🇫🇮", "Finnish", "fin"),
        c("IS", "Iceland", "🇮🇸", "Icelandic", "isl"),
        c("RU", "Russia", "🇷🇺", "Russian", "rus"),
        c("UA", "Ukraine", "🇺🇦", "Ukrainian", "ukr"),
        c("BY", "Belarus", "🇧🇾", "Russian", "blr"),
        c("KZ", "Kazakhstan", "🇰🇿", "Kazakh", "kaz"),
        c("AZ", "Azerbaijan", "🇦🇿", "Azerbaijani", "aze"),
        c("GE", "Georgia", "🇬🇪", "Georgian", "geo"),
        c("AM", "Armenia", "🇦🇲", "Armenian", "arm"),
        c("IL", "Israel", "🇮🇱", "Hebrew", "isr"),
        c("CN", "China", "🇨🇳", "Chinese", "chn"),
        c("HK", "Hong Kong", "🇭🇰", "Chinese", "hkg"),
        c("TW", "Taiwan", "🇹🇼", "Chinese", "twn"),
        c("JP", "Japan", "🇯🇵", "Japanese", "jpn"),
        c("KR", "South Korea", "🇰🇷", "Korean", "kor", "korea"),
        c("PH", "Philippines", "🇵🇭", "Filipino", "phl", "pinoy"),
        c("ID", "Indonesia", "🇮🇩", "Indonesian", "idn"),
        c("MY", "Malaysia", "🇲🇾", "Malay", "mys"),
        c("SG", "Singapore", "🇸🇬", "English", "sgp"),
        c("TH", "Thailand", "🇹🇭", "Thai", "tha"),
        c("VN", "Vietnam", "🇻🇳", "Vietnamese", "vnm"),
        c("NG", "Nigeria", "🇳🇬", "English", "nga"),
        c("GH", "Ghana", "🇬🇭", "English", "gha"),
        c("KE", "Kenya", "🇰🇪", "Swahili", "ken"),
        c("ET", "Ethiopia", "🇪🇹", "Amharic", "eth"),
        c("ZA", "South Africa", "🇿🇦", "English", "zaf"),
        c("BR", "Brazil", "🇧🇷", "Portuguese", "bra", "brasil"),
        c("MX", "Mexico", "🇲🇽", "Spanish", "mex"),
        c("AR", "Argentina", "🇦🇷", "Spanish", "arg"),
        c("CO", "Colombia", "🇨🇴", "Spanish", "col"),
        c("CL", "Chile", "🇨🇱", "Spanish", "chl"),
        c("PE", "Peru", "🇵🇪", "Spanish", "per"),
        c("VE", "Venezuela", "🇻🇪", "Spanish", "ven"),
        c("EC", "Ecuador", "🇪🇨", "Spanish", "ecu"),
        c("CU", "Cuba", "🇨🇺", "Spanish", "cub"),
        c("DO", "Dominican Republic", "🇩🇴", "Spanish", "dom"),
        c("LATAM", "Latin America", "🌎", "Spanish", "latino", "latin", "latam"),
        c("ARAB", "Arab world", "🌍", "Arabic", "arab", "arabic", "arabia", "mena", "gulf", "khaleej"),
        c("INTL", "International", "🌐", "English", "int", "international", "world", "global"),
    )

    private val countryIndex: Map<String, Country> = buildMap {
        for (co in countries) for (a in co.aliases) putIfAbsent(a, co)
        // Two-letter "ar" means Arabic far more often than Argentina in IPTV lists.
        put("ar", countries.first { it.code == "ARAB" })
    }
    private val byCode: Map<String, Country> = countries.associateBy { it.code }

    private val languageAliases: Map<String, String> = buildMap {
        fun l(name: String, vararg aliases: String) { put(name.lowercase(), name); for (a in aliases) put(a.lowercase(), name) }
        l("English", "en", "eng", "ingles")
        l("Arabic", "ar", "ara", "arab", "عربي", "عربية")
        l("French", "fr", "fra", "fre", "français", "francais")
        l("Spanish", "es", "spa", "español", "espanol", "castellano")
        l("German", "de", "deu", "ger", "deutsch")
        l("Italian", "it", "ita", "italiano")
        l("Portuguese", "pt", "por", "português", "portugues")
        l("Turkish", "tr", "tur", "türkçe", "turkce")
        l("Hindi", "hi", "hin")
        l("Urdu", "ur", "urd")
        l("Bengali", "bn", "ben", "bangla")
        l("Tamil", "ta", "tam")
        l("Telugu", "te", "tel")
        l("Malayalam", "ml", "mal")
        l("Kannada", "kn", "kan")
        l("Punjabi", "pa", "pan")
        l("Marathi", "mr")
        l("Gujarati", "gu")
        l("Persian", "fa", "fas", "per", "farsi")
        l("Pashto", "ps", "pus")
        l("Kurdish", "ku", "kur", "kurdi")
        l("Russian", "ru", "rus")
        l("Ukrainian", "uk-ua", "ukr")
        l("Polish", "pl", "pol", "polski")
        l("Dutch", "nl", "nld", "dut", "nederlands")
        l("Greek", "el", "gre", "ell")
        l("Romanian", "ro", "ron", "rum")
        l("Bulgarian", "bg", "bul")
        l("Serbian", "sr", "srp")
        l("Croatian", "hr", "hrv")
        l("Bosnian", "bs", "bos")
        l("Albanian", "sq", "alb", "sqi", "shqip")
        l("Macedonian", "mk-lang", "mkd")
        l("Hungarian", "hu", "hun", "magyar")
        l("Czech", "cs", "ces", "cze")
        l("Slovak", "sk-lang", "slk")
        l("Swedish", "sv", "swe", "svenska")
        l("Norwegian", "nb", "nor", "norsk")
        l("Danish", "da", "dan", "dansk")
        l("Finnish", "fi-lang", "fin", "suomi")
        l("Hebrew", "he", "heb", "ivrit")
        l("Chinese", "zh", "zho", "chi", "mandarin", "cantonese")
        l("Japanese", "ja", "jpn")
        l("Korean", "ko", "kor")
        l("Thai", "th-lang", "tha")
        l("Vietnamese", "vi", "vie")
        l("Indonesian", "id-lang", "ind", "bahasa")
        l("Malay", "ms", "msa", "may")
        l("Filipino", "tl", "fil", "tagalog", "pinoy")
        l("Somali", "so-lang", "som")
        l("Amharic", "am", "amh")
        l("Swahili", "sw", "swa")
        l("Hausa", "ha", "hau")
    }

    private val sep = Regex("""[|/\\:\-–—,()\[\]{}]+""")

    fun countryByCode(code: String): Country? = byCode[code.uppercase()]

    fun lookupCountry(token: String): Country? = countryIndex[token.trim().lowercase()]

    fun lookupLanguage(token: String): String? = languageAliases[token.trim().lowercase()]

    /** Tokens worth testing, in priority order: leading prefixes first, then every word. */
    private fun tokens(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val parts = text.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        // the first segment is usually the region tag ("UK", "|AR|", "USA")
        parts.firstOrNull()?.let { out += it }
        for (p in parts) {
            val words = p.split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (words.size <= 3) out += p
            out += words
        }
        return out.distinct()
    }

    fun detectCountry(ch: Channel): Country? {
        if (ch.country.isNotBlank()) {
            lookupCountry(ch.country)?.let { return it }
        }
        // XMLTV ids often end in a country code: "bbc1.uk", "cnn.us"
        val suffix = ch.tvgId.substringAfterLast('.', "")
        if (suffix.length in 2..3 && ch.tvgId.contains('.')) {
            lookupCountry(suffix)?.takeIf { it.code != "ARAB" || suffix.lowercase() != "ar" }?.let { return it }
        }
        for (t in tokens(ch.group)) lookupCountry(t)?.let { return it }
        for (t in tokens(ch.name).take(3)) {
            // in names, only accept clear tags (upper-case short codes or full names) to avoid "Sky" -> nothing etc.
            if (t.length <= 3 && t != t.uppercase()) continue
            lookupCountry(t)?.let { return it }
        }
        return null
    }

    fun detectLanguage(ch: Channel, country: Country? = detectCountry(ch)): String? {
        if (ch.language.isNotBlank()) lookupLanguage(ch.language)?.let { return it } ?: return ch.language.trim().replaceFirstChar { it.uppercase() }
        for (t in tokens(ch.group)) if (t.length > 3 || t == t.uppercase()) lookupLanguage(t)?.let { return it }
        for (t in tokens(ch.name).take(3)) if (t.length > 3) lookupLanguage(t)?.let { return it }
        return country?.language
    }
}
