package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

//import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent._

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.Memory;
import com.sun.jna.ptr.IntByReference
import com.sun.jna.platform.win32._
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.WinDef._
import com.sun.jna.platform.win32.WinUser._

import win32ex._
import win32ex.User32Ex._

import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }
import com.sun.jna.platform.win32.WinUser.{ MSLLHOOKSTRUCT => HookInfo }

import java.util.Random

object Windows {
    private val ctx = Context
    private val logger = Logger.getLogger()
    private val u32 = User32.INSTANCE
    private val u32ex = User32Ex.INSTANCE
    private val k32 = Kernel32.INSTANCE
    //private val shcore = Shcore.INSTANCE


    private val rawInputSize = new RAWINPUT().size()

    private def procRawInput(lParam: LPARAM): Boolean = {
        val pcbSize = new IntByReference
        val cbSizeHeader = new RAWINPUTHEADER().size()

        def getRawInputData(data: Pointer) =
            u32ex.GetRawInputData(lParam.toPointer(), RID_INPUT, data, pcbSize, cbSizeHeader)

        // usFlags can have other bits with a relative move (e.g. MOUSE_MOVE_NOCOALESCE)
        def isMouseMoveRelative(ri: RAWINPUT) =
            ri.header.dwType == RIM_TYPEMOUSE && (ri.mouse.usFlags & MOUSE_MOVE_ABSOLUTE) == 0

        // Not smaller than RAWINPUT (a mouse input, the only device registered): it is read whole
        // from the buffer, which would be read past otherwise
        if (getRawInputData(null) == 0 && pcbSize.getValue >= rawInputSize) {
            // Freed after each WM_INPUT (as W10Wheel.NET does), not left to the GC (Cleaner):
            // there is one for each mouse report while scrolling
            val buf = new Memory(pcbSize.getValue)
            try {
                if (getRawInputData(buf) == pcbSize.getValue) {
                    val ri = new RAWINPUT(buf)

                    if (isMouseMoveRelative(ri)) {
                        val rm = ri.mouse
                        sendWheelRaw(rm.lLastX, rm.lLastY)
                        return true
                    }
                }
            }
            finally {
                buf.close()
            }
        }

        false
    }

    // The window that receives RawInput (WM_INPUT while scrolling), the end of the session, and the
    // suspend and resume, the lock and unlock (SessionEvents). On
    // the hook thread (as in the SWT version and W10Wheel.NET): WM_INPUT is handled between the hook
    // procedures, never while one changes the state of scroll mode (e.g. the scroll ended: no wheel
    // sent nor cursor changed after it). (The mode of the apps is watched in the registry: Theme.)
    private val windowProc = new WindowProc {
        override def callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT = {
            uMsg match {
                // Then DefWindowProc, also when handled: for RIM_INPUT (input in the foreground) the
                // system needs it to clean up (see WM_INPUT)
                case WM_INPUT =>
                    procRawInput(lParam)
                // Not at WM_QUERYENDSESSION (DefWindowProc allows the end): the shutdown or logoff
                // can still be canceled there, and W10Wheel stayed in the tray without working.
                // WM_ENDSESSION with wParam TRUE comes only when the session is actually ending.
                case WM_ENDSESSION =>
                    if (wParam.intValue != 0)
                        W10Wheel.procExit
                    return new LRESULT(0)
                // The suspend and resume, the lock and unlock (see SessionEvents.procMessage)
                case WM_POWERBROADCAST | WM_WTSSESSION_CHANGE =>
                    SessionEvents.procMessage(uMsg, wParam.intValue)
                case _ => {}
            }

            u32.DefWindowProc(hwnd, uMsg, wParam, lParam)
        }
    }
    
    private val CLASS_NAME = Context.PROGRAM_NAME + "_WM"

    // Explorer started again: the tray icon is added again by AWT (its hidden window)
    private val TaskbarCreated = u32.RegisterWindowMessage("TaskbarCreated")

    @volatile private var messageHwnd: HWND = null
    // null if it could not be made
    def getMessageWindow: HWND = messageHwnd

    // A top-level window (not message-only): WM_ENDSESSION, WM_POWERBROADCAST and
    // WM_WTSSESSION_CHANGE are sent to the top-level windows only.
    // Made on the hook thread before the hooks (W10Wheel.init): its messages are dispatched by the
    // loop of that thread (HookThread).
    //
    // As administrator, TaskbarCreated (sent by Explorer, not as administrator) is blocked by
    // Windows (UIPI) unless allowed: for the process (AWT does not allow it for its window, and the
    // tray icon would not come back after Explorer restarts).
    def createMessageWindow(): Unit = {
        if (!u32ex.ChangeWindowMessageFilter(TaskbarCreated, MSGFLT_ADD))
            logger.warn("ChangeWindowMessageFilter (TaskbarCreated): " + getLastErrorMessage)

        val wx = new WNDCLASSEX
        wx.lpszClassName = CLASS_NAME
        wx.lpfnWndProc = windowProc

        val hwnd =
            if (u32.RegisterClassEx(wx).intValue() != 0)
                u32.CreateWindowEx(0, CLASS_NAME, null, 0, 0, 0, 0, 0, null, null, null, null)
            else
                null

        if (hwnd == null)
            logger.warn("Failed create the message window: " + getLastErrorMessage)
        messageHwnd = hwnd
    }

