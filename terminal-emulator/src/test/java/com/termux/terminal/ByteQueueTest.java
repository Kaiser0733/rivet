package com.termux.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ByteQueueTest {
    @Test
    public void tryWriteDropsTheWholeWriteWhenTheQueueIsFull() {
        ByteQueue queue = new ByteQueue(4);
        assertTrue(queue.tryWrite(new byte[] {1, 2, 3}, 0, 3));

        long started = System.nanoTime();
        assertFalse(queue.tryWrite(new byte[] {4, 5}, 0, 2));
        assertTrue("tryWrite must not wait", System.nanoTime() - started < 100_000_000L);

        byte[] out = new byte[4];
        assertEquals(3, queue.read(out, false));
        assertArrayEquals(new byte[] {1, 2, 3}, Arrays.copyOf(out, 3));

        assertTrue(queue.tryWrite(new byte[] {4, 5}, 0, 2));
        assertEquals(2, queue.read(out, false));
        assertArrayEquals(new byte[] {4, 5}, Arrays.copyOf(out, 2));
    }

    @Test
    public void tryWriteRefusesAfterClose() {
        ByteQueue queue = new ByteQueue(4);
        queue.close();
        assertFalse(queue.tryWrite(new byte[] {1}, 0, 1));
    }
}
