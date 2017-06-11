package hooktest.win32ex

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.ByReference
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinUser._
import com.sun.jna.platform.win32.WinDef._
import com.sun.jna.platform.win32.WinNT._

import scala.collection.JavaConverters._

// http://stackoverflow.com/questions/7004810/from-java-capture-mouse-click-and-use-as-hotkey
// https://msdn.microsoft.com/library/windows/desktop/ms644986.aspx
trait LowLevelMouseProc extends HOOKPROC
{
    def callback(nCode: Int, wParam: WPARAM, lParam: MSLLHOOKSTRUCT): LRESULT
}

object User32Ex {
    // https://msdn.microsoft.com/library/ff468877.aspx
    val WM_MOUSEMOVE = 0x0200
    val WM_LBUTTONDOWN = 0x0201
    val WM_LBUTTONUP = 0x0202
    val WM_LBUTTONDBLCLK = 0x0203
    val WM_RBUTTONDOWN = 0x0204
    val WM_RBUTTONUP = 0x0205
    val WM_RBUTTONDBLCLK = 0x0206
    val WM_MBUTTONDOWN = 0x0207
    val WM_MBUTTONUP = 0x0208
    val WM_MBUTTONDBLCLK = 0x0209
    val WM_MOUSEWHEEL = 0x020A
    val WM_XBUTTONDOWN = 0x020B
    val WM_XBUTTONUP = 0x020C
    val WM_XBUTTONDBLCLK = 0x020D
    val WM_MOUSEHWHEEL = 0x020E

    // https://msdn.microsoft.com/library/ms646245.aspx
    // low-order
    val MK_LBUTTON = 0x0001
    val MK_RBUTTON = 0x0002
    val MK_SHIFT = 0x0004
    val MK_CONTROL = 0x0008
    val MK_MBUTTON = 0x0010
    val MK_XBUTTON1 = 0x0020
    val MK_XBUTTON2 = 0x0040

    // high-order
    val XBUTTON1 = 0x0001
    val XBUTTON2 = 0x0002

    // https://msdn.microsoft.com/library/ms646273.aspx
    val WHEEL_DELTA = 120
    val MOUSEEVENTF_ABSOLUTE = 0x8000
    val MOUSEEVENTF_HWHEEL = 0x01000
    val MOUSEEVENTF_MOVE = 0x0001
    val MOUSEEVENTF_LEFTDOWN = 0x0002
    val MOUSEEVENTF_LEFTUP = 0x0004
    val MOUSEEVENTF_RIGHTDOWN = 0x0008
    val MOUSEEVENTF_RIGHTUP = 0x0010
    val MOUSEEVENTF_MIDDLEDOWN = 0x0020
    val MOUSEEVENTF_MIDDLEUP = 0x0040
    val MOUSEEVENTF_WHEEL = 0x0800
    val MOUSEEVENTF_XDOWN = 0x0080
    val MOUSEEVENTF_XUP = 0x0100

    // https://msdn.microsoft.com/library/windows/desktop/dd375731.aspx
    val VK_LBUTTON = 0x01
    val VK_RBUTTON = 0x02
    val VK_MBUTTON = 0x04
    val VK_XBUTTON1 = 0x05
    val VK_XBUTTON2 = 0x06

    // https://msdn.microsoft.com/library/windows/desktop/ms648395.aspx
    val OCR_APPSTARTING = 32650
    val OCR_NORMAL = 32512
    val OCR_CROSS = 32515
    val OCR_HAND = 32649
    val OCR_HELP = 32651
    val OCR_IBEAM = 32513
    val OCR_NO = 32648
    val OCR_SIZEALL = 32646
    val OCR_SIZENESW = 32643
    val OCR_SIZENS = 32645
    val OCR_SIZENWSE = 32642
    val OCR_SIZEWE = 32644
    val OCR_UP = 32516
    val OCR_WAIT = 32514

    // https://msdn.microsoft.com/library/windows/desktop/ms724947.aspx
    val SPI_SETCURSORS = 0x0057

    val MSGFLT_ALLOW = 1
    val MSGFLT_DISALLOW = 2
    val MSGFLT_RESET = 0

    val WM_QUERYENDSESSION = 0x0011
    val WM_INPUT = 0x00ff
    val RIM_INPUT = 0
    val RIM_INPUTSINK = 1

    val HID_USAGE_PAGE_GENERIC: Short  = 0x01
    val HID_USAGE_GENERIC_MOUSE: Short = 0x02

    val RIDEV_INPUTSINK = 0x00000100
    val RIDEV_REMOVE = 0x00000001

    val RID_INPUT = 0x10000003
    val MOUSE_MOVE_RELATIVE = 0
    //val RIM_TYPEMOUSE = 0

    val INSTANCE = Native.loadLibrary("user32", classOf[User32ExTrait]).asInstanceOf[User32ExTrait]
}

trait User32ExTrait extends User32
{
    def GetKeyState(vKey: Int): Short
    def GetAsyncKeyState(vKey: Int): Short

    // https://msdn.microsoft.com/library/windows/desktop/aa383751.aspx
    def LoadImageW(hinst: HINSTANCE, ptr: Pointer, uType: Int, xDesired: Int, yDesired: Int, load: Int): Pointer
    def LoadCursorW(hInstance: HINSTANCE, lpCursorName: Pointer): Pointer
    def SystemParametersInfoW(uiAction: Int, uiParam: Int, pvParam: Pointer, fWinIni: Int): Boolean
    def SetSystemCursor(hcur: Pointer, id: Int): Boolean
    def MonitorFromPoint(pt: POINT, dwFlags: Int): HMONITOR
    def ChangeWindowMessageFilterEx(hWnd: HWND, msg: Int, action: Int, pcfs: Pointer): Boolean

    def RegisterRawInputDevices(pRawInputDevices: Array[RAWINPUTDEVICE], uiNumDevices: Int, cbSize: Int): Boolean
    def GetRawInputData(hRawInput: Pointer, uiCommand: Int, pData: Pointer, pcbSize: ByReference, cbSizeHeader: Int): Int
}

object Kernel32Ex {
    // https://msdn.microsoft.com/library/windows/desktop/ms686219.aspx
    val ABOVE_NORMAL_PRIORITY_CLASS = 0x00008000
    val HIGH_PRIORITY_CLASS = 0x00000080
    val NORMAL_PRIORITY_CLASS = 0x00000020

    val INSTANCE = Native.loadLibrary("kernel32", classOf[Kernel32ExTrait]).asInstanceOf[Kernel32ExTrait]
}

trait Kernel32ExTrait extends Kernel32
{
    def SetPriorityClass(hProcess: HANDLE, dwPriorityClass: Int): Boolean
}