    // false if the hook was not installed any more (e.g. removed by Windows for a timeout)
    def unhook(hhk: HHOOK): Boolean =
        u32.UnhookWindowsHookEx(hhk)

    def setHook(proc: LowLevelMouseProc) = {
        val hMod = k32.GetModuleHandle(null)
        u32.SetWindowsHookEx(WH_MOUSE_LL, proc, hMod, 0)
    }

    def setHook(proc: LowLevelKeyboardProc) = {
        val hMod = k32.GetModuleHandle(null)
        u32.SetWindowsHookEx(WH_KEYBOARD_LL, proc, hMod, 0)
    }

    def createInputArray(size: Int) =
        new INPUT().toArray(size).asInstanceOf[Array[INPUT]]

    private val rand = new Random

    // Positive (not zero): dwExtraInfo is pointer-sized, and a negative Int would be sign-extended
    private def createRandomNumber: Int =
        1 + rand.nextInt(Int.MaxValue - 1)

    private val resendTag = createRandomNumber
    // Not the same as resendTag: a click would be taken for another resend
    private val resendClickTag = {
        var res = createRandomNumber
        while (res == resendTag)
            res = createRandomNumber
        res
    }

    // LLMHF_INJECTED, LLMHF_LOWER_IL_INJECTED
    // https://msdn.microsoft.com/en-ca/library/windows/desktop/ms644970(v=vs.85).aspx
    // Events injected from a lower integrity level process have both bits set (flags = 3),
    // so test the bits instead of comparing the whole value.
    def isInjectedEvent(me: MouseEvent): Boolean =
        (me.info.flags & 0x3) != 0

    private def isInjected(info: HookInfo): Boolean =
        (info.flags & 0x3) != 0

    // The kind of a resent input and the ID of the physical press it is for, in the upper half of
    // dwExtraInfo (resendTag in the lower half): see dropResent. W10Wheel runs as a 64-bit
    // process only (x64), so dwExtraInfo has 64 bits.
    private val KIND_PLAIN = 0L   // passed as is (e.g. a down and up pair)
    private val KIND_DOWN = 1L    // a down resent alone (its up comes later)
    private val KIND_RELEASE = 2L // the up resent for a down resent alone

    private val PRESS_ID_MASK = 0xFFFFFF

    private def resendExtra(kind: Long, press: Int): Long = {
        val upper = (kind << 24) | (press & PRESS_ID_MASK).toLong
        (upper << 32) | resendTag.toLong
    }

    // dwExtraInfo is pointer-sized: compare all of it (the tag in the lower half for resends)
    private def isResendInfo(info: HookInfo): Boolean =
        (info.dwExtraInfo.longValue() & 0xFFFFFFFFL) == resendTag.toLong

    def isResendEvent(me: MouseEvent): Boolean =
        isResendInfo(me.info)

    def isResendClickEvent(me: MouseEvent): Boolean =
        me.info.dwExtraInfo.longValue() == resendClickTag.toLong

    // (kind, press) of a resent input
    private def resendKind(info: HookInfo): Option[(Long, Int)] = {
        if (isInjected(info) && isResendInfo(info)) {
            val upper = info.dwExtraInfo.longValue() >>> 32
            Some((upper >>> 24, (upper & PRESS_ID_MASK).toInt))
        }
        else
            None
    }

    // The physical presses and the downs resent alone (LR trigger) by button (0: left, 1: right,
    // 2: middle, 3: X1, 4: X2; only left and right are resent alone), as the hook sees them: in
    // the order the target application gets the inputs, whenever SendInput returns. Used on the
    // hook thread only.
    // pressId: the ID of the last physical down (see handlingButton); the waits and resends for it
    // carry it (pressIdOf).
    // upPassed: the physical up of that press has passed W10Wheel (e.g. in Pass Mode, or after
    // initState reset the flags).
    // downPassedId: the press whose down resent alone has reached the application, with no up
    // since (NO_PRESS if none).
    // downSuppressed: the physical down of that press did not pass (W10Wheel suppressed it).
    private val NO_PRESS = 0 // the IDs are 1 to PRESS_ID_MASK - 1
    private val pressId = Array.fill(5)(NO_PRESS)
    private val upPassed = Array.fill(5)(false)
    private val downPassedId = Array.fill(5)(NO_PRESS)
    private val downSuppressed = Array.fill(5)(false)
    // releaseQueuedId: the last press whose up has been resent (queued, see resendUp) for its down
    // resent alone: that down is not dropped if a later press has started before it is sent (e.g.
    // the sender busy with a click), it is released by that up
    private val releaseQueuedId = Array.fill(5)(NO_PRESS)
    private var lastPressId = 0

