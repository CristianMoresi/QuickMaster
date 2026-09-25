package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.IdentityHashMap;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.ChannelLayout;

/** Current-leaf arithmetic: no reference filter, silence epsilon, or historic sum. */
class LoudnessCoreWindowTreeTest
{
    @Test
    void gettersNeverMutateAnyFieldOrArrayAndReadoutCadenceCannotChangeState() throws Exception
    {
        int rate = 31;
        LoudnessCore eager = new LoudnessCore(rate, ChannelLayout.STEREO_LR);
        LoudnessCore lazy = new LoudnessCore(rate, ChannelLayout.STEREO_LR);
        float[] frame = new float[2];
        for (int i = 0; i < 4 * 3 * rate + 17; i++)
        {
            frame[0] = (float) (StrictMath.sin(i * .173d) * (i < 37 ? .5d : 1e-10d));
            frame[1] = -frame[0];
            eager.acceptFrame(frame, 0);
            lazy.acceptFrame(frame, 0);
            Object[] before = snapshot(eager);
            for (int repeat = 0; repeat < 3; repeat++)
            {
                assertEquals(i + 1L, eager.framesSeen());
                if (i + 1 >= 12) eager.momentaryPower();
                else assertThrows(IllegalArgumentException.class, eager::momentaryPower);
                if (i + 1 >= 93) eager.shortTermPower();
                else assertThrows(IllegalArgumentException.class, eager::shortTermPower);
            }
            assertSnapshot(before, snapshot(eager), "Readout mutated state at frame " + (i + 1));
            assertSnapshot(snapshot(lazy), snapshot(eager), "Readout cadence changed state at frame " + (i + 1));
        }
        assertEquals(bits(eager.momentaryPower()), bits(lazy.momentaryPower()));
        assertEquals(bits(eager.shortTermPower()), bits(lazy.shortTermPower()));
        assertEquals(389L, eager.framesSeen(), "EOF getters must not pad or advance the clock");
    }

    @Test
    void everyFrameOfFourWrapsMatchesExactCurrentDoubleReductionForAllLayouts() throws Exception
    {
        for (int rate : new int[] { 1, 3, 29, 31, 500 })
            for (ChannelLayout layout : ChannelLayout.values())
            {
                LoudnessCore core = new LoudnessCore(rate, layout);
                int n = rate * 3;
                int m = Math.max(1, (int) StrictMath.round(rate * .4d));
                double[] history = new double[4 * n + 17];
                double[][] ring = array(core, "weightedPowerRing");
                BigInteger momentary = BigInteger.ZERO, shortTerm = BigInteger.ZERO;
                float[] frame = new float[layout.channels()];
                for (int i = 0; i < history.length; i++)
                {
                    for (int c = 0; c < frame.length; c++)
                        frame[c] = (float) (StrictMath.sin(i * .173d + c * .39d)
                                * (i % (n + 1) < n / 2 ? .5d : 1e-10d));
                    core.acceptFrame(frame, 0);
                    assertEquals((i + 1) % n, field("ringCursor").getInt(core));
                    history[i] = at(ring, i % n);
                    BigInteger inserted = units(history[i]);
                    momentary = momentary.add(inserted);
                    shortTerm = shortTerm.add(inserted);
                    if (i >= m) momentary = momentary.subtract(units(history[i - m]));
                    if (i >= n) shortTerm = shortTerm.subtract(units(history[i - n]));
                    if (i + 1 >= m) exactBound(core.momentaryPower(), momentary, m, n);
                    if (i + 1 >= n) exactBound(core.shortTermPower(), shortTerm, n, n);
                    if (i % n == 0 || i == history.length - 1) verifyTree(core);
                }
            }
    }

