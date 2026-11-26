package com.dspark.core.wdf;

/**
 * N-port R-type adaptor for non-series/parallel interconnections.
 * <p>
 * Port 0 faces up (toward the root) and is adapted: its resistance is set to
 * the Thevenin resistance seen into the network, making S(0,0) = 0. Ports
 * 1..N-1 connect the children, in declaration order. The topology is a list
 * of (node+, node-) pairs over a small node graph (-1 = ground reference).
 * <p>
 * On every {@link #updatePorts()} the scattering matrix is rebuilt from
 * Modified Nodal Analysis with the current port resistances of the children:
 * each port is replaced by its instantaneous Thevenin equivalent (source a_i,
 * resistance R_i), the linear node system is solved, and S = 2M - I where M
 * maps incident waves to port voltages. O(nodes^3), a few microseconds for
 * typical circuits, fine for pot automation. Nothing is allocated after
 * construction.
 */
public final class RType implements WdfNode
{
    /** Largest node graph (non-ground nodes). */
    public static final int MAX_NODES = 12;

    private final WdfNode[] children;
    private final int numPorts;
    private final int[] nodePlus;
    private final int[] nodeMinus;
    private final int numNodes;

    private final double[] portR;
    private final double[] a;
    private final double[][] s;

    private final double[][] g = new double[MAX_NODES][MAX_NODES];
    private final double[][] lu = new double[MAX_NODES][MAX_NODES];
    private final int[] piv = new int[MAX_NODES];
    private final double[] rhs = new double[MAX_NODES];
    private final double[] work = new double[MAX_NODES];

    /**
     * @param portNodes  (node+, node-) per port, port 0 first; -1 is ground.
     *                   Indices outside [-1, numNodes - 1] are clamped into
     *                   that range (a clamped topology is wrong but cannot
     *                   read or write out of bounds).
     * @param numNodes   number of non-ground nodes in the graph, clamped to
     *                   [1, MAX_NODES]
     * @param children   child elements bound to ports 1..N-1, in order
     * @throws IllegalArgumentException when there is no child or the number
     *         of port pairs is not the number of children plus one
     */
    public RType(int[][] portNodes, int numNodes, WdfNode... children)
    {
        if (children.length < 1)
            throw new IllegalArgumentException("RType needs at least one child");
        if (portNodes.length != children.length + 1)
            throw new IllegalArgumentException("RType needs one node pair per port");
        this.children = children.clone();
        this.numPorts = children.length + 1;
        this.numNodes = numNodes < 1 ? 1 : (numNodes > MAX_NODES ? MAX_NODES : numNodes);
        this.nodePlus = new int[numPorts];
        this.nodeMinus = new int[numPorts];
        for (int j = 0; j < numPorts; ++j)
        {
            if (portNodes[j].length != 2)
                throw new IllegalArgumentException("RType node pairs have two entries");
            nodePlus[j] = clampNode(portNodes[j][0]);
            nodeMinus[j] = clampNode(portNodes[j][1]);
        }
        this.portR = new double[numPorts];
        this.a = new double[numPorts];
        this.s = new double[numPorts][numPorts];
    }

    private int clampNode(int node)
    {
        return node < -1 ? -1 : (node > numNodes - 1 ? numNodes - 1 : node);
    }

    /** Number of ports, the up-facing port included. */
    public int getNumPorts()
    {
        return numPorts;
    }

    @Override
    public void prepare(double sampleRate)
    {
        for (WdfNode ch : children) ch.prepare(sampleRate);
    }

    @Override
    public void updatePorts()
    {
        for (WdfNode ch : children) ch.updatePorts();

        // Gather child port resistances (port 0 resolved by adaptation).
        for (int i = 1; i < numPorts; ++i) portR[i] = children[i - 1].portResistance();

        // 1) Thevenin resistance looking into the network from port 0:
        //    assemble MNA without port 0, inject 1 A across its nodes.
        assembleConductance(false);
        factor();
        java.util.Arrays.fill(rhs, 0.0);
        stampCurrent(rhs, 0, 1.0);
        solve(rhs);
        final double rth = portVoltage(rhs, 0);
        portR[0] = rth < 1e-6 ? 1e-6 : (1e12 < rth ? 1e12 : rth);

        // 2) Full MNA with every port loaded; S = 2M - I where column j of M
        //    is the port-voltage response to a_j = 1 (Norton: 1/R_j).
        assembleConductance(true);
        factor();
        for (int j = 0; j < numPorts; ++j)
        {
            java.util.Arrays.fill(rhs, 0.0);
            stampCurrent(rhs, j, 1.0 / portR[j]);
            solve(rhs);
            for (int i = 0; i < numPorts; ++i)
            {
                final double m = portVoltage(rhs, i);
                s[i][j] = 2.0 * m - (i == j ? 1.0 : 0.0);
            }
        }
        // Adaptation leaves no instantaneous reflection at the up port.
        s[0][0] = 0.0;
    }