    // Called by the mouse hook for a button down or up before it is handled, also in Pass Mode:
    // true to drop it.
    // - A down resent alone for a press whose physical up has passed (or for an earlier press)
    //   would leave the button pressed. Not if its up has been resent (releaseQueuedId): it comes
    //   after, and the press would be lost.
    // - The up resent for a down resent alone that did not reach the application (dropped above,
    //   or not sent) would be an up without a down (e.g. a right up opens a context menu).
    def dropResent(button: Int, isDown: Boolean, info: HookInfo): Boolean = resendKind(info) match {
        case Some((KIND_DOWN, id)) if isDown && (id != pressId(button) || upPassed(button)) &&
                                      id != releaseQueuedId(button) =>
            logger.debug("dropResent: a down after its physical up")
            true
        case Some((KIND_RELEASE, id)) if !isDown && id != downPassedId(button) =>
            logger.debug("dropResent: an up for a down not passed")
            true
        case _ =>
            false
    }

    // Called by the mouse hook before handling a button down or up that is not dropped: a physical
    // down starts a new press
    def handlingButton(button: Int, isDown: Boolean, info: HookInfo): Unit = {
        if (isDown && !isInjected(info)) {
            lastPressId = lastPressId % (PRESS_ID_MASK - 1) + 1
            pressId(button) = lastPressId
            upPassed(button) = false
            downSuppressed(button) = false
        }
    }

    // Called by the mouse hook after handling a button down or up (passed: passed on)
    def handledButton(button: Int, isDown: Boolean, info: HookInfo, passed: Boolean): Unit = {
        if (isDown && !passed && !isInjected(info))
            downSuppressed(button) = true

        if (passed) {
            if (isDown) {
                resendKind(info) match {
                    case Some((KIND_DOWN, id)) => downPassedId(button) = id
                    case _ => ()
                }
            }
            else {
                downPassedId(button) = NO_PRESS
                if (!isInjected(info))
                    upPassed(button) = true
            }
        }

        // The physical up of the press is handled (suppressed or passed): the press is over. A
        // later up whose down the hook did not see (e.g. pressed on another desktop) is not taken
        // for an up without its down (dropLoneUp).
        if (!isDown && !isInjected(info))
            downSuppressed(button) = false
    }

    // Called by the mouse hook when a button up is about to be passed on (by the handlers, or in
    // Pass Mode): true to suppress it instead. If the physical down of its press was suppressed
    // and no down resent alone for it reached the application, the application has the button
    // released (or got a whole click instead): the up alone would be an up without a down (e.g. a
    // right up opens a context menu). The press is ended then: a down resent late for it is
    // dropped (dropResent).
    def dropLoneUp(button: Int, info: HookInfo): Boolean = {
        if (!isInjected(info) && downSuppressed(button) && downPassedId(button) != pressId(button)) {
            logger.debug("dropLoneUp: an up whose down did not reach the application")
            upPassed(button) = true
            true
        }
        else
            false
    }

    // The buttons' ups may have been missed (no hook saw them; the physical up reached the
    // application then): the session was locked or the system suspended (Context.inputLost), or
    // Windows had removed the mouse hook. The suppressed downs are forgotten, so that the up of a
    // press made meanwhile (which reached the application too) is not taken for an up without its
    // down (dropLoneUp). A down resent alone still queued for a press whose up has not been resent
    // (releaseQueuedId) is dropped when it comes (as after a passed up, dropResent), or it would
    // reach the application alone and the button stay pressed (e.g. the LR trigger's wait timed
    // out, then the up missed); one whose up is queued passes with it (the click is whole). The
    // press IDs are kept (resent events in flight are still told). On the hook thread.
    def forgetPresses(): Unit = {
        for (i <- downSuppressed.indices) {
            downSuppressed(i) = false
            if (releaseQueuedId(i) != pressId(i))
                upPassed(i) = true
        }
    }

    // dropLoneUp for a left or right up (on the hook thread)
    def dropLoneUpOf(up: MouseEvent): Boolean = up match {
        case LeftUp(info) => dropLoneUp(0, info)
        case RightUp(info) => dropLoneUp(1, info)
        case _ => false
    }

