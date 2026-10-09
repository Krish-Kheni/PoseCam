package com.posecam.core.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthStoreTest {
    private lateinit var context: Context
    private var now = 1_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("posecam_auth", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = AuthStore(context) { now }
    private fun session(expires: Long = 10_000) = AuthSession("a@b.co", "Asha", "tok", expires)

    @Test fun startsSignedOutAndNeverSignedIn() {
        val state = store().current()
        assertFalse(state.isSignedIn)
        assertFalse(state.hasEverSignedIn)
    }

    @Test fun signInSurvivesARestart() {
        store().signIn(session())

        val again = store()
        assertTrue(again.isSignedIn)
        assertEquals("tok", again.token())
        assertEquals("a@b.co", again.current().session!!.email)
    }

    @Test fun anExpiredTokenIsNeverSentButTheEmailIsKept() {
        val store = store()
        store.signIn(session(expires = 5_000))

        now = 5_000

        assertNull(store.token())
        assertFalse(store.isSignedIn)
        assertEquals("a@b.co", store.current().lastEmail)
        assertTrue(store.current().hasEverSignedIn) // recording does not go back to a sign-in wall
    }

    @Test fun signOutForgetsTheTokenOnly() {
        val store = store()
        store.signIn(session())

        store.signOut()

        assertNull(store.token())
        assertEquals("a@b.co", store.current().lastEmail)
        assertNull(store().token()) // and it stays gone after a restart
    }

    @Test fun theProviderSignsOutWhenTheServerRefusesTheToken() = runBlocking {
        val store = store()
        store.signIn(session())
        val provider = UserAuthProvider(store)
        assertEquals("tok", provider.getToken())

        provider.onUnauthorized()

        assertNull(provider.getToken())
    }

    @Test fun aDaysListIsKeptPerAccountAndOldDaysAreDropped() {
        val cache = UploadStatsCache(context)
        val one = listOf(UploadedRecording("capture-20261009T100000-aaaaaa", "white", "White pipes", "x", "2026-10-09", "live"))
        cache.saveDay("a@b.co", "2026-10-09", one)

        assertEquals(one, cache.loadDay("a@b.co", "2026-10-09"))
        assertNull(cache.loadDay("someone@else.com", "2026-10-09"))
        assertNull(cache.loadDay("a@b.co", "2026-10-08"))

        // Forty more days: only the 30 most recent stay.
        for (i in 1..40) cache.saveDay("a@b.co", "2026-08-%02d".format(i.coerceAtMost(31)).let { if (i > 31) "2026-07-%02d".format(i - 31) else it }, one)
        assertEquals(one, cache.loadDay("a@b.co", "2026-10-09"))
        assertNull(cache.loadDay("a@b.co", "2026-07-01"))
    }

    @Test fun statsAreOnlyShownToTheirOwner() {
        val cache = UploadStatsCache(context)
        val stats = UploadStats("a@b.co", 12, 3, "2026-09-01T05:00:00.000Z", listOf(DayCount("2026-10-09", 3, mapOf("white" to 3), live = 3), DayCount("2026-10-08", 9, mapOf("white" to 5, "pink" to 4), live = 8, failed = 1)),
            )
        cache.save(stats, 42)

        assertEquals(stats, cache.load("a@b.co")!!.stats)
        assertEquals(42L, cache.load("a@b.co")!!.savedAtMs)
        assertNull(cache.load("someone@else.com"))
    }
}
