package com.dspark.core.wdf;

/**
 * Exact Fender '59 Bassman treble/bass/middle tone stack.
 * <p>
 * Topology and component values after Yeh and Smith, "Discretization of the
 * '59 Fender Bassman Tone Stack" (DAFx-06), figure 1: C1 = 250 pF into the
 * treble pot (R1 = 250 k ohm, output at the wiper), R4 = 56 k ohm into the
 * slope node, C2 = C3 = 20 nF from the slope node to the bass rheostat
 * (l R2, R2 = 1 M ohm) and to the middle pot wiper (R3 = 25 k ohm)
 * respectively. The source is modelled with a series output resistance
 * (cathode-follower buffer) and the wiper drives a grid-load resistance.
 * <p>
 * Solved exactly as one 12-port R-type adaptor under an ideal voltage source
 * root, so the stack is sample-exact against the bilinear transform of the
 * symbolic transfer function. Controls are not orthogonal: that interaction
 * is the character of the circuit.
 * <p>
 * {@link #prepare} and {@link #setControls} rebuild the scattering (a 12-node
 * solve, nothing allocated); {@link #processSample} allocates nothing.
 */
public final class ToneStackFMV
{
    private static final int SRC = 0, VI = 1, A = 2, VO = 3, B = 4, S = 5, C = 6, W = 7;
    private static final int NUM_NODES = 8;

    /** Minimum pot segment, which keeps the node matrix well-posed. */
    private static final double R_MIN = 0.5;

    /** Ports at the common DC voltage: source, C1, C2 and C3. */
    private static final double[] DC_MODE = { 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 0 };

    private final Resistor rOut;
    private final Capacitor c1;
    private final Resistor r1Top, r1Bot, r4;
    private final Capacitor c2;
    private final Resistor r2;
    private final Capacitor c3;
    private final Resistor r3Top, r3Bot, rLoad;

    private final RType rtype;
    private final IdealVoltageSourceRoot root;

    private double treble = 0.5, bass = 0.5, middle = 0.5;

    // Scratch of analogStateSpace(): node voltages kVi..kW, then the three
    // capacitor currents.
    private static final int SS_N = 10;
    private final double[][] ssM = new double[SS_N][SS_N];
    private final double[][] ssRhs = new double[SS_N][4];

    /** A stack driven through 1 k ohm into a 1 M ohm load. */
    public ToneStackFMV()
    {
        this(1e3, 1e6);
    }

    /**
     * @param sourceResistance  driving stage output impedance in ohms
     * @param loadResistance    following stage grid load in ohms
     */
    public ToneStackFMV(double sourceResistance, double loadResistance)
    {
        rOut = new Resistor(sourceResistance);
        c1 = new Capacitor(0.25e-9);
        r1Top = new Resistor(125e3);
        r1Bot = new Resistor(125e3);
        r4 = new Resistor(56e3);
        c2 = new Capacitor(20e-9);
        r2 = new Resistor(500e3);
        c3 = new Capacitor(20e-9);
        r3Top = new Resistor(12.5e3);
        r3Bot = new Resistor(12.5e3);
        rLoad = new Resistor(loadResistance);
        rtype = new RType(new int[][] {
                    { SRC, -1 },        // port 0: ideal source root
                    { SRC, VI },        // rOut
                    { VI, A },          // C1
                    { A, VO },          // (1-t) R1
                    { VO, B },          // t R1
                    { VI, S },          // R4
                    { S, B },           // C2
                    { B, C },           // l R2 (rheostat)
                    { S, W },           // C3 to the middle wiper
                    { C, W },           // (1-m) R3
                    { W, -1 },          // m R3 to ground
                    { VO, -1 } },       // grid load
                NUM_NODES,
                rOut, c1, r1Top, r1Bot, r4, c2, r2, c3, r3Top, r3Bot, rLoad);
        root = new IdealVoltageSourceRoot(rtype);
    }

    /**
     * Prepares the network for a sample rate and clears its states
     * (allocates nothing).
     *
     * @param sampleRate  sample rate in Hz
     */
    public void prepare(double sampleRate)
    {
        root.prepare(sampleRate);
        setControls(treble, bass, middle);
    }

    /** Clears the capacitor states. */
    public void reset()
    {
        root.reset();
    }