    // The ID of the current press of the button of a left or right event (on the hook thread)
    def pressIdOf(me: MouseEvent): Int =
        pressId(getButton(me))

    def setInput(msg: INPUT, pt: POINT, data: Int, flags: Int, time: Int, extra: Long): Unit = {
        msg.`type` = new DWORD(INPUT.INPUT_MOUSE)
        msg.input.setType("mi")

        val mi = msg.input.mi
        mi.dx = new LONG(pt.x)
        mi.dy = new LONG(pt.y)
        mi.mouseData = new DWORD(data)
        mi.dwFlags = new DWORD(flags)
        mi.time = new DWORD(time)
        mi.dwExtraInfo = new ULONG_PTR(extra)
    }

    private def createInput(pt: POINT, data: Int, flags: Int, time: Int, extra: Long): INPUT = {
        val input = createInputArray(1)(0)
        setInput(input, pt, data, flags, time, extra)
        input
    }

    // A wheel event of scroll mode (see InputSender.Wheel)
    def sendInput(pt: POINT, data: Int, flags: Int, time: Int, extra: Int): Unit = {
        // It has scrolled: the press of the drag trigger is a drag (see EventHandler.dragStart)
        ctx.setScrolled()
        InputSender.enqueue(InputSender.Wheel, Array(createInput(pt, data, flags, time, extra)))
    }

    // false if SendInput failed
    def sendInputDirect(pt: POINT, data: Int, flags: Int, time: Int, extra: Int): Boolean = {
        val input = createInputArray(1)
        setInput(input(0), pt, data, flags, time, extra)
        u32.SendInput(new DWORD(1), input, input(0).size()).intValue() == 1
    }

    private var vwCount = 0
    private var hwCount = 0

    sealed abstract class MoveDirection
    case class Plus() extends MoveDirection
    case class Minus() extends MoveDirection
    case class Zero() extends MoveDirection

    private var vLastMove: MoveDirection = Zero()
    private var hLastMove: MoveDirection = Zero()

    private var vWheelMove = 0
    private var hWheelMove = 0
    private var quickTurn = false

    def startWheelCount: Unit = {
        vwCount = if (ctx.isQuickFirst) vWheelMove else vWheelMove / 2
        hwCount = if (ctx.isQuickFirst) hWheelMove else hWheelMove / 2

        vLastMove = Zero()
        hLastMove = Zero()
    }

    import scala.annotation.tailrec

    // d == Not Zero
    private def getNearestIndex(d: Int, thr: Array[Int]): Int = {
        val ad = Math.abs(d)

        @tailrec
        def loop(i: Int): Int = {                
            thr(i) match {
                case n if n == ad => i
                case n if n > ad =>
                    // i = 0: smaller than the first threshold (custom tables may not start with 1)
                    if (i == 0) i
                    else if (n - ad < Math.abs(thr(i - 1) - ad)) i else i - 1
                case _ =>
                    if (i != thr.length - 1) loop(i + 1) else i
            }
        }

        loop(0)
    }

    private var accelThreshold: Array[Int] = null
    private var accelMultiplier: Array[Double] = null
    private def passInt(d: Int) = d
    private var addAccelIf: Int => Int = passInt

    private def addAccel(d: Int): Int = {
        val i = getNearestIndex(d, accelThreshold)
        (d * accelMultiplier(i)).toInt
    }

    private def reverseIfFlip(d: Int) = -d
    private var reverseIfV: Int => Int = passInt
    private var reverseIfH: Int => Int = reverseIfFlip
    private var reverseIfDelta: Int => Int = reverseIfFlip
    private var wheelDelta = 0

    private def getVWheelDelta(input: Int) = {
        val delta = wheelDelta
        val res = if (input > 0) -delta else delta

        reverseIfDelta(res)
    }

    private def getHWheelDelta(input: Int) = {
        -(getVWheelDelta(input))
    }

    private def isTurnMove(last: MoveDirection, d: Int) = last match {
        case Plus() => d < 0
        case Minus() => d > 0
        case _ => false
    }

    // On a turn, the move left from the other direction does not count: start counting again as
    // at the scroll start (quickTurn sends a notch at once, like quickFirst).
    // A move larger than wheelMove sends a notch for each wheelMove.
    private def sendRealVWheel(pt: POINT, d: Int): Unit = {
        def send = sendInput(pt, getVWheelDelta(d), MOUSEEVENTF_WHEEL, 0, 0)

        if (isTurnMove(vLastMove, d))
            vwCount = if (quickTurn) vWheelMove else vWheelMove / 2

        vwCount += Math.abs(d)

        while (vWheelMove > 0 && vwCount >= vWheelMove) {
            send
            vwCount -= vWheelMove
        }

        vLastMove = if (d > 0) Plus() else Minus()
    }

