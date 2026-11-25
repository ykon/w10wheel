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
        // The keyboard hook is removed in Pass Mode (Context.setKeyboardHookOf), on this thread at
        // once: no key comes here then. Passed, in case one does.
        else if (ctx.isPassMode)
            callNextHook()
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

    // The hooks have been removed for the exit (unhook): not installed again after it (e.g. after a
    // resume, by SessionEvents, or at the end of a pause, if the session ended meanwhile: the loop
    // goes on after WM_ENDSESSION). The hooks are installed and removed on the hook thread only.
    @volatile private var closed = false
    def isClosed: Boolean = closed

    // None if installed, else the error message. At startup (W10Wheel.init).
    def setMouseHook: Option[String] = {
        if (closed)
            None
        else {
            val hhk = Windows.setHook(mouseProc)
            val err = getHookError(hhk)
            mouseHhk = hhk
            err
        }
    }

    // Installs the mouse hook again, to be first in the chain again (the newest hook is called
    // first): before the hooks installed after it (e.g. mouse software that installs its hook again
    // after a resume, then sees the buttons first and passes on only those it injects, which W10Wheel
    // ignores). The new hook is installed before the old one is removed (kept if that fails, None):
    // no event is handled in between, as the hooks are called on this thread only when it takes its
    // messages. Some(true): Windows had removed the old one already (e.g. for a timeout;
    // UnhookWindowsHookEx failed), so events may have been missed before.
    def reinstallMouseHook: Option[Boolean] = {
        val old = mouseHhk
        if (closed || old == null)
            None
        else {
            val hhk = Windows.setHook(mouseProc)
            if (hhk == null) {
                logger.warn(s"reinstallMouseHook: ${Windows.getLastErrorMessage}")
                None
            }
            else {
                mouseHhk = hhk
                Some(!Windows.unhook(old))
            }
        }
    }

    // What setOrUnsetKeyboardHook did
    sealed trait KeyboardHookResult
    object KeyboardHookResult {
        // Nothing changed, also when a replacement could not be installed (the old hook stays)
        case object Unchanged extends KeyboardHookResult
        // Installed where there was none
        case object Installed extends KeyboardHookResult
        // Replaced by a new one, installed before the old one was removed: no event was missed.
        // oldHookLost: Windows had removed the old one already (e.g. for a timeout; its
        // UnhookWindowsHookEx failed), so keys may have been pressed and released before with no
        // hook to see them (see KEventHandler.hookLost).
        case class Replaced(oldHookLost: Boolean) extends KeyboardHookResult
        // Removed: no keyboard events are seen any more
        case object Removed extends KeyboardHookResult
        // Could not be installed where there was none (the error message, shown by Context,
        // translated)
        case class Failed(err: String) extends KeyboardHookResult
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

    // Installs (b) or removes the keyboard hook. Installed, it is replaced by a new one, first in
    // the chain: on every properties load (Reload, switching properties), after a resume or an
    // unlock (Context.reinstallHooks). This also recovers a hook that Windows removed silently
    // because of a timeout. As the mouse hook (reinstallMouseHook): the new one is installed before
    // the old one is removed, with no event handled in between, and the old one stays if that fails.
    def setOrUnsetKeyboardHook(b: Boolean): KeyboardHookResult = {
        import KeyboardHookResult._
        logger.debug(s"setOrUnsetKeyboardHook: $b")
        val old = keyboardHhk
        // Not installed again after the hooks have been removed for the exit (as reinstallMouseHook)
        if (b && closed)
            Unchanged
        else if (b) {
            val hhk = Windows.setHook(keyboardProc)
            getHookError(hhk) match {
                case Some(err) if old != null =>
                    logger.warn(s"setOrUnsetKeyboardHook: not replaced: $err")
                    Unchanged
                case Some(err) => Failed(err)
                case None =>
                    keyboardHhk = hhk
                    if (old == null) Installed else Replaced(!Windows.unhook(old))
            }
        }
        else if (old != null) {
            unhookKeyboard
            Removed
        }
        else
            Unchanged
    }

    def unhook: Unit = {
        closed = true
        unhookMouse
        unhookKeyboard
    }
}