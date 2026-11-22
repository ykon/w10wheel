package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import com.sun.jna.Pointer
import com.sun.jna.platform.win32._
import com.sun.jna.platform.win32.WinDef._
import com.sun.jna.platform.win32.WinUser._

import win32ex.User32Ex._

import com.sun.jna.platform.win32.WinUser.{ MSLLHOOKSTRUCT => HookInfo }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

object Hook {
    private val ctx = Context
    private val logger = Logger.getLogger()

    @volatile private var mouseHhk: HHOOK = null
    @volatile private var keyboardHhk: HHOOK = null

    private def procCommand(info: HookInfo): Boolean = {
        if ((info.mouseData >>> 16) != 1)
            false
        else {
            logger.debug("receive (mouseData >>> 16) = 1")
            // Commands are sent with 32-bit values: anything larger is a normal wheel event
            val extra = info.dwExtraInfo.longValue()
            if ((extra >>> 32) != 0)
                false
            else
                W10Message.procMessage(extra.toInt)
        }
    }

    private def procMouse(nCode: Int, wParam: WPARAM, info: HookInfo): LRESULT = {
        val eh = EventHandler
        val callNextHook = () => Windows.callNextHook(mouseHhk, nCode, wParam, info.getPointer)
        eh.setCallNextHook(callNextHook)

        if (nCode < 0)
            return callNextHook()

        if (ctx.isPassMode) {
            return if (wParam.intValue == WM_MOUSEHWHEEL && procCommand(info))
                new LRESULT(1) else callNextHook()
        }

        wParam.intValue match {
            case WM_MOUSEMOVE => eh.move(info)
            case WM_LBUTTONDOWN => eh.leftDown(info)
            case WM_LBUTTONUP => eh.leftUp(info)
            case WM_RBUTTONDOWN => eh.rightDown(info)
            case WM_RBUTTONUP => eh.rightUp(info)
            case WM_MBUTTONDOWN => eh.middleDown(info)
            case WM_MBUTTONUP => eh.middleUp(info)
            case WM_XBUTTONDOWN => eh.xDown(info)
            case WM_XBUTTONUP => eh.xUp(info)
            case WM_MOUSEWHEEL => callNextHook()
            case WM_MOUSEHWHEEL =>
                if (procCommand(info)) new LRESULT(1) else callNextHook()
            case _ => callNextHook()
        }
    }

    // An exception must not escape a low-level hook procedure: JNA would return 0 without calling
    // the next hook. Pass the event on to the next hook instead.
    private val mouseProc = new LowLevelMouseProc() {
        override def callback(nCode: Int, wParam: WPARAM, info: HookInfo): LRESULT = {
            try {
                procMouse(nCode, wParam, info)
            }
            catch {
                case e: Exception =>
                    logger.warn(s"mouseProc: $e")
                    Windows.callNextHook(mouseHhk, nCode, wParam, info.getPointer)
            }
        }
    }

    private def procKeyboard(nCode: Int, wParam: WPARAM, info: KHookInfo): LRESULT = {
        val keh = KEventHandler
        val callNextHook = () => Windows.callNextHook(keyboardHhk, nCode, wParam, info.getPointer)
        keh.setCallNextHook(callNextHook)

        if (nCode < 0 || ctx.isPassMode)
            return callNextHook()

        wParam.intValue match {
            case WM_KEYDOWN | WM_SYSKEYDOWN  => keh.keyDown(info)
            case WM_KEYUP | WM_SYSKEYUP =>keh.keyUp(info)
            case _ => callNextHook()
        }
    }

    private val keyboardProc = new LowLevelKeyboardProc() {
        override def callback(nCode: Int, wParam: WPARAM, info: KHookInfo): LRESULT = {
            try {
                procKeyboard(nCode, wParam, info)
            }
            catch {
                case e: Exception =>
                    logger.warn(s"keyboardProc: $e")
                    Windows.callNextHook(keyboardHhk, nCode, wParam, info.getPointer)
            }
        }
    }

    def setMouseHook: Boolean = {
        mouseHhk = Windows.setHook(mouseProc)
        mouseHhk != null
    }

    def setKeyboardHook: Boolean = {
        // Called on every properties load (Reload, switching properties) when the keyboard hook is
        // on. Remove the previous hook first: installing again would leave it installed
        // (unhookKeyboard only removes the last one). Installing anew also recovers a hook that
        // Windows removed silently because of a timeout (UnhookWindowsHookEx then just fails).
        unhookKeyboard
        keyboardHhk = Windows.setHook(keyboardProc)
        keyboardHhk != null
    }

    def unhookMouse: Unit = {
        if (mouseHhk != null) {
            Windows.unhook(mouseHhk)
            mouseHhk = null
        }
    }

    def unhookKeyboard: Unit = {
        if (keyboardHhk != null) {
            Windows.unhook(keyboardHhk)
            keyboardHhk = null
        }
    }

    def setOrUnsetKeyboardHook(b: Boolean): Unit = {
        if (b) {
            if (!setKeyboardHook) {
                Dialog.errorMessage("Failed keyboard hook install: " + Windows.getLastErrorMessage)
            }
        }
        else
            unhookKeyboard
    }

    def unhook: Unit = {
        unhookMouse
        unhookKeyboard
    }
}