    // Not a delta of 0 (e.g. a multiplier smaller than 1 for a small move): it would not scroll
    private def sendDirectVWheel(pt: POINT, d: Int): Unit = {
        //println(s"d: $d")
        val delta = reverseIfV(addAccelIf(d))
        if (delta != 0)
            sendInput(pt, delta, MOUSEEVENTF_WHEEL, 0, 0)
    }

    private def sendRealHWheel(pt: POINT, d: Int): Unit = {
        def send = sendInput(pt, getHWheelDelta(d), MOUSEEVENTF_HWHEEL, 0, 0)

        if (isTurnMove(hLastMove, d))
            hwCount = if (quickTurn) hWheelMove else hWheelMove / 2

        hwCount += Math.abs(d)

        while (hWheelMove > 0 && hwCount >= hWheelMove) {
            send
            hwCount -= hWheelMove
        }

        hLastMove = if (d > 0) Plus() else Minus()
    }

    private def sendDirectHWheel(pt: POINT, d: Int): Unit = {
        val delta = reverseIfH(addAccelIf(d))
        if (delta != 0)
            sendInput(pt, delta, MOUSEEVENTF_HWHEEL, 0, 0)
    }

    private var sendVWheel: (POINT, Int) => Unit = sendDirectVWheel
    private var sendHWheel: (POINT, Int) => Unit = sendDirectHWheel

    sealed abstract class VHDirection
    case class Vertical() extends VHDirection
    case class Horizontal() extends VHDirection
    case class NonDirection() extends VHDirection

    //private var vhDirection: VHDirection = NonDirection()
    private var fixedVHD: VHDirection = NonDirection()
    private var latestVHD: VHDirection = NonDirection()

    private def initFuncs: Unit = {
        addAccelIf = if (ctx.isAccelTable) addAccel else passInt
        swapIf = if (ctx.isSwapScroll) swapIfOn else swapIfOff

        reverseIfV = if (ctx.isReverseScroll) passInt else reverseIfFlip
        reverseIfH = if (ctx.isReverseScroll) reverseIfFlip else passInt

        sendVWheel = if (ctx.isRealWheelMode) sendRealVWheel else sendDirectVWheel
        sendHWheel = if (ctx.isRealWheelMode) sendRealHWheel else sendDirectHWheel

        sendWheelIf = if (ctx.isHorizontalScroll && ctx.isVhAdjusterMode) sendWheelVHA else sendWheelStd
    }

    private def initAccelTable: Unit = {
        accelThreshold = ctx.getAccelThreshold
        accelMultiplier = ctx.getAccelMultiplier
    }

    private def initRealWheelMode: Unit = {
        vWheelMove = ctx.getVWheelMove
        hWheelMove = ctx.getHWheelMove
        quickTurn = ctx.isQuickTurn
        wheelDelta = ctx.getWheelDelta
        reverseIfDelta = if (ctx.isReverseScroll) reverseIfFlip else passInt

        startWheelCount
    }

    private def initVhAdjusterMode: Unit = {
        //vhDirection = NonDirection()
        fixedVHD = NonDirection()
        latestVHD = NonDirection()
        switchingThreshold = ctx.getSwitchingThreshold
        switchVHDif = if (ctx.isVhAdjusterSwitching) switchVHD else switchVHDifNone
    }

    private def initStdMode: Unit = {
        verticalThreshold = ctx.getVerticalThreshold
        horizontalThreshold = ctx.getHorizontalThreshold
        sendWheelStdIfHorizontal = if (ctx.isHorizontalScroll) sendWheelStdHorizontal else sendWheelStdNone
    }

    def initScroll: Unit = {
        scrollStartPoint = ctx.getScrollStartPoint
        initFuncs

        if (ctx.isAccelTable)
            initAccelTable
        if (ctx.isRealWheelMode)
            initRealWheelMode

        if (ctx.isVhAdjusterMode)
            initVhAdjusterMode
        else
            initStdMode
    }
    
    private def changeCursorVHD(vhd: VHDirection): Unit = vhd match {
        case Vertical() => {
            if (ctx.isCursorChange) changeCursorV
        }
        case Horizontal() => {
            if (ctx.isCursorChange) changeCursorH
        }
        case _ => ()
    }

    private def getFirstVHD(adx: Int, ady: Int): VHDirection = {
        val mthr = ctx.getFirstMinThreshold
        if (adx > mthr || ady > mthr) {
            val y = if (ctx.isFirstPreferVertical) ady * 2 else ady
            if (y >= adx) Vertical() else Horizontal()
        }
        else
            NonDirection()
    }

    private var switchingThreshold = 0

    private def switchVHD(adx: Int, ady: Int): VHDirection = {
        val sthr = switchingThreshold
        if (ady > sthr)
            Vertical()
        else if (adx > sthr)
            Horizontal()
        else
            NonDirection()
    }

