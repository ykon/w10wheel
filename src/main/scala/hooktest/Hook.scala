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

    // LLKHF_INJECTED (0x10), LLKHF_LOWER_IL_INJECTED (0x02, set with LLKHF_INJECTED): the bits are
    // tested, as for the mouse (Windows.isInjectedEvent). Not LLKHF_EXTENDED (0x01).
    private def isInjectedKey(info: KHookInfo): Boolean =
        (info.flags & 0x12) != 0

    private def procKeyboardEvent(nCode: Int, wParam: WPARAM, info: KHookInfo, callNextHook: () => LRESULT): LRESULT = {
        val keh = KEventHandler

        if (nCode < 0)
            callNextHook()
        // The keys of other software (injected, e.g. the auto-type of a password manager sending
        // Tab, or a macro) pass without being handled, as the mouse events of other software do:
        // the trigger key sent would start scroll mode and be suppressed (then the next key,
        // ending it). They do not change the flags of the physical keys either.
        else if (isInjectedKey(info))
            callNextHook()
        else if (ctx.isPassMode) {
            // The keys pressed in Pass Mode pass: so do their repeats and up after it is turned
            // off, even if they have become the trigger (see KEventHandler.keyDown). The up alone
            // of a key whose down was suppressed before Pass Mode is suppressed (LastFlags.init),
            // as the hook does for the buttons (Windows.dropLoneUp).
            val vk = info.vkCode
            wParam.intValue match {
                case WM_KEYDOWN | WM_SYSKEYDOWN =>
                    ctx.LastFlags.newKeyDown(vk)
                    ctx.LastFlags.resetLoneUpKey(vk)
                    ctx.LastFlags.setPassedKey(vk)
                    callNextHook()
                case WM_KEYUP | WM_SYSKEYUP if ctx.LastFlags.getAndReset_LoneUpKey(vk) =>
                    logger.debug(s"Pass Mode: suppress the up alone of a suppressed down: $vk")
                    new LRESULT(1)
                case WM_KEYUP | WM_SYSKEYUP =>
                    ctx.LastFlags.getAndReset_PassedKey(vk)
                    callNextHook()
                case _ =>
                    callNextHook()
            }
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

    // The error message of a failure, read right after it (another call could change the last error)
    private def getHookError(hhk: HHOOK): Option[String] =
        if (hhk == null) Some(Windows.getLastErrorMessage) else None

    // None if installed, else the error message
    def setMouseHook: Option[String] = {
        val hhk = Windows.setHook(mouseProc)
        val err = getHookError(hhk)
        mouseHhk = hhk
        err
    }

    // None if installed, else the error message
    def setKeyboardHook: Option[String] = {
        // Called on every properties load (Reload, switching properties) when the keyboard hook is
        // on. Remove the previous hook first: installing again would leave it installed
        // (unhookKeyboard only removes the last one). Installing anew also recovers a hook that
        // Windows removed silently because of a timeout (UnhookWindowsHookEx then just fails).
        unhookKeyboard
        val hhk = Windows.setHook(keyboardProc)
        val err = getHookError(hhk)
        keyboardHhk = hhk
        err
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

    // None if done, else the error message of the install (shown by Context, translated)
    def setOrUnsetKeyboardHook(b: Boolean): Option[String] = {
        if (b)
            setKeyboardHook
        else {
            unhookKeyboard
            None
        }
    }

    def unhook: Unit = {
        unhookMouse
        unhookKeyboard
    }
}