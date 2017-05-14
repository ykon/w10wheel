package win32ex;

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinNT.HRESULT;

public interface WinUserX extends WinUser
{
    // https://msdn.microsoft.com/library/ff468877.aspx
    public int WM_MOUSEMOVE = 0x0200;
    public int WM_LBUTTONDOWN = 0x0201;
    public int WM_LBUTTONUP = 0x0202;
    public int WM_LBUTTONDBLCLK = 0x0203;
    public int WM_RBUTTONDOWN = 0x0204;
    public int WM_RBUTTONUP = 0x0205;
    public int WM_RBUTTONDBLCLK = 0x0206;
    public int WM_MBUTTONDOWN = 0x0207;
    public int WM_MBUTTONUP = 0x0208;
    public int WM_MBUTTONDBLCLK = 0x0209;
    public int WM_MOUSEWHEEL = 0x020A;
    public int WM_XBUTTONDOWN = 0x020B;
    public int WM_XBUTTONUP = 0x020C;
    public int WM_XBUTTONDBLCLK = 0x020D;
    public int WM_MOUSEHWHEEL = 0x020E;
    
    // https://msdn.microsoft.com/library/ms646245.aspx
    // low-order
    public int MK_LBUTTON = 0x0001;
    public int MK_RBUTTON = 0x0002;
    public int MK_SHIFT = 0x0004;
    public int MK_CONTROL = 0x0008;
    public int MK_MBUTTON = 0x0010;
    public int MK_XBUTTON1 = 0x0020;
    public int MK_XBUTTON2 = 0x0040;
    
    // high-order
    public int XBUTTON1 = 0x0001;
    public int XBUTTON2 = 0x0002;
    
    // https://msdn.microsoft.com/library/ms646273.aspx
    public int WHEEL_DELTA = 120;
    public int MOUSEEVENTF_ABSOLUTE = 0x8000;
    public int MOUSEEVENTF_HWHEEL = 0x01000;
    public int MOUSEEVENTF_MOVE = 0x0001;
    public int MOUSEEVENTF_LEFTDOWN = 0x0002;
    public int MOUSEEVENTF_LEFTUP = 0x0004;
    public int MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public int MOUSEEVENTF_RIGHTUP = 0x0010;
    public int MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    public int MOUSEEVENTF_MIDDLEUP = 0x0040;
    public int MOUSEEVENTF_WHEEL = 0x0800;
    public int MOUSEEVENTF_XDOWN = 0x0080;
    public int MOUSEEVENTF_XUP = 0x0100;
    
    // https://msdn.microsoft.com/library/windows/desktop/dd375731.aspx
    public int VK_LBUTTON = 0x01;
    public int VK_RBUTTON = 0x02;
    public int VK_MBUTTON = 0x04;
    public int VK_XBUTTON1 = 0x05;
    public int VK_XBUTTON2 = 0x06;

    // http://stackoverflow.com/questions/7004810/from-java-capture-mouse-click-and-use-as-hotkey
    
    // https://msdn.microsoft.com/library/windows/desktop/ms644986.aspx
    public interface LowLevelMouseProc extends HOOKPROC 
    {
        LRESULT callback(int nCode, WPARAM wParam, MSLLHOOKSTRUCT lParam);
    }