    /**
     * Copies the capacitor history from an identically prepared stack.
     * <p>
     * Stream-owner only: neither stack may be processed concurrently. Both
     * stacks must have the same sample rate and controls. Port connections
     * remain owned by the destination; no scattering rebuild occurs. A
     * nonzero input offset shifts all three coupling capacitors by the same
     * DC voltage; the subsequent input must use that reference. The stack
     * blocks DC, so this reference shift preserves its output.
     *
     * @param source       the stack to copy
     * @param inputOffset  DC offset of the following input (0 for none)
     */
    public void copyStateFrom(ToneStackFMV source, double inputOffset)
    {
        c1.copyFrom(source.c1);
        c2.copyFrom(source.c2);
        c3.copyFrom(source.c3);
        if (inputOffset != 0.0)
        {
            c1.offsetVoltage(inputOffset);
            c2.offsetVoltage(inputOffset);
            c3.offsetVoltage(inputOffset);
        }
    }

    /** {@link #copyStateFrom(ToneStackFMV, double)} without an offset. */
    public void copyStateFrom(ToneStackFMV source)
    {
        copyStateFrom(source, 0.0);
    }

    /**
     * Sets the three controls, [0, 1] each (out-of-range values are clamped;
     * a NaN is passed through as the clamp passes it).
     * <p>
     * Treble and middle pots are linear, the bass pot is a log-taper
     * rheostat (squared approximation), matching the original circuit. The
     * scattering is rebuilt and the capacitor states are kept.
     *
     * @param treble  treble pot position
     * @param bass    bass pot position
     * @param middle  middle pot position
     */
    public void setControls(double treble, double bass, double middle)
    {
        this.treble = clamp01(treble);
        this.bass = clamp01(bass);
        this.middle = clamp01(middle);

        final double t = this.treble;
        final double l = this.bass * this.bass;
        final double m = this.middle;

        r1Top.setResistance((1.0 - t) * 250e3 + R_MIN);
        r1Bot.setResistance(t * 250e3 + R_MIN);
        r2.setResistance(l * 1e6 + R_MIN);
        r3Top.setResistance((1.0 - m) * 25e3 + R_MIN);
        r3Bot.setResistance(m * 25e3 + R_MIN);
        rtype.updatePorts();           // rebuild scattering, keep states
        rtype.preserveDcMode(DC_MODE);
    }

    /** Treble pot position in [0, 1]. */
    public double getTreble()
    {
        return treble;
    }

    /** Bass pot position in [0, 1]. */
    public double getBass()
    {
        return bass;
    }

    /** Middle pot position in [0, 1]. */
    public double getMiddle()
    {
        return middle;
    }

    /**
     * Processes one sample (input volts to wiper volts).
     *
     * @param input  source voltage
     * @return the voltage across the grid load
     */
    public double processSample(double input)
    {
        root.setVoltage(input);
        root.process();
        return rLoad.getVoltage();
    }

    /** The scattering adaptor (package access for verification). */
    RType rType()
    {
        return rtype;
    }

    /**
     * Continuous-time state space of the network:
     * dx/dt = a x + b u, y = c x + d u.
     * <p>
     * States are the capacitor voltages (C1: input to treble pot, C2: slope
     * node to bass rheostat, C3: slope node to middle wiper), the input is
     * the source voltage and the output is the wiper voltage. a =
     * -diag(capacitance)^-1 G for a symmetric positive definite G.
     */
    public static final class AnalogStateSpace
    {
        /** State matrix. */
        public final double[][] a = new double[3][3];
        /** Input vector. */
        public final double[] b = new double[3];
        /** Output vector. */
        public final double[] c = new double[3];
        /** Feedthrough. */
        public double d;
        /** Capacitances of C1, C2 and C3 in farads. */
        public final double[] capacitance = new double[3];
    }

    /**
     * The continuous-time state space of the same network, in a new object.
     *
     * @return the state space
     * @see #analogStateSpace(AnalogStateSpace)
     */
    public AnalogStateSpace analogStateSpace()
    {
        final AnalogStateSpace out = new AnalogStateSpace();
        analogStateSpace(out);
        return out;
    }

