package com.salatv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepTest {
    @Test fun crunchyrollSeriesBecomesItsScheme() {
        assertEquals(listOf("crunchyroll://series/GY5P48XEY"),
            Deep.candidates(Crunchy.PKG, "https://www.crunchyroll.com/series/GY5P48XEY/demon-slayer"))
    }

    @Test fun crunchyrollEpisodeWithLanguageKeepsTheEpisode() {
        assertEquals(listOf("crunchyroll://watch/G4VUQ1ZKW"),
            Deep.candidates(Crunchy.PKG, "https://www.crunchyroll.com/es/watch/G4VUQ1ZKW/cruelty"))
    }

    @Test fun crunchyrollUnknownLinkIsLeftAlone() {
        assertEquals(listOf("https://www.crunchyroll.com/videos/popular"), Deep.candidates(Crunchy.PKG, "https://www.crunchyroll.com/videos/popular"))
    }

    @Test fun disneyTriesItsSchemeFirst() {
        val l = Deep.candidates("com.disney.disneyplus", "https://www.disneyplus.com/browse/entity-123")
        assertEquals("disneyplus://www.disneyplus.com/browse/entity-123", l.first())
        assertTrue(l.last().startsWith("https://"))
    }

    @Test fun netflixAndPrimeTakeTheWebAddress() {
        assertEquals(listOf("https://www.netflix.com/title/80057281"), Deep.candidates("com.netflix.ninja", "https://www.netflix.com/title/80057281"))
        assertEquals(listOf("https://app.primevideo.com/detail?gti=amzn1.dv.gti.1"), Deep.candidates("com.amazon.firebat", "https://app.primevideo.com/detail?gti=amzn1.dv.gti.1"))
    }

    @Test fun searchIgnoresAccents() {
        assertEquals("cancion animacion", Search.fold("Canción Animación"))
    }
}
