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

    // The button (0: left, 1: right, 2: middle, 3: X1, 4: X2) and whether it is a down, for the
    // checks of Windows (dropResent, dropLoneUp)
    private def buttonOf(msg: Int, info: HookInfo): Option[(Int, Boolean)] = {
        def x = if (Mouse.isXButton1(info.mouseData)) 3 else 4
        msg match {
            case WM_LBUTTONDOWN => Some((0, true))
            case WM_LBUTTONUP => Some((0, false))
            case WM_RBUTTONDOWN => Some((1, true))
            case WM_RBUTTONUP => Some((1, false))
            case WM_MBUTTONDOWN => Some((2, true))
            case WM_MBUTTONUP => Some((2, false))
            case WM_XBUTTONDOWN => Some((x, true))
            case WM_XBUTTONUP => Some((x, false))
            case _ => None
        }
    }

    private def procMouseEvent(wParam: WPARAM, info: HookInfo, callNextHook: () => LRESULT): LRESULT = {
        val eh = EventHandler

        if (ctx.isPassMode) {
            if (wParam.intValue == WM_MOUSEHWHEEL && procCommand(info))
                new LRESULT(1)
            else
                callNextHook()
        }
        else {
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
    }

    private def procMouse(nCode: Int, wParam: WPARAM, info: HookInfo): LRESULT = {
        val buttonEvent = if (nCode < 0) None else buttonOf(wParam.intValue, info)
        var passed = false

        // Passes the event on, unless it is an up without its down (Windows.dropLoneUp)
        val callNextHook = () => buttonEvent match {
            case Some((button, false)) if Windows.dropLoneUp(button, info) => new LRESULT(1)
            case _ =>
                passed = true
                Windows.callNextHook(mouseHhk, nCode, wParam, info.getPointer)
        }

        if (nCode < 0)
            callNextHook()
        else {
            // The handlers use the function of the event being handled. Restored after, in case a
            // hook call is handled within this one (e.g. a modal dialog shown by a handler).
            val outer = EventHandler.getCallNextHook
            EventHandler.setCallNextHook(callNextHook)
            try {
                // Before Pass Mode and the triggers: the resent downs and ups are checked whatever
                // handles them, in the order the application gets them
                buttonEvent match {
                    case Some((button, isDown)) if Windows.dropResent(button, isDown, info) => new LRESULT(1)
                    case _ =>
                        buttonEvent.foreach { case (button, isDown) => Windows.handlingButton(button, isDown, info) }
                        val res = procMouseEvent(wParam, info, callNextHook)
                        buttonEvent.foreach { case (button, isDown) => Windows.handledButton(button, isDown, info, passed) }
                        res
                }
            }
            finally {
                EventHandler.setCallNextHook(outer)
            }
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

    private def procKeyboardEvent(nCode: Int, wParam: WPARAM, info: KHookInfo, callNextHook: () => LRESULT): LRESULT = {
        val keh = KEventHandler

        if (nCode < 0)
            callNextHook()
        else if (ctx.isPassMode) {
            // The keys pressed in Pass Mode pass: so do their repeats and up after it is turned
            // off, even if they have become the trigger (see KEventHandler.keyDown)
            wParam.intValue match {
                case WM_KEYDOWN | WM_SYSKEYDOWN =>
                    ctx.LastFlags.newKeyDown(info.vkCode)
                    ctx.LastFlags.setPassedKey(info.vkCode)
                case WM_KEYUP | WM_SYSKEYUP =>
                    ctx.LastFlags.getAndReset_PassedKey(info.vkCode)
                case _ => ()
            }

            callNextHook()
        }
        else {
            wParam.intValue match {
                case WM_KEYDOWN | WM_SYSKEYDOWN  => keh.keyDown(info)
                case WM_KEYUP | WM_SYSKEYUP =>keh.keyUp(info)
                case _ => callNextHook()
            }
        }
    }

    private def procKeyboard(nCode: Int, wParam: WPARAM, info: KHookInfo): LRESULT = {
        val callNextHook = () => Windows.callNextHook(keyboardHhk, nCode, wParam, info.getPointer)
        // Restored after, as for the mouse (see procMouse)
        val outer = KEventHandler.getCallNextHook
        KEventHandler.setCallNextHook(callNextHook)
        try {
            procKeyboardEvent(nCode, wParam, info, callNextHook)
        }
        finally {
            KEventHandler.setCallNextHook(outer)
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