    private def switchVHDifNone(adx: Int, ady: Int): VHDirection = fixedVHD
    private var switchVHDif: (Int, Int) => VHDirection = switchVHD

    private def sendWheelVHA(wspt: POINT, dx: Int, dy: Int): Unit = {
        val adx = Math.abs(dx)
        val ady = Math.abs(dy)
        
        val curVHD = fixedVHD match {
            case NonDirection() => {
                fixedVHD = getFirstVHD(adx, ady)
                fixedVHD
            }
            case _ => switchVHDif(adx, ady)
        }
        
        if (curVHD != NonDirection() && curVHD != latestVHD) {
            changeCursorVHD(curVHD)
            latestVHD = curVHD
        }

        latestVHD match {
            case Vertical() => if (dy != 0) sendVWheel(wspt, dy)
            case Horizontal() => if (dx != 0) sendHWheel(wspt, dx)
            case _ => ()
        }
    }

    private var verticalThreshold = 0
    private var horizontalThreshold = 0

    private def sendWheelStdHorizontal(wspt: POINT, dx: Int, dy: Int): Unit = {
        if (Math.abs(dx) > horizontalThreshold)
            sendHWheel(wspt, dx)
    }

    private def sendWheelStdNone(wspt: POINT, dx: Int, dy: Int): Unit = {}
    private var sendWheelStdIfHorizontal: (POINT, Int, Int) => Unit = sendWheelStdHorizontal
    private var sendWheelIf: (POINT, Int, Int) => Unit = sendWheelStd

    private def sendWheelStd(wspt: POINT, dx: Int, dy: Int): Unit = {
        if (Math.abs(dy) > verticalThreshold)
            sendVWheel(wspt, dy)

        sendWheelStdIfHorizontal(wspt, dx, dy)
    }

    private def swapIfOff(x: Int, y: Int) = (x, y)
    private def swapIfOn(x: Int, y: Int) = (y, x)
    private var swapIf: (Int, Int) => (Int, Int) = swapIfOff

    // Set by initScroll, before Context.Scroll sets scroll mode (a volatile write): read after
    // checking scroll mode, it is the one of the current scroll
    private var scrollStartPoint: (Int, Int) = (0, 0)

    def sendWheel(movePt: POINT): Unit = {
         //logger.debug("movePT: " + movePt.x + "," + movePt.y);

        val (sx, sy) = scrollStartPoint
        val (dx, dy) = swapIf(movePt.x - sx, movePt.y - sy)

        sendWheelIf(new POINT(sx, sy), dx, dy)
    }

    def sendWheelRaw(x: Int, y: Int): Unit = {
        //logger.debug("sendWheelRaw: " + x + "," + y)

        // x and y are relative moves (they were compared with the start point, so e.g. a vertical
        // move was dropped when the scroll started at the left edge). WM_INPUT already queued is
        // still handled after scroll mode has ended (RawInput unregistered), and it can come before
        // scroll mode is set: RawInput is registered first (Context.Scroll.start, maybe on the
        // EventWaiter thread). The start point is read after checking scroll mode (see above).
        if (ctx.isScrollMode && (x != 0 || y != 0)) {
            val (sx, sy) = scrollStartPoint
            val (dx, dy) = swapIf(x, y)
            sendWheelIf(new POINT(sx, sy), dx, dy)
        }
    }

    private def createClick(mc: MouseClick) = {
        val extra = resendClickTag
        val input = createInputArray(2)

        def set(mouseData: Int, down: Int, up: Int): Unit = {
            setInput(input(0), mc.info.pt, mouseData, down, 0, extra)
            setInput(input(1), mc.info.pt, mouseData, up, 0, extra)
        }

        mc match {
            case LeftClick(_) => set(0, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP)
            case RightClick(_) => set(0, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP)
            case MiddleClick(_) => set(0, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP)
            case X1Click(_) => set(XBUTTON1, MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP)
            case X2Click(_) => set(XBUTTON2, MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP)
        }
        input
    }

    def resendClick(mc: MouseClick): Unit = {
        InputSender.enqueue(InputSender.Click, createClick(mc))
    }

    // Left (0) or right (1): only their downs and ups are resent alone
    def getButton(me: MouseEvent): Int = me match {
        case LeftDown(_) | LeftUp(_) => 0
        case RightDown(_) | RightUp(_) => 1
        case _ => throw new IllegalArgumentException(s"Not a left/right event: ${me.name}")
    }

    private def createResendDown(me: MouseEvent, extra: Long): INPUT = me match {
        case LeftDown(info) => createInput(info.pt, 0, MOUSEEVENTF_LEFTDOWN, 0, extra)
        case RightDown(info) => createInput(info.pt, 0, MOUSEEVENTF_RIGHTDOWN, 0, extra)
        case _ => throw new IllegalArgumentException(s"Not a left/right down: ${me.name}")
    }