    @Override
    public void reset()
    {
        for (WdfNode ch : children) ch.reset();
        java.util.Arrays.fill(a, 0.0);
    }

    @Override
    public double portResistance()
    {
        return portR[0];
    }

    @Override
    public double reflected()
    {
        for (int i = 1; i < numPorts; ++i) a[i] = children[i - 1].reflected();
        double b0 = 0.0;
        final double[] row = s[0];
        for (int j = 1; j < numPorts; ++j) b0 += row[j] * a[j];
        return b0;
    }

    @Override
    public void incident(double aUp)
    {
        a[0] = aUp;
        for (int i = 1; i < numPorts; ++i) children[i - 1].incident(rowDot(i));
    }

    /** Scattering coefficient S(i, j), valid after {@link #updatePorts()}. */
    double scattering(int i, int j)
    {
        return s[i][j];
    }

    /**
     * Preserves a topology-proven zero-current voltage mode after the nodal
     * solve: S v = v. Correcting only the final nonzero column removes
     * factorization roundoff in this identity without adding arithmetic to
     * sample processing.
     *
     * @param mode  the port voltage mode, one entry per port
     */
    void preserveDcMode(double[] mode)
    {
        int pivot = numPorts - 1;
        while (pivot > 0 && mode[pivot] == 0.0) --pivot;
        if (pivot == 0) return; // Keep the adapted up-port reflection at zero.
        for (int i = 0; i < numPorts; ++i)
        {
            double remainder = 0.0;
            for (int j = 0; j < numPorts; ++j)
                if (j != pivot)
                    remainder += s[i][j] * mode[j];
            s[i][pivot] = (mode[i] - remainder) / mode[pivot];
        }
    }

    private double rowDot(int row)
    {
        double acc = 0.0;
        final double[] r = s[row];
        for (int j = 0; j < numPorts; ++j) acc += r[j] * a[j];
        return acc;
    }

    private void assembleConductance(boolean includePort0)
    {
        for (double[] row : g) java.util.Arrays.fill(row, 0.0);
        for (int j = includePort0 ? 0 : 1; j < numPorts; ++j)
        {
            final double gj = 1.0 / portR[j];
            final int p = nodePlus[j];
            final int m = nodeMinus[j];
            if (p >= 0) g[p][p] += gj;
            if (m >= 0) g[m][m] += gj;
            if (p >= 0 && m >= 0)
            {
                g[p][m] -= gj;
                g[m][p] -= gj;
            }
        }
    }

    private void stampCurrent(double[] v, int port, double amps)
    {
        final int p = nodePlus[port];
        final int m = nodeMinus[port];
        if (p >= 0) v[p] += amps;
        if (m >= 0) v[m] -= amps;
    }

    private double portVoltage(double[] v, int port)
    {
        final int p = nodePlus[port];
        final int m = nodeMinus[port];
        return (p >= 0 ? v[p] : 0.0) - (m >= 0 ? v[m] : 0.0);
    }

    /** LU factorization with partial pivoting of the node matrix. */
    private void factor()
    {
        final int n = numNodes;
        for (int i = 0; i < n; ++i)
        {
            for (int j = 0; j < n; ++j)
                lu[i][j] = g[i][j];
            piv[i] = i;
        }
        for (int k = 0; k < n; ++k)
        {
            int p = k;
            for (int i = k + 1; i < n; ++i)
                if (Math.abs(lu[i][k]) > Math.abs(lu[p][k]))
                    p = i;
            if (p != k)
            {
                final double[] rowSwap = lu[p];
                lu[p] = lu[k];
                lu[k] = rowSwap;
                final int pivSwap = piv[p];
                piv[p] = piv[k];
                piv[k] = pivSwap;
            }
            double d = lu[k][k];
            if (Math.abs(d) < 1e-300)
                d = (d >= 0.0 ? 1e-300 : -1e-300);
            final double invD = 1.0 / d;
            for (int i = k + 1; i < n; ++i)
            {
                final double f = lu[i][k] * invD;
                lu[i][k] = f;
                for (int j = k + 1; j < n; ++j)
                    lu[i][j] -= f * lu[k][j];
            }
        }
    }

    /** Solves the factored system in place (v becomes the solution). */
    private void solve(double[] v)
    {
        final int n = numNodes;
        final double[] y = work;
        for (int i = 0; i < n; ++i)
            y[i] = v[piv[i]];
        for (int i = 0; i < n; ++i)
            for (int j = 0; j < i; ++j)
                y[i] -= lu[i][j] * y[j];
        for (int i = n - 1; i >= 0; --i)
        {
            for (int j = i + 1; j < n; ++j)
                y[i] -= lu[i][j] * y[j];
            double d = lu[i][i];
            if (Math.abs(d) < 1e-300)
                d = (d >= 0.0 ? 1e-300 : -1e-300);
            y[i] /= d;
        }
        for (int i = 0; i < n; ++i)
            v[i] = y[i];
    }
}
