package com.quickmaster.ui;

import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

/** Constrains the native Windows live-resize rectangle before it is painted. */
public final class WindowsAspectRatioHook implements AutoCloseable
{
    private static final int WM_SIZING = 0x0214;
    private static final int WM_ENTERSIZEMOVE = 0x0231;
    private static final int WM_EXITSIZEMOVE = 0x0232;

    private static final int WMSZ_LEFT = 1;
    private static final int WMSZ_RIGHT = 2;
    private static final int WMSZ_TOP = 3;
    private static final int WMSZ_TOPLEFT = 4;
    private static final int WMSZ_TOPRIGHT = 5;
    private static final int WMSZ_BOTTOM = 6;
    private static final int WMSZ_BOTTOMLEFT = 7;
    private static final int WMSZ_BOTTOMRIGHT = 8;

    private final HWND window;
    private final double minimumScale;
    private final double maximumScale;
    private final WinUser.WindowProc windowProcedure;
    private final Runnable onMoveOrSizeStart;
    private final Runnable onResizeStart;
    private final Runnable onResizeEnd;

    /** All geometry below is in Win32 physical pixels, not JavaFX units. */
    private double designWidth;
    private double designHeight;
    private double decorationWidth;
    private double decorationHeight;
    private double lastScale;

    private Pointer previousWindowProcedure;
    private boolean installed;
    private boolean resizing;

    private WindowsAspectRatioHook(HWND window, double designWidth,
            double designHeight, double minimumScale, double maximumScale,
            double initialScale, Runnable onMoveOrSizeStart,
            Runnable onResizeStart, Runnable onResizeEnd)
    {
        this.window = window;
        this.designWidth = designWidth;
        this.designHeight = designHeight;
        this.minimumScale = minimumScale;
        this.maximumScale = maximumScale;
        this.lastScale = clamp(initialScale);
        this.onMoveOrSizeStart = onMoveOrSizeStart;
        this.onResizeStart = onResizeStart;
        this.onResizeEnd = onResizeEnd;
        this.windowProcedure = this::windowProcedure;
    }

    public static WindowsAspectRatioHook install(String windowTitle,
            double designWidth, double designHeight, double minimumScale,
            double maximumScale, double initialScale,
            double outputScaleX, double outputScaleY,
            Runnable onMoveOrSizeStart,
            Runnable onResizeStart, Runnable onResizeEnd)
    {
        HWND window = findCurrentProcessWindow(windowTitle);
        WindowsAspectRatioHook hook = new WindowsAspectRatioHook(window,
                designWidth, designHeight, minimumScale, maximumScale,
                initialScale, onMoveOrSizeStart,
                onResizeStart, onResizeEnd);

        // Changing the frame changes its decoration thickness. Do it before
        // measuring physical geometry or installing the sizing callback.
        hook.disableNativeMaximise();
        hook.calibrateNativeGeometry(outputScaleX, outputScaleY);
        hook.installWindowProcedure();
        return hook;
    }

    private void calibrateNativeGeometry(double outputScaleX,
            double outputScaleY)
    {
        designWidth *= outputScaleX;
        designHeight *= outputScaleY;

        RECT nativeBounds = new RECT();
        if (!User32.INSTANCE.GetWindowRect(window, nativeBounds))
            throw new IllegalStateException("GetWindowRect failed with error "
                    + Native.getLastError());

        decorationWidth = Math.max(0.0,
                nativeBounds.right - nativeBounds.left
                        - designWidth * lastScale);
        decorationHeight = Math.max(0.0,
                nativeBounds.bottom - nativeBounds.top
                        - designHeight * lastScale);
    }

    private void installWindowProcedure()
    {
        Pointer callbackPointer = CallbackReference.getFunctionPointer(
                windowProcedure);
        Native.setLastError(0);
        previousWindowProcedure = User32.INSTANCE.SetWindowLongPtr(
                window, WinUser.GWL_WNDPROC, callbackPointer);
        if (previousWindowProcedure == null)
            throw new IllegalStateException("SetWindowLongPtr failed with error "
                    + Native.getLastError());
        installed = true;
    }

    private void disableNativeMaximise()
    {
        User32 user32 = User32.INSTANCE;
        int style = user32.GetWindowLong(window, WinUser.GWL_STYLE);
        user32.SetWindowLong(window, WinUser.GWL_STYLE,
                style & ~WinUser.WS_MAXIMIZEBOX);
        user32.SetWindowPos(window, null, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE
                        | WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE
                        | WinUser.SWP_FRAMECHANGED);
    }

    private LRESULT windowProcedure(HWND hwnd, int message, WPARAM wParam,
            LPARAM lParam)
    {
        if (message == WM_ENTERSIZEMOVE)
            onMoveOrSizeStart.run();
        else if (message == WM_EXITSIZEMOVE)
        {
            onResizeEnd.run();
            resizing = false;
        }

        if (message == WM_SIZING && lParam.longValue() != 0)
        {
            if (!resizing)
            {
                resizing = true;
                onResizeStart.run();
            }
            SizingRect rectangle = new SizingRect(
                    new Pointer(lParam.longValue()));
            constrainRectangle(wParam.intValue(), rectangle);
            rectangle.write();
            return new LRESULT(1);
        }

        return User32.INSTANCE.CallWindowProc(previousWindowProcedure,
                hwnd, message, wParam, lParam);
    }

