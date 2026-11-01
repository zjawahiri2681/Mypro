package com.mypro.iptvbox.core

import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.util.ChannelOrganizer
import com.mypro.iptvbox.core.util.GroupMode
import com.mypro.iptvbox.core.util.Regions
import com.mypro.iptvbox.core.util.SortMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionsTest {
    private fun ch(name: String, group: String = "", tvgId: String = "", country: String = "", language: String = "", number: Int = 0) =
        Channel(id = name + group, playlistId = "p", name = name, url = "u", group = group, tvgId = tvgId, country = country, language = language, number = number)

    @Test
    fun detectsCountryFromPrefixesIdsAndAttributes() {
        assertEquals("GB", Regions.detectCountry(ch("UK: BBC One HD"))?.code)
        assertEquals("GB", Regions.detectCountry(ch("BBC One", group = "UK | Entertainment"))?.code)
        assertEquals("SA", Regions.detectCountry(ch("MBC 1", group = "KSA - General"))?.code)
        assertEquals("ARAB", Regions.detectCountry(ch("|AR| MBC Drama"))?.code)
        assertEquals("US", Regions.detectCountry(ch("CNN", tvgId = "cnn.us"))?.code)
        assertEquals("FR", Regions.detectCountry(ch("TF1", country = "France"))?.code)
        assertEquals("DE", Regions.detectCountry(ch("Das Erste", group = "Germany"))?.code)
        assertEquals("AR", Regions.detectCountry(ch("TyC Sports", group = "Argentina"))?.code)
        assertNull(Regions.detectCountry(ch("Sky Sports Main Event", group = "Sports")))
    }

    @Test
    fun detectsLanguageFromAttributesTagsAndCountry() {
        assertEquals("Arabic", Regions.detectLanguage(ch("MBC 1", group = "KSA - General")))
        assertEquals("French", Regions.detectLanguage(ch("TF1", language = "fr")))
        assertEquals("Hindi", Regions.detectLanguage(ch("Star Plus", group = "INDIA | HINDI")))
        assertEquals("English", Regions.detectLanguage(ch("BBC One", tvgId = "bbc1.uk")))
        assertNull(Regions.detectLanguage(ch("Sky Sports", group = "Sports")))
    }

    @Test
    fun organizerGroupsAndSorts() {
        val list = listOf(
            ch("UK: BBC One", group = "UK", number = 3),
            ch("UK: ITV", group = "UK", number = 1),
            ch("KSA: MBC 1", group = "Arabic", number = 2),
            ch("Mystery", group = "", number = 4),
        )
        val org = ChannelOrganizer.of(list)
        assertEquals(listOf("🇬🇧 United Kingdom", "🌍 Arab world", "Other"), org.groups(GroupMode.COUNTRY).map { it.label })
        assertEquals(listOf(2, 1, 1), org.groups(GroupMode.COUNTRY).map { it.count })
        assertEquals(listOf("English", "Arabic", "Other"), org.groups(GroupMode.LANGUAGE).map { it.label })
        assertEquals(listOf("UK: ITV", "UK: BBC One"), org.channelsIn(GroupMode.COUNTRY, "🇬🇧 United Kingdom").map { it.name })
        assertEquals(listOf("UK: BBC One", "UK: ITV"), org.channelsIn(GroupMode.COUNTRY, "🇬🇧 United Kingdom", SortMode.NAME).map { it.name })
        assertEquals(listOf("UK", "Arabic", "Other"), org.groups(GroupMode.PROVIDER).map { it.label })
        assert(ChannelOrganizer.of(list) === org)
    }
}
