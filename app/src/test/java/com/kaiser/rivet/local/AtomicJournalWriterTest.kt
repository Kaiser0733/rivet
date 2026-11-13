package com.kaiser.rivet.local

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicJournalWriterTest {

    @Test
    fun concurrentWritesNeverThrowOrLeaveCorruptOrTempFiles() {
        val dir = Files.createTempDirectory("journal-race-test").toFile()
        try {
            val target = File(dir, "downloads.json")
            val lock = Any()
            val pool = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)
            val done = CountDownLatch(4)
            val failure = AtomicReference<Throwable?>()

            repeat(4) { worker ->
                pool.submit {
                    try {
                        start.await()
                        repeat(60) { i ->
                            val payload = """{"worker":$worker,"step":$i}"""
                            AtomicJournalWriter.write(target, payload, lock)
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            done.await()
            pool.shutdownNow()

            assertNull(failure.get()?.toString(), failure.get())
            assertTrue(target.isFile)
            val leftoverTemps = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
            assertTrue("Expected no leftover tmp files, found $leftoverTemps", leftoverTemps.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
