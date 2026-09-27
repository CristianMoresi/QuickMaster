package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link RingBuffer}. */
class RingBufferTest
{
    @Test
    @DisplayName("read(delay) returns the sample written 'delay' pushes ago")
    void delayedReads()
    {
        RingBuffer rb = new RingBuffer();
        rb.prepare(8);
        rb.push(1f); rb.push(2f); rb.push(3f); rb.push(4f);
        assertEquals(4f, rb.read(0), 0f);
        assertEquals(3f, rb.read(1), 0f);
        assertEquals(2f, rb.read(2), 0f);
        assertEquals(1f, rb.read(3), 0f);
    }

    @Test
    @DisplayName("Capacity is rounded up to a power of two")
    void capacityPowerOfTwo()
    {
        RingBuffer rb = new RingBuffer();
        rb.prepare(100);
        assertEquals(128, rb.getCapacity());
    }

    @Test
    @DisplayName("Reads before prepare are safe")
    void safeBeforePrepare()
    {
        RingBuffer rb = new RingBuffer();
        assertEquals(0f, rb.read(0), 0f);   // no exception
        assertTrue(rb.getCapacity() == 0);
    }
}