    /**
     * Fills the continuous-time state space of the network. The matrices are
     * obtained by nodal analysis of the identical element values and
     * topology used by the R-type adaptor, with each capacitor replaced by a
     * voltage source. Allocation free; cost of a 10x10 elimination. Uses
     * scratch storage of this stack: owner thread only.
     *
     * @param out  the state space to fill (every field is overwritten)
     */
    public void analogStateSpace(AnalogStateSpace out)
    {
        // Unknowns: node voltages kVi..kW (7) then the three capacitor currents.
        final int n = SS_N;
        final double[][] m = ssM;
        final double[][] rhs = ssRhs;   // columns: unit C1, C2, C3 voltages, unit source
        for (int i = 0; i < n; ++i)
        {
            java.util.Arrays.fill(m[i], 0.0);
            java.util.Arrays.fill(rhs[i], 0.0);
        }
        int capIndex = 0;
        for (int e = 0; e < ELEMENT_P.length; ++e)
        {
            final double value = elementValue(e);
            final int ep = ELEMENT_P[e], em = ELEMENT_M[e];
            final int p = ep - 1, q = em < 0 ? -1 : em - 1;   // kSrc maps to -1
            if (ELEMENT_CAPACITOR[e])
            {
                final int row = 7 + capIndex;
                if (p >= 0) { m[p][row] += 1.0; m[row][p] += 1.0; }
                if (q >= 0) { m[q][row] -= 1.0; m[row][q] -= 1.0; }
                rhs[row][capIndex] = 1.0;
                out.capacitance[capIndex] = value;
                ++capIndex;
                continue;
            }
            final double g = 1.0 / value;
            if (ep == SRC)
            {
                // Source node is driven: its conductance stamps the RHS.
                m[q][q] += g;
                rhs[q][3] += g;
                continue;
            }
            if (p >= 0) m[p][p] += g;
            if (q >= 0) m[q][q] += g;
            if (p >= 0 && q >= 0) { m[p][q] -= g; m[q][p] -= g; }
        }
        // Gaussian elimination with partial pivoting, four right-hand sides.
        for (int k = 0; k < n; ++k)
        {
            int piv = k;
            for (int i = k + 1; i < n; ++i)
                if (Math.abs(m[i][k]) > Math.abs(m[piv][k])) piv = i;
            if (piv != k)
                for (int j = 0; j < n; ++j) { final double t = m[piv][j]; m[piv][j] = m[k][j]; m[k][j] = t; }
            if (piv != k)
                for (int j = 0; j < 4; ++j) { final double t = rhs[piv][j]; rhs[piv][j] = rhs[k][j]; rhs[k][j] = t; }
            for (int i = k + 1; i < n; ++i)
            {
                final double f = m[i][k] / m[k][k];
                if (f == 0.0) continue;
                for (int j = k; j < n; ++j) m[i][j] -= f * m[k][j];
                for (int j = 0; j < 4; ++j) rhs[i][j] -= f * rhs[k][j];
            }
        }
        for (int i = n - 1; i >= 0; --i)
            for (int j = 0; j < 4; ++j)
            {
                double v = rhs[i][j];
                for (int k = i + 1; k < n; ++k) v -= m[i][k] * rhs[k][j];
                rhs[i][j] = v / m[i][i];
            }
        // Capacitor current flows from its first node to its second node.
        for (int j = 0; j < 4; ++j)
        {
            for (int k = 0; k < 3; ++k)
            {
                final double dv = rhs[7 + k][j] / out.capacitance[k];
                if (j < 3) out.a[k][j] = dv;
                else out.b[k] = dv;
            }
            if (j < 3) out.c[j] = rhs[VO - 1][j];
            else out.d = rhs[VO - 1][j];
        }
    }

    // Elements of the network, in the order of the nodal stamps.
    private static final int[] ELEMENT_P = { SRC, VI, A, VO, VI, S, B, S, C, W, VO };
    private static final int[] ELEMENT_M = { VI, A, VO, B, S, B, C, W, W, -1, -1 };
    private static final boolean[] ELEMENT_CAPACITOR =
        { false, true, false, false, false, true, false, true, false, false, false };

    private double elementValue(int e)
    {
        switch (e)
        {
            case 0: return rOut.getResistance();
            case 1: return c1.getCapacitance();
            case 2: return r1Top.getResistance();
            case 3: return r1Bot.getResistance();
            case 4: return r4.getResistance();
            case 5: return c2.getCapacitance();
            case 6: return r2.getResistance();
            case 7: return c3.getCapacitance();
            case 8: return r3Top.getResistance();
            case 9: return r3Bot.getResistance();
            default: return rLoad.getResistance();
        }
    }

    /** Clamps to [0, 1] (a NaN passes through). */
    private static double clamp01(double v)
    {
        return v < 0.0 ? 0.0 : (1.0 < v ? 1.0 : v);
    }
}
