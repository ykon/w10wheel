package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.{ Kernel32, User32, WinNT, WinUser }
import com.sun.jna.platform.win32.WinDef._
import com.sun.jna.platform.win32.WinUser.{ HHOOK, INPUT }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }
import com.sun.jna.platform.win32.WinUser.{ MSLLHOOKSTRUCT => HookInfo }
import com.sun.jna.ptr.IntByReference

import win32ex._

// The calls to Windows of the triggers' handlers that act on the desktop (the input sent, the
// cursor changed, RawInput registered, the next hook called) or read its state (the keys down, the
// cursor, the window under a point and its program). The program uses them as they are; the tests
// replace them, so that they neither send input nor change the cursor, and tell what the handlers
// did. As W10Wheel.NET's Host.
object Host {
    private val u32 = User32.INSTANCE
    private val u32ex = User32Ex.INSTANCE
    private val k32 = Kernel32.INSTANCE

    // One input sent (SendInput): whether it was
    @volatile var sendInput: INPUT => Boolean =
        input => u32.SendInput(new DWORD(1), Array(input), input.size()).intValue() == 1

    // The state of a key now (GetAsyncKeyState)
    @volatile var getAsyncKeyState: Int => Short =
        vk => u32.GetAsyncKeyState(vk)

    // A system cursor (id) set to a copy of the cursor (SetSystemCursor destroys the copy only if
    // it succeeds: destroyed here otherwise)
    @volatile var setSystemCursor: (HICON, Int) => Unit = (icon, id) => {
        val copy = u32.CopyIcon(icon)
        val h = if (copy == null) null else copy.getPointer
        if (h != null && !u32ex.SetSystemCursor(h, id))
            u32ex.DestroyCursor(h)
    }

    // The system cursors reloaded (SPI_SETCURSORS): whether it succeeded
    @volatile var restoreSystemCursors: () => Boolean =
        () => u32ex.SystemParametersInfoW(User32Ex.SPI_SETCURSORS, 0, null, 0)

    // RegisterRawInputDevices
    @volatile var registerRawInputDevices: Array[RAWINPUTDEVICE] => Boolean =
        rid => u32ex.RegisterRawInputDevices(rid, rid.length, rid(0).size)

    // CallNextHookEx of the mouse and keyboard hooks
    @volatile var callNextMouseHook: (HHOOK, Int, WPARAM, HookInfo) => LRESULT =
        (hhk, nCode, wParam, info) => callNextHook(hhk, nCode, wParam, info.getPointer)

    @volatile var callNextKeyboardHook: (HHOOK, Int, WPARAM, KHookInfo) => LRESULT =
        (hhk, nCode, wParam, info) => callNextHook(hhk, nCode, wParam, info.getPointer)

    private def callNextHook(hhk: HHOOK, nCode: Int, wParam: WPARAM, ptr: Pointer): LRESULT =
        u32.CallNextHookEx(hhk, nCode, wParam, new LPARAM(Pointer.nativeValue(ptr)))

    // The cursor position (GetCursorPos) in pt: whether it could be read
    @volatile var getCursorPos: POINT => Boolean =
        pt => u32.GetCursorPos(pt)

    // The window at a point (WindowFromPoint: null if none), its top-level window (GetAncestor
    // GA_ROOT), the process of a window (GetWindowThreadProcessId: 0 if the window is gone) and the
    // full path of a process (None if it cannot be read)
    @volatile var windowFromPoint: POINT => HWND =
        pt => u32ex.WindowFromPoint(new POINT.ByValue(pt.x, pt.y))

    @volatile var rootWindow: HWND => HWND =
        hWnd => u32.GetAncestor(hWnd, WinUser.GA_ROOT)

    @volatile var processIdOfWindow: HWND => Int = hWnd => {
        val pid = new IntByReference
        u32.GetWindowThreadProcessId(hWnd, pid)
        pid.getValue
    }

    // PROCESS_QUERY_LIMITED_INFORMATION is enough for QueryFullProcessImageName (also a process
    // running as administrator, or of another user, when W10Wheel is not)
    @volatile var fullPathOfProcess: Int => Option[String] = processId => {
        val hProcess = k32.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, processId)
        if (hProcess == null)
            None
        else {
            try {
                val buffer = new Array[Char](1024)
                val size = new IntByReference(buffer.length)
                if (k32.QueryFullProcessImageName(hProcess, 0, buffer, size))
                    Some(new String(buffer, 0, size.getValue))
                else
                    None
            }
            finally {
                k32.CloseHandle(hProcess)
            }
        }
    }
}
