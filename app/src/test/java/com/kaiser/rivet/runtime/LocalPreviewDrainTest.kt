package com.kaiser.rivet.runtime

import java.net.Socket
import java.util.NoSuchElementException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPreviewDrainTest {
    @Test fun aClientThatFinishesWhileCloseRunsIsDrainedWithoutThrowing() {
        val first = Socket()
        val second = Socket()
        val clients = ConcurrentLinkedQueue<Socket>()
        clients += first
        clients += second
        // The handler for `first` finishes exactly here, as it does in production.
        assertTrue(clients.remove(first))

        assertEquals(listOf(second), drainClients(clients))
        assertTrue(clients.isEmpty())
        assertEquals(emptyList<Socket>(), drainClients(clients))
    }

    @Test fun theKeySetTheServerUsedToDrainThrowsWhenOneKeyDisappears() {
        // Mechanism behind the CI NoSuchElementException. With exactly one element,
        // Collections.toList takes the size==1 fast path, calls iterator().next(), and
        // if the handler removed that key in between, the fresh iterator is empty:
        // ConcurrentHashMap$KeyIterator.next throws NoSuchElementException.
        val set = ConcurrentHashMap.newKeySet<Int>()
        set += 1
        assertEquals(1, set.size)
        set.remove(1)
        assertThrows(NoSuchElementException::class.java) { set.iterator().next() }
    }
}