    @Test
    void everySmallRangeAndCircularCursorHandlesNonPowerOfTwoAndSubnormalPowers() throws Exception
    {
        Method rebuild = helper("rebuildTree", int.class, int.class);
        Method range = helper("treeRange", int.class, int.class, int.class);
        Method window = helper("treeWindow", int.class, int.class, int.class);
        for (int n = 1; n <= 41; n++)
        {
            double[][] ring = pages(n), tree = pages((n + 1) / 2);
            for (int i = 0; i < n; i++)
            {
                put(ring, i, new double[] { 0d, Double.MIN_VALUE, 3d * Double.MIN_VALUE,
                        1e-240d, 1e-20d, .08d, .5d, 1e60d }[i % 8]);
                invoke(rebuild, ring, tree, n, i);
            }
            assertEquals(0L, bits(at(tree, 0)));
            for (int start = 0; start <= n; start++)
                for (int end = start; end <= n; end++)
                    exactBound((double) invoke(range, ring, tree, n, start, end),
                            exact(ring, n, end, end - start), 1, n);
            for (int end = 0; end < n; end++)
                for (int length = 0; length <= n; length++)
                    exactBound((double) invoke(window, ring, tree, n, end, length),
                            exact(ring, n, end, length), 1, n);
        }
        double[][] ring = pages(3), tree = pages(2);
        for (int i = 0; i < 3; i++) { put(ring, i, Double.MIN_VALUE); invoke(rebuild, ring, tree, 3, i); }
        assertEquals(bits(3d * Double.MIN_VALUE), bits((double) invoke(range, ring, tree, 3, 0, 3)),
                "A positive subnormal sum must not be floored to silence");
    }

    @Test
    void ownedArraysAndAllChunksAreDistinctSizedAndFreshAcrossMeasurements() throws Exception
    {
        IdentityHashMap<Object, Boolean> owned = new IdentityHashMap<>();
        for (ChannelLayout layout : ChannelLayout.values())
            for (int instance = 0; instance < 2; instance++)
            {
                LoudnessCore core = new LoudnessCore(31, layout);
                int arrays = 0, tables = 0;
                for (Field f : LoudnessCore.class.getDeclaredFields())
                    if (f.getType() == double[].class)
                    {
                        f.setAccessible(true);
                        double[] a = (double[]) f.get(core);
                        assertNull(owned.put(a, Boolean.TRUE), "Aliased array " + f.getName());
                        int expected = f.getName().equals("coefficients") ? 10 : layout.channels();
                        assertEquals(expected, a.length, f.getName());
                        if (!f.getName().equals("coefficients"))
                            for (double value : a) assertEquals(0L, bits(value), f.getName());
                        arrays++;
                    }
                    else if (f.getType() == double[][].class)
                    {
                        f.setAccessible(true);
                        double[][] chunks = (double[][]) f.get(core);
                        assertNull(owned.put(chunks, Boolean.TRUE), "Aliased table " + f.getName());
                        int expected = f.getName().equals("weightedPowerRing") ? 93 : 47;
                        assertEquals(1, chunks.length);
                        assertEquals(expected, chunks[0].length);
                        assertNull(owned.put(chunks[0], Boolean.TRUE), "Aliased chunk " + f.getName());
                        for (double value : chunks[0]) assertEquals(0L, bits(value));
                        tables++;
                    }
                assertEquals(5, arrays);
                assertEquals(2, tables);
                assertEquals(0L, core.framesSeen());
                assertEquals(0L, bits(field("momentarySum").getDouble(core)));
                assertEquals(0L, bits(field("shortTermSum").getDouble(core)));
            }
    }

