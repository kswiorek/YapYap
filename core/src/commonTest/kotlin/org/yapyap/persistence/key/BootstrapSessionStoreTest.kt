package org.yapyap.persistence.key

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class BootstrapSessionStoreTest {

    private fun store() = BootstrapSessionStore(InMemoryKeyStore())

    @Test
    fun emptyStore_readsAsNoSession_gateClosed() = runTest {
        val store = store()

        assertNull(store.session())
        assertNull(store.introKey())
    }

    @Test
    fun setActiveSecret_roundTripsSecretAndDeadline() = runTest {
        val store = store()
        val secret = ByteArray(32) { it.toByte() }
        val deadline = epochSeconds(10_000L) + 5.minutes

        store.setActiveSecret(secret, deadline)

        val session = store.session()
        assertContentEquals(secret, session?.secret)
        assertEquals(deadline, session?.deadline)
        assertContentEquals(secret, store.introKey())
    }

    @Test
    fun extendDeadline_keepsSecret_movesDeadline() = runTest {
        val store = store()
        val secret = ByteArray(32) { 3 }
        store.setActiveSecret(secret, epochSeconds(10_000L))
        val extended = epochSeconds(20_000L)

        store.extendDeadline(extended)

        val session = store.session()
        assertContentEquals(secret, session?.secret)
        assertEquals(extended, session?.deadline)
    }

    @Test
    fun extendDeadline_noSession_isNoOp() = runTest {
        val store = store()

        store.extendDeadline(epochSeconds(10_000L))

        assertNull(store.session())
    }

    @Test
    fun burn_clearsSessionAndGate() = runTest {
        val store = store()
        store.setActiveSecret(ByteArray(32) { 3 }, epochSeconds(10_000L))

        store.burn()

        assertNull(store.session())
        assertNull(store.introKey())
    }

    @Test
    fun corruptEntry_readsAsAbsent_andIsRepaired() = runTest {
        val keyStore = InMemoryKeyStore()
        val store = BootstrapSessionStore(keyStore)
        // A bare pre-deadline-era secret (no magic prefix): stale by construction, must fail
        // closed instead of parsing as a garbage deadline.
        keyStore.putKey(
            KeyReference(
                keyId = "yapyap:bootstrap:secret",
                purpose = IdentityKeyPurpose.BOOTSTRAP_SECRET,
                type = KeyType.PRIVATE,
            ),
            ByteArray(32) { 9 },
        )

        assertNull(store.session())
        assertNull(store.introKey())
        // Repair path deleted the unreadable entry: a fresh setup starts clean.
        assertNull(store.session())
    }

    @Test
    fun codec_roundTrip_isExact() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        val deadline = epochSeconds(1_700_000_000L)

        val decoded = BootstrapSessionStore.decodeSession(BootstrapSessionStore.encodeSession(secret, deadline))

        assertContentEquals(secret, decoded?.secret)
        assertEquals(deadline, decoded?.deadline)
    }
}
