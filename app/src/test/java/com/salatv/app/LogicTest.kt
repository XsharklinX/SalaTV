package com.salatv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SunTest {
    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { set(y, m - 1, d, 12, 0) }

    @Test fun bogotaSetsAroundQuarterToSix() {
        val t = Sun.times(Cities.ALL.first { it.id == "bog" }, day(2026, 10, 21))!!
        assertTrue("amanecer ${Sun.fmt(t.first)}", t.first in (5 * 60 + 30)..(5 * 60 + 55))
        assertTrue("atardecer ${Sun.fmt(t.second)}", t.second in (17 * 60 + 40)..(18 * 60 + 5))
    }

    /** Late October in Madrid, still on summer time: the sun sets around 19:27. */
    @Test fun madridInSummerTimeSetsHalfPastSeven() {
        val t = Sun.times(Cities.ALL.first { it.id == "mad" }, day(2026, 10, 21))!!
        assertTrue("atardecer ${Sun.fmt(t.second)}", t.second in (19 * 60 + 15)..(19 * 60 + 40))
    }

    @Test fun formatsMinutes() {
        assertEquals("07:05", Sun.fmt(7 * 60 + 5))
        assertEquals("00:30", Sun.fmt(24 * 60 + 30))
    }
}

class DockTest {
    private val visible = listOf("crunchy", "disney", "hydra", "netflix", "prime", "smart", "stremio")

    @Test fun nightUsagePutsPinnedFavouriteFirst() {
        val ranks = mapOf("hydra" to 9f, "netflix" to 2f, "crunchy" to 5f)
        val d = Logic.dock(listOf("netflix", "hydra", "crunchy"), visible, ranks, auto = true)
        assertEquals(listOf("hydra", "crunchy", "netflix"), d)
    }

    @Test fun freePlacesGoToMostUsedOfTheMoment() {
        val d = Logic.dock(listOf("netflix"), visible, mapOf("stremio" to 3f, "prime" to 0.2f), auto = true)
        assertEquals(listOf("netflix", "stremio"), d)
    }

    @Test fun fixedDockKeepsOrderAndSkipsHidden() {
        val d = Logic.dock(listOf("smart", "gone", "hydra"), visible, mapOf("hydra" to 9f), auto = false)
        assertEquals(listOf("smart", "hydra"), d)
    }

    @Test fun neverMoreThanSix() {
        val d = Logic.dock(emptyList(), visible, visible.associateWith { 1f }, auto = true)
        assertEquals(6, d.size)
    }

    @Test fun sortsTheGrid() {
        val alpha = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), Logic.sort(alpha, "used", mapOf("c" to 9, "a" to 2), emptyMap()) { "App" })
        assertEquals(listOf("b", "a", "c"), Logic.sort(alpha, "recent", emptyMap(), mapOf("b" to 50L, "a" to 10L)) { "App" })
        assertEquals(listOf("b", "a", "c"), Logic.sort(alpha, "type", emptyMap(), emptyMap()) { if (it == "b") "Vídeo" else "App" })
    }

    @Test fun doubleHomeWindow() {
        assertTrue(Logic.isDoubleHome(900, salaShown = true))
        assertTrue(Logic.isDoubleHome(2600, salaShown = true))
        assertFalse(Logic.isDoubleHome(100, salaShown = true))
        assertFalse(Logic.isDoubleHome(900, salaShown = false))
        assertFalse(Logic.isDoubleHome(4000, salaShown = true))
    }
}

class LinksTest {
    @Test fun smartTubeGoesToTheVideoAndSecond() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=95s", LinkRules.url("org.smarttube.stable", "dQw4w9WgXcQ", null, 95))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", LinkRules.url("org.smarttube.stable", null, "https://youtu.be/dQw4w9WgXcQ", 2))
    }

    @Test fun netflixNeedsANumericId() {
        assertEquals("https://www.netflix.com/watch/81234567", LinkRules.url("com.netflix.ninja", "81234567", null, 0))
        assertNull(LinkRules.url("com.netflix.ninja", "abc", null, 0))
    }

    @Test fun localFilesAreNotLinks() {
        assertNull(LinkRules.url("org.videolan.vlc", null, "content://media/1", 0))
        assertEquals("stremio:///detail/series/tt1", LinkRules.url("com.stremio.one", null, "stremio:///detail/series/tt1", 0))
    }
}

class VersionsTest {
    @Test fun comparesTagsAndNames() {
        assertTrue(Versions.newer("32.70s", "32.68"))
        assertTrue(Versions.newer("v2.1.7", "2.1.6"))
        assertFalse(Versions.newer("v2.1.6", "2.1.6-generic"))
        assertFalse(Versions.newer("21.3", "21.10"))
        assertFalse(Versions.newer(null, "1.0"))
    }

    @Test fun picksTheDownloadForThisProcessor() {
        val smart = listOf("SmartTube_stable_32.68_arm64-v8a.apk", "SmartTube_stable_32.68_armeabi-v7a.apk", "SmartTube_stable_32.68_universal.apk")
        assertEquals("SmartTube_stable_32.68_armeabi-v7a.apk", Versions.pickAsset(smart, "armeabi-v7a"))
        assertEquals("SmartTube_stable_32.68_universal.apk", Versions.pickAsset(smart, "x86_64"))
        val bro = listOf("tvbro-2.1.6-generic-geckoExcluded.apk", "tvbro-2.1.6-generic-geckoIncluded-armeabi-v7a.apk")
        assertEquals("tvbro-2.1.6-generic-geckoExcluded.apk", Versions.pickAsset(bro, "armeabi-v7a", prefer = "geckoExcluded"))
    }
}

class CleanerTest {
    @Test fun protectedPackagesAreNeverTouched() {
        assertTrue(Cleaner.isProtected("com.amazon.tv.launcher"))
        assertTrue(Cleaner.isProtected("com.amazon.tv.settings.v2"))
        assertTrue(Cleaner.isProtected("com.android.systemui"))
        assertTrue(Cleaner.isProtected("com.salatv.app"))
        assertFalse(Cleaner.isProtected("com.amazon.kso.blackbird"))
    }

    @Test fun pinHashIsStableAndSalted() {
        assertEquals(Pin.hash("1234"), Pin.hash("1234"))
        assertFalse(Pin.hash("1234") == Pin.hash("1235"))
        assertEquals(64, Pin.hash("0000").length)
    }

    @Test fun agoReadsLikeSpanish() {
        val now = 1_760_000_000_000L
        assertEquals("hace 20 min", UsageRank.ago(now - 20 * 60_000L, now))
        assertEquals("ahora", UsageRank.ago(now - 10_000L, now))
    }
}
