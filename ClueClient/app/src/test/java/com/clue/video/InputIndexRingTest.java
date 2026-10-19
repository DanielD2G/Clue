package com.clue.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class InputIndexRingTest {
    @Test
    public void emptyRingPollsNothing() {
        InputIndexRing ring = new InputIndexRing(8);
        assertEquals(-1, ring.poll());
    }

    @Test
    public void indicesComeOutInArrivalOrder() {
        InputIndexRing ring = new InputIndexRing(8);
        assertTrue(ring.offer(3));
        assertTrue(ring.offer(0));
        assertTrue(ring.offer(7));
        assertEquals(3, ring.poll());
        assertEquals(0, ring.poll());
        assertEquals(7, ring.poll());
        assertEquals(-1, ring.poll());
    }

    @Test
    public void fullRingRejectsWithoutLosingContents() {
        InputIndexRing ring = new InputIndexRing(4);
        for (int i = 0; i < 4; i++) {
            assertTrue(ring.offer(i));
        }
        assertFalse(ring.offer(9));
        for (int i = 0; i < 4; i++) {
            assertEquals(i, ring.poll());
        }
    }

    @Test
    public void wrapsAroundManyTimes() {
        InputIndexRing ring = new InputIndexRing(4);
        for (int i = 0; i < 1000; i++) {
            assertTrue(ring.offer(i));
            assertTrue(ring.offer(i + 1));
            assertEquals(i, ring.poll());
            assertEquals(i + 1, ring.poll());
        }
        assertEquals(-1, ring.poll());
    }

    @Test
    public void capacityRoundsUpToPowerOfTwo() {
        InputIndexRing ring = new InputIndexRing(5);
        for (int i = 0; i < 8; i++) {
            assertTrue(ring.offer(i));
        }
        assertFalse(ring.offer(8));
    }

    @Test
    public void clearDropsEverything() {
        InputIndexRing ring = new InputIndexRing(8);
        ring.offer(1);
        ring.offer(2);
        ring.clear();
        assertEquals(-1, ring.poll());
        assertTrue(ring.offer(5));
        assertEquals(5, ring.poll());
    }

    @Test
    public void takeReturnsImmediatelyWhenAvailable() {
        InputIndexRing ring = new InputIndexRing(8);
        ring.offer(4);
        long t0 = System.nanoTime();
        assertEquals(4, ring.take(1_000_000_000L));
        assertTrue(System.nanoTime() - t0 < 50_000_000L);
    }

    @Test
    public void takeGivesUpAfterTimeout() {
        InputIndexRing ring = new InputIndexRing(8);
        long t0 = System.nanoTime();
        assertEquals(-1, ring.take(20_000_000L));
        long elapsed = System.nanoTime() - t0;
        assertTrue("waited " + elapsed, elapsed >= 20_000_000L);
        assertTrue("waited " + elapsed, elapsed < 500_000_000L);
    }

    @Test
    public void takeWakesAsSoonAsProducerOffers() throws Exception {
        final InputIndexRing ring = new InputIndexRing(8);
        final CountDownLatch waiting = new CountDownLatch(1);
        final long[] result = new long[2];
        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                waiting.countDown();
                result[0] = ring.take(5_000_000_000L);
                result[1] = System.nanoTime();
            }
        });
        consumer.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        Thread.sleep(50); // let the consumer park
        long offeredAt = System.nanoTime();
        ring.offer(6);
        consumer.join(5000);
        assertFalse(consumer.isAlive());
        assertEquals(6, result[0]);
        // Woken by the offer, not by the 5 s timeout
        assertTrue("woke after " + (result[1] - offeredAt), result[1] - offeredAt < 1_000_000_000L);
    }

    @Test
    public void producerAndConsumerOnDifferentThreadsLoseNothing() throws Exception {
        final InputIndexRing ring = new InputIndexRing(16);
        final int n = 200_000;
        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < n; ) {
                    if (ring.offer(i & 0xFFFF)) {
                        i++;
                    } else {
                        Thread.yield();
                    }
                }
            }
        });
        producer.start();
        for (int i = 0; i < n; i++) {
            int v = ring.take(5_000_000_000L);
            assertEquals(i & 0xFFFF, v);
        }
        producer.join(5000);
        assertEquals(-1, ring.poll());
    }
}