    // https://msdn.microsoft.com/library/windows/desktop/ms644970.aspx
    public class MSLLHOOKSTRUCT extends Structure 
    {
        public POINT pt;
        public int mouseData;
        public int flags;
        public int time;
        public ULONG_PTR dwExtraInfo;
        
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(new String[] { "pt", "mouseData", "flags",
                                                "time", "dwExtraInfo" });
        }
    }
    
    // https://msdn.microsoft.com/library/windows/desktop/ms648395.aspx
    public int OCR_APPSTARTING = 32650;
    public int OCR_NORMAL = 32512;
    public int OCR_CROSS = 32515;
    public int OCR_HAND = 32649;
    public int OCR_HELP = 32651;
    public int OCR_IBEAM = 32513;
    public int OCR_NO = 32648;
    public int OCR_SIZEALL = 32646;
    public int OCR_SIZENESW = 32643;
    public int OCR_SIZENS = 32645;
    public int OCR_SIZENWSE = 32642;
    public int OCR_SIZEWE = 32644;
    public int OCR_UP = 32516;
    public int OCR_WAIT = 32514;
    
    // https://msdn.microsoft.com/library/windows/desktop/ms724947.aspx
    public int SPI_SETCURSORS = 0x0057;
    
    public interface User32ex extends User32 {
        User32ex INSTANCE = (User32ex)Native.loadLibrary("user32", User32ex.class);
        
        public short GetKeyState(int vKey);
        public short GetAsyncKeyState(int vKey);
    
        // https://msdn.microsoft.com/library/windows/desktop/aa383751.aspx
        public Pointer LoadImageW(HINSTANCE hinst, Pointer ptr, int type, int xDesired, int yDesired, int load);
        public Pointer LoadCursorW(HINSTANCE hInstance, Pointer lpCursorName);
        public boolean SystemParametersInfoW(int uiAction, int uiParam, Pointer pvParam, int fWinIni);
        public boolean SetSystemCursor(Pointer hcur, int id);
        //public Pointer CopyIcon(Pointer hIcon);
        //public boolean GetCursorPos(POINT p);
        public boolean GetPhysicalCursorPos(POINT lpPoint);
        public boolean SetProcessDPIAware();
        
        public HMONITOR MonitorFromPoint(POINT pt, int dwFlags);
        
        //public int SetThreadDpiAwarenessContext(int value);
    }
    
    // https://msdn.microsoft.com/library/windows/desktop/ms686219.aspx
    public int ABOVE_NORMAL_PRIORITY_CLASS = 0x00008000;
    public int HIGH_PRIORITY_CLASS = 0x00000080;
    public int NORMAL_PRIORITY_CLASS = 0x00000020;
    
    public interface Kernel32ex extends Kernel32 {
        Kernel32ex INSTANCE = (Kernel32ex)Native.loadLibrary("kernel32", Kernel32ex.class);
        
        public boolean SetPriorityClass(HANDLE hProcess, int dwPriorityClass);
    }
    
    public static interface PROCESS_DPI_AWARENESS { 
        public static final int PROCESS_DPI_UNAWARE            = 0;
        public static final int PROCESS_SYSTEM_DPI_AWARE       = 1;
        public static final int PROCESS_PER_MONITOR_DPI_AWARE  = 2;
    }
    
    public static interface DPI_AWARENESS_CONTEXT { 
        public static final int DPI_AWARENESS_CONTEXT_DEFAULT            = 0;
        public static final int DPI_AWARENESS_CONTEXT_UNAWARE            = 1;
        public static final int DPI_AWARENESS_CONTEXT_SYSTEM_AWARE       = 2;
        public static final int DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE  = 3;
    }
    
    public static interface DpiType {
        public static final int Effective = 0;
        public static final int Angular = 1;
        public static final int Raw = 2; 
    }
 
    /*
    public int MONITOR_DEFAULTTONEAREST = 0x00000002;
    public int MONITOR_DEFAULTTONULL = 0x00000000;
    public int MONITOR_DEFAULTTOPRIMARY = 0x00000001;
    */
    
    public int S_OK = 0x00000000;
    public int E_INVALIDARG = 0x80070057;
    public int E_ACCESSDENIED = 0x80070005;
    
    public interface Shcore extends StdCallLibrary {
        Shcore INSTANCE = (Shcore)Native.loadLibrary("Shcore", Shcore.class);
        
        public HRESULT SetProcessDpiAwareness(int value);
        public HRESULT GetDpiForMonitor(HMONITOR hmonitor, int dpiType, IntByReference dpiX, IntByReference dpiY);
    }
}