    private def createResendUp(me: MouseEvent, extra: Long): INPUT = me match {
        case LeftUp(info) => createInput(info.pt, 0, MOUSEEVENTF_LEFTUP, 0, extra)
        case RightUp(info) => createInput(info.pt, 0, MOUSEEVENTF_RIGHTUP, 0, extra)
        case _ => throw new IllegalArgumentException(s"Not a left/right up: ${me.name}")
    }

    // The functions taking a generation and a press are for EventWaiter, which resends on its own
    // thread some time after the physical down: it passes the Pass Mode generation and the press
    // (pressIdOf) recorded when the physical down was received (EventWaiter.start)

    // A down resent alone: its up is resent later by resendUp
    def resendDown(generation: Int, press: Int, me: MouseEvent): Unit = {
        val input = createResendDown(me, resendExtra(KIND_DOWN, press))
        InputSender.enqueue(generation, InputSender.Down(getButton(me), press), Array(input))
    }

    // The up of a down resent alone (resendDown), for the physical up (suppressed) of the current
    // press: called on the hook thread. Sent only if that down was sent (InputSender), and passed
    // only if it reached the application (dropResent).
    def resendUp(me: MouseEvent): Unit = {
        val press = pressIdOf(me)
        releaseQueuedId(getButton(me)) = press
        val input = createResendUp(me, resendExtra(KIND_RELEASE, press))
        InputSender.enqueue(InputSender.Release(getButton(me), press), Array(input))
    }

    // Resends a down and an up (at different points). The same button's down and up are sent as
    // one batch, like resendClick: 10 ms apart, the up with retries.
    def resendDownUp(generation: Int, press: Int, down: MouseEvent, up: MouseEvent): Unit = {
        val plain = resendExtra(KIND_PLAIN, NO_PRESS)
        (down, up) match {
            case (LeftDown(_), LeftUp(_)) | (RightDown(_), RightUp(_)) =>
                InputSender.enqueue(generation, InputSender.Click,
                    Array(createResendDown(down, plain), createResendUp(up, plain)))
            case _ =>
                // Different buttons: not a pair. The other button's down was not resent alone (the
                // up of such a down is resent by checkResentDown before the wait is offered): it
                // passed W10Wheel (e.g. pressed in Pass Mode), so its up is always passed.
                resendDown(generation, press, down)
                InputSender.enqueue(generation, InputSender.Up, Array(createResendUp(up, plain)))
        }
    }

    // https://github.com/java-native-access/jna/blob/master/contrib/platform/src/com/sun/jna/platform/win32/Kernel32Util.java
    private def loadCursor(id: Int) = {
        val id_ptr = new Pointer(id)
        u32ex.LoadImageW(null, id_ptr, IMAGE_CURSOR, 0, 0, LR_DEFAULTSIZE | LR_SHARED)
    }

    private val CURSOR_V = loadCursor(IDC_SIZENS)
    private val CURSOR_H = loadCursor(IDC_SIZEWE)

    // Whether the system cursors have been changed since they were restored: restoring reloads
    // them all (SPI_SETCURSORS), so only when they have been changed (e.g. not for a click of the
    // drag trigger), and whatever the setting is now (it may have been changed while scrolling)
    @volatile private var cursorChanged = false

    def changeCursor(hCur: Pointer) = {
        cursorChanged = true
        val icon = new HICON(hCur)
        Host.setSystemCursor(icon, OCR_NORMAL)
        Host.setSystemCursor(icon, OCR_IBEAM)
        Host.setSystemCursor(icon, OCR_HAND)
    }

    def changeCursorV: Unit =
        changeCursor(CURSOR_V)

    def changeCursorH: Unit =
        changeCursor(CURSOR_H)

    //val SPIF_UPDATEINIFILE = 0x01;
    //val SPIF_SENDCHANGE = 0x02;
    //val SPIF_SENDWININICHANGE = 0x02;

    def restoreCursor: Boolean = {
        //val fWinIni = if (!sendChange) 0 else (SPIF_UPDATEINIFILE | SPIF_SENDWININICHANGE)
        if (cursorChanged) {
            val res = Host.restoreSystemCursors()
            if (res) cursorChanged = false
            res
        }
        else
            true
    }

    private def getAsyncKeyState(vKey: Int) =
        (Host.getAsyncKeyState(vKey) & 0x8000) != 0  // most significant bit: the key is down

