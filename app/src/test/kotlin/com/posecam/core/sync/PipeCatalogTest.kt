package com.posecam.core.sync

import com.posecam.core.cloud.CloudNetworkException
import com.posecam.core.cloud.PipeInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PipeCatalogTest {
    private class MemoryStore(var json: String? = null) : PipeStore {
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private val api = FakeCloudApi()
    private val store = MemoryStore()
    private val catalog = PipeCatalog(api, store)

    private val five = listOf(
        PipeInfo("white", "White pipes", "#f8fafc", 1),
        PipeInfo("black", "Black pipes", "#111827", 2),
        PipeInfo("black-white", "Black/White pipes", "#9ca3af", 3),
        PipeInfo("pink", "Pink Pipes", "#ec4899", 4),
        PipeInfo("green", "Green Pipes", "#22c55e", 5),
    )

    @Test fun beforeTheFirstFetchTheThreeBuiltInPipesAreOffered() {
        assertEquals(listOf("white", "black", "black-white"), catalog.current().map { it.wire })
    }

    @Test fun aFetchedListReplacesTheDefaultsAndSurvivesARestart() = runBlocking {
        api.pipesOnServer = five

        assertTrue(catalog.refresh())

        val expected = listOf("white", "black", "black-white", "pink", "green")
        assertEquals(expected, catalog.current().map { it.wire })
        // A new catalog over the same store is the next app launch, with no network.
        assertEquals(expected, PipeCatalog(FakeCloudApi(), store).current().map { it.wire })
        assertEquals("Pink Pipes", catalog.current()[3].label)
        assertEquals(0xFFEC4899.toInt(), catalog.current()[3].color)
    }

    @Test fun theBackendsOrderWinsOverTheOrderItWasSentIn() = runBlocking {
        api.pipesOnServer = five.reversed()

        catalog.refresh()

        assertEquals(listOf("white", "black", "black-white", "pink", "green"), catalog.current().map { it.wire })
    }

    @Test fun aFailedFetchKeepsWhatWasCached() = runBlocking {
        api.pipesOnServer = five
        catalog.refresh()
        api.failOnce("listPipes", CloudNetworkException(IOException("offline")))

        assertFalse(catalog.refresh())

        assertEquals(5, catalog.current().size)
    }

    @Test fun anEmptyAnswerNeverLeavesTheCollectorWithoutPipes() = runBlocking {
        api.pipesOnServer = emptyList()

        assertFalse(catalog.refresh())

        assertEquals(Pipe.DEFAULTS, catalog.current())
        assertNull(store.json)
    }

    @Test fun anUnreadableCacheFallsBackToTheDefaults() {
        store.json = "this is not json"
        assertEquals(Pipe.DEFAULTS, catalog.current())

        store.json = "[]"
        assertEquals(Pipe.DEFAULTS, catalog.current())
    }

    @Test fun entriesThatCouldNotBeAFolderNameAreDropped() = runBlocking {
        api.pipesOnServer = listOf(
            PipeInfo("pink", "Pink Pipes", "#ec4899", 1),
            PipeInfo("Pink", "Capitalised", null, 2),
            PipeInfo("../x", "Traversal", null, 3),
            PipeInfo("pink-pipe-", "Trailing hyphen", null, 4),
            PipeInfo("blue", "  ", null, 5),
            PipeInfo("pink", "Duplicate", null, 6),
        )

        catalog.refresh()

        assertEquals(listOf("pink"), catalog.current().map { it.wire })
        assertEquals("Pink Pipes", catalog.current().single().label)
    }

    @Test fun colourIsReadFromRrggbbOnly() {
        assertEquals(0xFFEC4899.toInt(), PipeCatalog.parseColor("#ec4899"))
        assertNull(PipeCatalog.parseColor("ec4899"))
        assertNull(PipeCatalog.parseColor("#ec489"))
        assertNull(PipeCatalog.parseColor("#zzzzzz"))
        assertNull(PipeCatalog.parseColor(null))
    }

    @Test fun pipesAreTheSameWhenTheirIdsMatch() {
        assertEquals(Pipe.WHITE, Pipe("white", "White pipes"))
        assertFalse(Pipe.WHITE == Pipe.BLACK)
    }
}