    @Test
    void helperBoundsAliasesVisitedNonfiniteAndOverflowAreRejected() throws Exception
    {
        Method node = helper("nodePower", int.class, int.class);
        Method rebuild = helper("rebuildTree", int.class, int.class);
        Method range = helper("treeRange", int.class, int.class, int.class);
        Method window = helper("treeWindow", int.class, int.class, int.class);
        double[][] ring = pages(3), tree = pages(2);
        for (int bad : new int[] { -1, 0, 6, Integer.MAX_VALUE })
            rejected(node, ring, tree, 3, bad);
        for (int bad : new int[] { -1, 3, Integer.MAX_VALUE }) rejected(rebuild, ring, tree, 3, bad);
        rejected(node, ring, ring, 3, 1);
        rejected(node, null, tree, 3, 1);
        rejected(node, ring, null, 3, 1);
        rejected(node, new double[0][], new double[0][], 3, 1);
        rejected(node, ring, new double[2][], 3, 1);
        rejected(node, ring, tree, 0, 1);
        rejected(node, ring, tree, Integer.MAX_VALUE, 1);
        for (int[] pair : new int[][] { { -1, 1 }, { 0, 4 }, { 2, 1 }, { 0, Integer.MAX_VALUE } })
            rejected(range, ring, tree, 3, pair[0], pair[1]);
        for (int[] pair : new int[][] { { -1, 1 }, { 3, 1 }, { 0, -1 }, { 0, 4 } })
            rejected(window, ring, tree, 3, pair[0], pair[1]);
        for (double bad : new double[] { -1d, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
        {
            put(ring, 0, bad);
            rejected(node, ring, tree, 3, 3);
            put(ring, 0, 0d);
            put(tree, 1, bad);
            rejected(node, ring, tree, 3, 1);
            put(tree, 1, 0d);
        }
        double[][] huge = pages(3);
        put(huge, 1, Double.MAX_VALUE); put(huge, 2, Double.MAX_VALUE);
        rejected(rebuild, huge, pages(2), 3, 1);
        rejected(range, huge, pages(2), 3, 0, 3);
    }

    @Test
    void precisionOracleKillsExpiredHistoryFloorAndMissingLeafControls()
    {
        BigInteger quiet = units(1e-20d).multiply(BigInteger.valueOf(19_200));
        assertThrows(AssertionError.class, () -> exactBound(1e-17d, quiet, 19_200, 144_000));
        assertThrows(AssertionError.class, () -> exactBound(0d, quiet, 19_200, 144_000));
        assertThrows(AssertionError.class, () -> exactBound(.125d, units(.5d), 1, 9));
        assertThrows(AssertionError.class, () -> exactBound(-0d, BigInteger.ZERO, 1, 9));
    }

    public static void main(String[] args) throws Exception
    {
        LoudnessCoreWindowTreeTest probe = new LoudnessCoreWindowTreeTest();
        if (args.length == 1 && args[0].equals("red"))
        {
            try { probe.gettersNeverMutateAnyFieldOrArrayAndReadoutCadenceCannotChangeState(); }
            catch (AssertionError failure)
            {
                System.out.println("CORE_GETTER_MUTATES_CURRENT_STATE: " + failure.getMessage());
                System.out.println("SUMMARY started=1 finished=1 passed=0 failed=1 skipped=0 aborted=0 infrastructure=0");
                throw failure;
            }
            System.out.println("SUMMARY started=1 finished=1 passed=1 failed=0 skipped=0 aborted=0 infrastructure=0");
            return;
        }
        int started = 0, passed = 0, failed = 0;
        for (Method test : LoudnessCoreWindowTreeTest.class.getDeclaredMethods())
            if (test.isAnnotationPresent(Test.class))
            {
                started++;
                try { test.invoke(probe); passed++; System.out.println("PASS " + test.getName()); }
                catch (InvocationTargetException failure)
                {
                    failed++;
                    System.out.println("FAIL " + test.getName() + ": " + failure.getCause());
                }
            }
        System.out.println("SUMMARY started=" + started + " finished=" + (passed + failed)
                + " passed=" + passed + " failed=" + failed + " skipped=0 aborted=0");
        if (failed != 0) throw new AssertionError("Core window contract failures=" + failed);
    }

    private static void verifyTree(LoudnessCore core) throws Exception
    {
        double[][] ring = array(core, "weightedPowerRing"), tree = array(core, "weightedPowerTree");
        int n = (int) field("shortTermWindowFrames").get(core);
        assertEquals(0L, bits(at(tree, 0)));
        for (int i = 1; i < n; i++)
        {
            int l = 2 * i, r = l + 1;
            double left = logical(ring, tree, n, l);
            double right = logical(ring, tree, n, r);
            assertEquals(bits(left + right), bits(logical(ring, tree, n, i)), "Stale ancestor " + i);
        }
    }

    private static BigInteger exact(double[][] ring, int n, int end, int length)
    {
        BigInteger result = BigInteger.ZERO;
        int start = end - length;
        if (start < 0) start += n;
        for (int i = 0; i < length; i++) result = result.add(units(at(ring, (start + i) % n)));
        return result;
    }

    // Every binary64 is an integer number of 2^-1074 units. The comparison
    // multiplies away W and gamma's denominator, so neither oracle rounds.
    private static void exactBound(double actual, BigInteger sum, int denominator, int n)
    {
        assertTrue(Double.isFinite(actual) && actual >= 0d);
        if (sum.signum() == 0) { assertEquals(0L, bits(actual), "Exact zero must be raw +0"); return; }
        int h = 32 - Integer.numberOfLeadingZeros(n - 1);
        long k = 3L * h + 4L;
        BigInteger gammaDenominator = BigInteger.ONE.shiftLeft(53).subtract(BigInteger.valueOf(k));
        BigInteger error = units(actual).multiply(BigInteger.valueOf(denominator)).subtract(sum).abs();
        BigInteger absolute = BigInteger.valueOf(4L * n + 8L * h + 16L)
                .multiply(BigInteger.valueOf(denominator));
        BigInteger limit = sum.multiply(BigInteger.valueOf(k)).add(absolute.multiply(gammaDenominator));
        assertTrue(error.multiply(gammaDenominator).compareTo(limit) <= 0,
                "Current-leaf rational bound failed: actual=" + actual + " W=" + denominator + " N=" + n);
    }

    private static BigInteger units(double value)
    {
        assertTrue(Double.isFinite(value) && value >= 0d);
        long raw = bits(value), fraction = raw & 0x000f_ffff_ffff_ffffL;
        int exponent = (int) ((raw >>> 52) & 0x7ffL);
        return exponent == 0 ? BigInteger.valueOf(fraction)
                : BigInteger.valueOf(fraction | (1L << 52)).shiftLeft(exponent - 1);
    }

    private static Object[] snapshot(LoudnessCore core) throws Exception
    {
        Field[] fields = LoudnessCore.class.getDeclaredFields();
        Object[] result = new Object[fields.length];
        for (int i = 0; i < fields.length; i++)
        {
            fields[i].setAccessible(true);
            Object value = fields[i].get(core);
            if (value instanceof double[] array)
            {
                long[] raw = new long[array.length];
                for (int j = 0; j < raw.length; j++) raw[j] = bits(array[j]);
                result[i] = raw;
            }
            else if (value instanceof double[][] chunks)
            {
                long[][] raw = new long[chunks.length][];
                for (int page = 0; page < chunks.length; page++)
                {
                    raw[page] = new long[chunks[page].length];
                    for (int j = 0; j < raw[page].length; j++) raw[page][j] = bits(chunks[page][j]);
                }
                result[i] = raw;
            }
            else result[i] = value instanceof Double d ? bits(d) : value;
        }
        return result;
    }

    private static void assertSnapshot(Object[] expected, Object[] actual, String cause)
    {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            if (expected[i] instanceof long[] a) assertArrayEquals(a, (long[]) actual[i], cause + " field=" + i);
            else if (expected[i] instanceof long[][] a)
            {
                long[][] b = (long[][]) actual[i];
                assertEquals(a.length, b.length, cause + " field=" + i);
                for (int page = 0; page < a.length; page++)
                    assertArrayEquals(a[page], b[page], cause + " field=" + i + " page=" + page);
            }
            else assertEquals(expected[i], actual[i], cause + " field=" + i);
    }

    private static Field field(String name) throws Exception
    {
        Field f = LoudnessCore.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static double[][] array(LoudnessCore core, String name) throws Exception { return (double[][]) field(name).get(core); }
    private static long bits(double value) { return Double.doubleToRawLongBits(value); }

    private static double[][] pages(int n)
    {
        double[][] result = new double[(n - 1) / 32768 + 1][];
        for (int page = 0; page < result.length; page++)
            result[page] = new double[Math.min(32768, n - page * 32768)];
        return result;
    }

    private static double at(double[][] pages, int index) { return pages[index >>> 15][index & 32767]; }
    private static void put(double[][] pages, int index, double value) { pages[index >>> 15][index & 32767] = value; }
    private static double logical(double[][] ring, double[][] tree, int n, int node)
    {
        if (node >= n) return at(ring, node - n);
        int k = (n + 1) / 2;
        return node < k ? at(tree, node) : at(ring, 2 * node - n) + at(ring, 2 * node + 1 - n);
    }

    private static Method helper(String name, Class<?>... suffix) throws Exception
    {
        Class<?>[] types = new Class<?>[suffix.length + 2];
        types[0] = types[1] = double[][].class;
        System.arraycopy(suffix, 0, types, 2, suffix.length);
        Method method = LoudnessCore.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static Object invoke(Method method, Object... args) throws Exception
    {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException failure)
        {
            if (failure.getCause() instanceof IllegalArgumentException bad) throw bad;
            throw failure;
        }
    }

    private static void rejected(Method method, Object... args)
    {
        assertThrows(IllegalArgumentException.class, () -> invoke(method, args), method.getName());
    }
}
