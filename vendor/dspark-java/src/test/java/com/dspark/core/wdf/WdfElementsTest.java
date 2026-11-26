package com.dspark.core.wdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the WDF one-port elements, adaptors and the ideal source root. */
class WdfElementsTest
{
    private static final double PI = 3.14159265358979;

    @Test
    @DisplayName("Series RC low-pass matches the analytic bilinear one-pole")
    void rcLowpassMatchesBilinear()
    {
        // A WDF network is the bilinear discretization of its circuit.
        final double R = 1000.0, C = 100e-9, fs = 48000.0;
        final Resistor r = new Resistor(R);
        final Capacitor c = new Capacitor(C);
        final Series tree = new Series(r, c);
        final IdealVoltageSourceRoot root = new IdealVoltageSourceRoot(tree);
        root.prepare(fs);

        final double cb = 1.0 / (2.0 * fs * R * C);
        double x1 = 0, y1 = 0, maxErr = 0;
        for (int n = 0; n < 4800; ++n)
        {
            final double x = Math.sin(2.0 * PI * 1000.0 * n / fs) + 0.5 * Math.sin(2.0 * PI * 7900.0 * n / fs);
            root.setVoltage(x);
            root.process();
            final double yRef = (cb * (x + x1) - (cb - 1.0) * y1) / (1.0 + cb);
            x1 = x;
            y1 = yRef;
            maxErr = Math.max(maxErr, Math.abs(c.getVoltage() - yRef));
        }
        assertTrue(maxErr < 1e-12, "max error " + maxErr);
        // The resistor carries the rest of the source voltage, and the
        // series current is common to both elements.
        assertEquals(x1 - c.getVoltage(), r.getVoltage(), 1e-12);
        assertEquals(r.getCurrent(), c.getCurrent(), 1e-15);
        assertEquals(r.getVoltage() / R, r.getCurrent(), 1e-15);
    }

    @Test
    @DisplayName("Series RLC matches the analytic bilinear biquad")
    void seriesRlcMatchesBilinear()
    {
        final double R = 220.0, L = 10e-3, C = 47e-9, fs = 48000.0;
        final Resistor r = new Resistor(R);
        final Inductor l = new Inductor(L);
        final Capacitor c = new Capacitor(C);
        final Series lc = new Series(l, c);
        final Series tree = new Series(r, lc);
        final IdealVoltageSourceRoot root = new IdealVoltageSourceRoot(tree);
        root.prepare(fs);

        final double K = 2.0 * fs;
        final double lck = L * C * K * K, rck = R * C * K;
        final double a0 = lck + rck + 1.0;
        final double b0 = 1.0 / a0, b1c = 2.0 / a0, b2 = 1.0 / a0;
        final double a1 = (2.0 - 2.0 * lck) / a0;
        final double a2 = (lck - rck + 1.0) / a0;

        double x1 = 0, x2 = 0, y1 = 0, y2 = 0, maxErr = 0;
        for (int n = 0; n < 9600; ++n)
        {
            final double x = (n == 0) ? 1.0 : 0.0;
            root.setVoltage(x);
            root.process();
            final double yRef = b0 * x + b1c * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = yRef;
            maxErr = Math.max(maxErr, Math.abs(c.getVoltage() - yRef));
        }
        assertTrue(maxErr < 1e-9, "max error " + maxErr);
        assertEquals(c.getCurrent(), l.getCurrent(), 1e-12);
    }

    @Test
    @DisplayName("Inverter flips the polarity of its subtree only")
    void inverterFlipsSubtreePolarity()
    {
        final double fs = 48000.0;
        final Resistor r1a = new Resistor(1000.0), r2a = new Resistor(2000.0);
        final IdealVoltageSourceRoot rootA = new IdealVoltageSourceRoot(new Series(r1a, r2a));
        rootA.prepare(fs);

        final Resistor r1b = new Resistor(1000.0), r2b = new Resistor(2000.0);
        final Inverter inv = new Inverter(r2b);
        final IdealVoltageSourceRoot rootB = new IdealVoltageSourceRoot(new Series(r1b, inv));
        rootB.prepare(fs);

        rootA.setVoltage(3.0);
        rootA.process();
        rootB.setVoltage(3.0);
        rootB.process();

        assertEquals(2.0, r2a.getVoltage(), 1e-12);                  // 3 V * 2k/3k
        assertEquals(-r2a.getVoltage(), r2b.getVoltage(), 1e-12);    // inverted view
        assertEquals(r1a.getVoltage(), r1b.getVoltage(), 1e-12);     // sibling intact
        assertEquals(2000.0, inv.portResistance(), 0.0);
    }