    private void constrainRectangle(int edge, SizingRect rectangle)
    {
        double widthScale = (rectangle.width() - decorationWidth)
                / designWidth;
        double heightScale = (rectangle.height() - decorationHeight)
                / designHeight;

        double requestedScale;
        if (edge == WMSZ_LEFT || edge == WMSZ_RIGHT)
        {
            requestedScale = widthScale;
        }
        else if (edge == WMSZ_TOP || edge == WMSZ_BOTTOM)
        {
            requestedScale = heightScale;
        }
        else
        {
            // A corner can move on both axes. Choosing either the width or
            // height on every message makes the driver alternate as the
            // pointer crosses the ideal aspect-ratio diagonal, which causes
            // visible size oscillation. Project the proposed corner onto that
            // diagonal instead so the scale changes continuously.
            double widthWeight = designWidth * designWidth;
            double heightWeight = designHeight * designHeight;
            requestedScale = (widthScale * widthWeight
                    + heightScale * heightWeight)
                    / (widthWeight + heightWeight);
        }

        double scale = clamp(requestedScale);
        int targetWidth = (int) Math.round(
                designWidth * scale + decorationWidth);
        int targetHeight = (int) Math.round(
                designHeight * scale + decorationHeight);

        switch (edge)
        {
            case WMSZ_LEFT ->
            {
                rectangle.left = rectangle.right - targetWidth;
                centreVertically(rectangle, targetHeight);
            }
            case WMSZ_RIGHT ->
            {
                rectangle.right = rectangle.left + targetWidth;
                centreVertically(rectangle, targetHeight);
            }
            case WMSZ_TOP ->
            {
                rectangle.top = rectangle.bottom - targetHeight;
                centreHorizontally(rectangle, targetWidth);
            }
            case WMSZ_BOTTOM ->
            {
                rectangle.bottom = rectangle.top + targetHeight;
                centreHorizontally(rectangle, targetWidth);
            }
            case WMSZ_TOPLEFT ->
            {
                rectangle.left = rectangle.right - targetWidth;
                rectangle.top = rectangle.bottom - targetHeight;
            }
            case WMSZ_TOPRIGHT ->
            {
                rectangle.right = rectangle.left + targetWidth;
                rectangle.top = rectangle.bottom - targetHeight;
            }
            case WMSZ_BOTTOMLEFT ->
            {
                rectangle.left = rectangle.right - targetWidth;
                rectangle.bottom = rectangle.top + targetHeight;
            }
            case WMSZ_BOTTOMRIGHT ->
            {
                rectangle.right = rectangle.left + targetWidth;
                rectangle.bottom = rectangle.top + targetHeight;
            }
            default -> { return; }
        }
        lastScale = scale;
    }

    private static void centreHorizontally(SizingRect rectangle, int width)
    {
        int centre = rectangle.left + rectangle.width() / 2;
        rectangle.left = centre - width / 2;
        rectangle.right = rectangle.left + width;
    }

    private static void centreVertically(SizingRect rectangle, int height)
    {
        int centre = rectangle.top + rectangle.height() / 2;
        rectangle.top = centre - height / 2;
        rectangle.bottom = rectangle.top + height;
    }

    private double clamp(double value)
    {
        if (!Double.isFinite(value))
            return lastScale;
        return Math.max(minimumScale, Math.min(maximumScale, value));
    }

    private static HWND findCurrentProcessWindow(String windowTitle)
    {
        User32 user32 = User32.INSTANCE;
        long currentPid = ProcessHandle.current().pid();
        HWND[] result = { null };
        user32.EnumWindows((candidate, data) ->
        {
            IntByReference pid = new IntByReference();
            user32.GetWindowThreadProcessId(candidate, pid);
            if (Integer.toUnsignedLong(pid.getValue()) != currentPid
                    || !user32.IsWindowVisible(candidate))
                return true;

            int length = user32.GetWindowTextLength(candidate);
            if (length <= 0)
                return true;
            char[] text = new char[length + 1];
            user32.GetWindowText(candidate, text, text.length);
            if (windowTitle.equals(Native.toString(text)))
            {
                result[0] = candidate;
                return false;
            }
            return true;
        }, null);

        if (result[0] == null)
            throw new IllegalStateException("QuickMaster native window was not found");
        return result[0];
    }

    @Override
    public void close()
    {
        if (!installed)
            return;
        User32.INSTANCE.SetWindowLongPtr(window, WinUser.GWL_WNDPROC,
                previousWindowProcedure);
        installed = false;
    }

    @Structure.FieldOrder({ "left", "top", "right", "bottom" })
    public static final class SizingRect extends Structure
    {
        public int left;
        public int top;
        public int right;
        public int bottom;

        private SizingRect(Pointer pointer)
        {
            super(pointer);
            read();
        }

        private int width()
        {
            return right - left;
        }

        private int height()
        {
            return bottom - top;
        }
    }
}