    def getShiftState = getAsyncKeyState(VK_SHIFT)
    def getCtrlState = getAsyncKeyState(VK_CONTROL)
    def getAltState = getAsyncKeyState(VK_MENU)
    def getLeftState = getAsyncKeyState(VK_LBUTTON)
    def getRightState = getAsyncKeyState(VK_RBUTTON)
    def getEscState = getAsyncKeyState(VK_ESCAPE)
    // Whether the key is down now (the state of the keyboard, not of the messages taken so far)
    def isKeyDown(vk: Int) = getAsyncKeyState(vk)

    sealed trait Priority {
        def name = this.getClass.getSimpleName
    }
    case class Normal() extends Priority
    case class AboveNormal() extends Priority
    case class High() extends Priority

    def getPriority(name: String) = name match {
        case DataID.High => High()
        case DataID.AboveNormal | "Above Normal" => AboveNormal()
        case DataID.Normal => Normal()
    }

    def setPriority(p: Priority) = {
        val process = k32.GetCurrentProcess()
        def setPriorityClass(pc: DWORD) = k32.SetPriorityClass(process, pc)

        p match {
            case Normal() => setPriorityClass(Kernel32.NORMAL_PRIORITY_CLASS)
            case AboveNormal() => setPriorityClass(Kernel32.ABOVE_NORMAL_PRIORITY_CLASS)
            case High() => setPriorityClass(Kernel32.HIGH_PRIORITY_CLASS)
        }
    }

    def getCursorPos = {
        val pos = new POINT
        Host.getCursorPos(pos)
        pos
    }

    private def createRawInputDevice =
        new RAWINPUTDEVICE().toArray(1).asInstanceOf[Array[RAWINPUTDEVICE]]

    private def registerMouseRawInputDevice(dwFlags: Int, hwnd: HWND): Boolean = {
        val rid = createRawInputDevice

        rid(0).usUsagePage = HID_USAGE_PAGE_GENERIC
        rid(0).usUsage = HID_USAGE_GENERIC_MOUSE
        rid(0).dwFlags = dwFlags
        rid(0).hwndTarget = hwnd

        Host.registerRawInputDevices(rid)
    }
    
    // The error of the last native call on this thread, as JNA saved it right after the call
    // (GetLastError called through JNA could get the error of another call)
    def getLastErrorCode: Int =
        com.sun.jna.Native.getLastError()

    def getLastErrorMessage: String =
        Kernel32Util.formatMessage(getLastErrorCode)

    // Called by Context.Scroll under its lock, maybe on the EventWaiter thread (LR trigger): no
    // dialog, it would block the lock that the hook waits for (no mouse input, the dialog could
    // not be closed). Returns whether it succeeded: scroll
    // mode does not start if it failed.
    def registerRawInput: Boolean = {
        val res = registerMouseRawInputDevice(RIDEV_INPUTSINK, messageHwnd)
        if (!res)
            logger.warn("Failed register RawInput: " + getLastErrorMessage)
        res
    }

    def unregisterRawInput: Boolean = {
        val res = registerMouseRawInputDevice(RIDEV_REMOVE, null)
        if (!res)
            logger.warn("Failed unregister RawInput: " + getLastErrorMessage)
        res
    }

    private def isExcludedProcess(processId: Int): Boolean =
        Host.fullPathOfProcess(processId).exists(ctx.isExcludedProgram)

    // Whether the window at pt (of the hook: physical coordinates, as WindowFromPoint takes them,
    // the JVM being per-monitor DPI aware) is of an excluded program (Context.isExcludedProgram),
    // the one the input goes to. The program of its top-level window too, if it is another: a child
    // window can be of another process, e.g. the content of WebView2 (msedgewebview2.exe) in the
    // window of an app. Not the UWP (Store) apps: the window found is often of
    // ApplicationFrameHost.exe, not of the app (said as not supported; their .exe cannot be kept
    // either: its path changes at each update). It runs in the hook, at a down of a trigger only:
    // with no program excluded (the default), nothing is looked up.
    def isExcludedWindowAt(pt: POINT): Boolean = {
        if (!ctx.hasExcludedPrograms)
            false
        else {
            val hWnd = Host.windowFromPoint(pt)
            if (hWnd == null)
                false
            else {
                val pid = Host.processIdOfWindow(hWnd)
                if (pid != 0 && isExcludedProcess(pid))
                    true
                else {
                    val root = Host.rootWindow(hWnd)
                    val rootPid = if (root == null || root == hWnd) pid else Host.processIdOfWindow(root)
                    rootPid != pid && rootPid != 0 && isExcludedProcess(rootPid)
                }
            }
        }
    }

    // For the keyboard trigger: the window under the cursor, where it scrolls
    def isExcludedWindowAtCursor: Boolean = {
        if (!ctx.hasExcludedPrograms)
            false
        else {
            val pt = new POINT
            Host.getCursorPos(pt) && isExcludedWindowAt(pt)
        }
    }
}