    @Test
    @DisplayName("Parallel RC under a series resistor matches the analytic bilinear divider")
    void parallelDividerMatchesBilinear()
    {
        // Vin -[R1]-+- R2 || C to ground: H(s) = R2 / (R1 + R2 + s R1 R2 C).
        final double R1 = 4700.0, R2 = 10000.0, C = 33e-9, fs = 44100.0;
        final Resistor r1 = new Resistor(R1), r2 = new Resistor(R2);
        final Capacitor c = new Capacitor(C);
        final Parallel shunt = new Parallel(r2, c);
        final IdealVoltageSourceRoot root = new IdealVoltageSourceRoot(new Series(r1, shunt));
        root.prepare(fs);

        // Bilinear: s = K (1 - z^-1) / (1 + z^-1).
        final double K = 2.0 * fs, k = R1 * R2 * C * K, sumR = R1 + R2;
        final double a0 = sumR + k, a1 = (sumR - k) / a0, b = R2 / a0;
        double x1 = 0, y1 = 0, maxErr = 0;
        final java.util.Random rng = new java.util.Random(12);
        for (int n = 0; n < 6000; ++n)
        {
            final double x = rng.nextGaussian();
            root.setVoltage(x);
            root.process();
            final double yRef = b * (x + x1) - a1 * y1;
            x1 = x;
            y1 = yRef;
            maxErr = Math.max(maxErr, Math.abs(c.getVoltage() - yRef));
        }
        assertTrue(maxErr < 1e-12, "max error " + maxErr);
        assertEquals(c.getVoltage(), r2.getVoltage(), 1e-12);   // common node
        assertEquals(1.0 / (1.0 / R2 + 2.0 * fs * C), shunt.portResistance(), 1e-9);
    }

    @Test
    @DisplayName("A resistive voltage source inside a tree delivers its terminal voltage")
    void resistiveVoltageSourceDivides()
    {
        // A 1 V source with 1 k ohm inside drives 3 k ohm: the terminals
        // carry 0.75 V. The tree closes on a 0 V ideal root.
        final ResistiveVoltageSource vs = new ResistiveVoltageSource(1000.0);
        final Resistor load = new Resistor(3000.0);
        final IdealVoltageSourceRoot root = new IdealVoltageSourceRoot(new Series(vs, load));
        root.prepare(48000.0);
        vs.setVoltage(1.0);
        root.process();
        assertEquals(0.75, vs.getVoltage(), 1e-15);
        assertEquals(-0.75, load.getVoltage(), 1e-15);
        assertEquals(1000.0, vs.portResistance(), 0.0);

        vs.setResistance(-5.0);
        load.setResistance(0.0);
        root.prepare(48000.0);
        assertEquals(1e-9, vs.portResistance(), 0.0);
        assertEquals(1e-9, load.getResistance(), 0.0);
    }

    @Test
    @DisplayName("Setters keep the documented minimum values")
    void elementMinimums()
    {
        final Capacitor c = new Capacitor(1e-9);
        c.setCapacitance(0.0);
        assertEquals(1e-18, c.getCapacitance(), 0.0);
        c.setCapacitance(2e-9);
        assertEquals(2e-9, c.getCapacitance(), 0.0);
        c.prepare(50000.0);
        c.updatePorts();
        assertEquals(1.0 / (2.0 * 50000.0 * 2e-9), c.portResistance(), 0.0);

        final Inductor l = new Inductor(1e-3);
        l.setInductance(-1.0);
        l.prepare(48000.0);
        l.updatePorts();
        assertEquals(2.0 * 48000.0 * 1e-12, l.portResistance(), 0.0);

        final Resistor r = new Resistor(10.0);
        r.setResistance(1e-12);
        r.updatePorts();
        assertEquals(1e-9, r.portResistance(), 0.0);
    }

    @Test
    @DisplayName("A non-finite source resets the tree, which then continues from rest")
    void recoversAfterNonFiniteInput()
    {
        final double fs = 48000.0;
        final Capacitor c = new Capacitor(100e-9), fc = new Capacitor(100e-9);
        final IdealVoltageSourceRoot root = new IdealVoltageSourceRoot(new Series(new Resistor(1000.0), c));
        final IdealVoltageSourceRoot fresh = new IdealVoltageSourceRoot(new Series(new Resistor(1000.0), fc));
        root.prepare(fs);
        fresh.prepare(fs);
        for (int i = 0; i < 100; ++i)
        {
            root.setVoltage(Math.sin(0.1 * i));
            root.process();
        }
        root.setVoltage(Double.NaN);
        root.process();
        root.setVoltage(Double.POSITIVE_INFINITY);
        root.process();
        for (int i = 0; i < 500; ++i)
        {
            final double x = 0.3 * Math.sin(0.05 * i);
            root.setVoltage(x);
            root.process();
            fresh.setVoltage(x);
            fresh.process();
            assertEquals(fc.getVoltage(), c.getVoltage(), 0.0);
        }
    }

    @Test
    @DisplayName("R-type: Thevenin adaptation and an RC low-pass equal to the series tree")
    void rTypeRcLowpass()
    {
        final double R = 1000.0, C = 100e-9, fs = 48000.0;
        final Resistor r = new Resistor(R);
        final Capacitor c = new Capacitor(C);
        // Port 0: source (node 0 to ground); R from node 0 to node 1; C from node 1 to ground.
        final RType rtype = new RType(new int[][] { { 0, -1 }, { 0, 1 }, { 1, -1 } }, 2, r, c);
        final IdealVoltageSourceRoot root = new IdealVoltageSourceRoot(rtype);
        root.prepare(fs);
        assertEquals(3, rtype.getNumPorts());
        assertEquals(R + 1.0 / (2.0 * fs * C), rtype.portResistance(), 1e-9);
        assertEquals(0.0, rtype.scattering(0, 0), 0.0);

        final Resistor rs = new Resistor(R);
        final Capacitor cs = new Capacitor(C);
        final IdealVoltageSourceRoot ref = new IdealVoltageSourceRoot(new Series(rs, cs));
        ref.prepare(fs);
        double maxErr = 0.0;
        for (int n = 0; n < 4800; ++n)
        {
            final double x = Math.sin(2.0 * PI * 1000.0 * n / fs) + 0.5 * Math.sin(2.0 * PI * 7900.0 * n / fs);
            root.setVoltage(x);
            root.process();
            ref.setVoltage(x);
            ref.process();
            maxErr = Math.max(maxErr, Math.abs(c.getVoltage() - cs.getVoltage()));
        }
        assertTrue(maxErr < 1e-12, "max error " + maxErr);
        assertEquals(cs.getCurrent(), c.getCurrent(), 1e-15);
    }

    @Test
    @DisplayName("R-type: invalid constructions are refused, out-of-range nodes clamped")
    void rTypeConstruction()
    {
        final Resistor r = new Resistor(1.0);
        assertThrows(IllegalArgumentException.class, () -> new RType(new int[][] { { 0, -1 } }, 1));
        assertThrows(IllegalArgumentException.class, () -> new RType(new int[][] { { 0, -1 } }, 1, r));
        assertThrows(IllegalArgumentException.class, () -> new RType(new int[][] { { 0 }, { 0, -1 } }, 1, r));
        // Node 5 is clamped into the one-node graph: still a valid divider.
        final RType clamped = new RType(new int[][] { { 0, -1 }, { 5, -7 } }, 1, r);
        clamped.prepare(48000.0);
        clamped.updatePorts();
        assertEquals(1.0, clamped.portResistance(), 1e-12);
    }
}
