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
import win32ex.Kernel32Ex._

import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

import java.util.Random

object Windows {
    private val ctx = Context
    private val logger = Logger.getLogger()
    private val u32 = User32.INSTANCE
    private val u32ex = User32Ex.INSTANCE
    private val k32 = Kernel32.INSTANCE
    private val k32ex = Kernel32Ex.INSTANCE
    //private val shcore = Shcore.INSTANCE

    // https://github.com/EsotericSoftware/clippy/blob/master/src/com/esotericsoftware/clippy/Tray.java
    private val TaskbarCreated = u32.RegisterWindowMessage("TaskbarCreated")

    private def procRawInput(lParam: LPARAM): Boolean = {
        val pcbSize = new IntByReference
        val cbSizeHeader = new RAWINPUTHEADER().size()

        def getRawInputData(data: Pointer) =
            u32ex.GetRawInputData(lParam.toPointer(), RID_INPUT, data, pcbSize, cbSizeHeader)

        def isMouseMoveRelative(ri: RAWINPUT) =
            ri.header.dwType == RIM_TYPEMOUSE && ri.mouse.usFlags == MOUSE_MOVE_RELATIVE

        if (getRawInputData(null) == 0) {
            val buf = new Memory(pcbSize.getValue)
            if (getRawInputData(buf) == pcbSize.getValue) {
                val ri = new RAWINPUT(buf)

                if (isMouseMoveRelative(ri)) {
                    val rm = ri.mouse
                    sendWheelRaw(rm.lLastX, rm.lLastY)
                    return true
                }
            }
        }

        false
    }

    private val windowProc = new WindowProc {
        override def callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT = {
            uMsg match {
                case WM_INPUT => {
                    if (procRawInput(lParam))
                        return new LRESULT(0)
                }
                case WM_QUERYENDSESSION =>
                    W10Wheel.procExit
                    return new LRESULT(0)
                case TaskbarCreated => {
                    logger.debug("TaskbarCreated")
                    Context.resetSystemTray
                    return new LRESULT(0)
                }
                case _ => {}
            }

            u32.DefWindowProc(hwnd, uMsg, wParam, lParam)
        }
    }
    
    private val CLASS_NAME = Context.PROGRAM_NAME + "_WM"

    private val messageHwnd: HWND = {
        val wx = new WNDCLASSEX
        wx.lpszClassName = CLASS_NAME
        wx.lpfnWndProc = windowProc

        if (u32.RegisterClassEx(wx).intValue() != 0) {
            val hwnd = u32.CreateWindowEx(0, CLASS_NAME, null, 0, 0, 0, 0, 0, null, null, null, null)
            u32ex.ChangeWindowMessageFilterEx(hwnd, TaskbarCreated, MSGFLT_ALLOW, null);

            hwnd
        }
        else
            null
    }

    // window message therad
    private val wmThread = new Thread(() => {
        val msg = new MSG
        while (u32.GetMessage(msg, null, 0, 0) > 0) {
            u32.TranslateMessage(msg)
            u32.DispatchMessage(msg)
        }
    })
    wmThread.setDaemon(true)
    wmThread.start

    //private val inputQueue = new ArrayBlockingQueue[Array[INPUT]](128, true)
    private val inputQueue = new LinkedBlockingQueue[Array[INPUT]]()

    private val senderThread = new Thread(() =>
        while (true) {
            val msgs = inputQueue.take()
            u32.SendInput(new DWORD(msgs.length), msgs, msgs(0).size())
        }
    )
    senderThread.setDaemon(true)
    senderThread.start

    def unhook(hhk: HHOOK) {
        u32.UnhookWindowsHookEx(hhk)
    }

    def setHook(proc: LowLevelMouseProc) = {
        val hMod = k32.GetModuleHandle(null)
        u32.SetWindowsHookEx(WH_MOUSE_LL, proc, hMod, 0)
    }

    def setHook(proc: LowLevelKeyboardProc) = {
        val hMod = k32.GetModuleHandle(null)
        u32.SetWindowsHookEx(WH_KEYBOARD_LL, proc, hMod, 0)
    }

    def callNextHook(hhk: HHOOK, nCode: Int, wParam: WPARAM, ptr: Pointer): LRESULT = {
        val peer = Pointer.nativeValue(ptr)
        u32.CallNextHookEx(hhk, nCode, wParam, new LPARAM(peer))
    }

    def createInputArray(size: Int) =
        new INPUT().toArray(size).asInstanceOf[Array[INPUT]]

    private val rand = new Random

    // Not Zero
    private def createRandomNumber: Int = {
        var res: Int = 0

        while (res == 0)
            res = rand.nextInt

        res
    }

    private val resendTag = createRandomNumber
    private val resendClickTag = createRandomNumber

    // LLMHF_INJECTED, LLMHF_LOWER_IL_INJECTED
    // https://msdn.microsoft.com/en-ca/library/windows/desktop/ms644970(v=vs.85).aspx
    def isInjectedEvent(me: MouseEvent): Boolean =
        me.info.flags == 1 || me.info.flags == 2

    def isResendEvent(me: MouseEvent): Boolean =
        me.info.dwExtraInfo.intValue() == resendTag

    def isResendClickEvent(me: MouseEvent): Boolean =
        me.info.dwExtraInfo.intValue() == resendClickTag

    def setInput(msg: INPUT, pt: POINT, data: Int, flags: Int, time: Int, extra: Int) {
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

    def sendInput(pt: POINT, data: Int, flags: Int, time: Int, extra: Int) {
        val input = createInputArray(1)
        setInput(input(0), pt, data, flags, time, extra)

        try {
            //lib.SendInput(new DWORD(1), input, input(0).size())
            inputQueue.add(input)
        }
        catch {
            case e: Exception => logger.warn(e.toString())
        }
    }

    def sendInputDirect(pt: POINT, data: Int, flags: Int, time: Int, extra: Int) {
        val input = createInputArray(1)
        setInput(input(0), pt, data, flags, time, extra)
        u32.SendInput(new DWORD(1), input, input(0).size())
    }

    def sendInput(msgs: Array[INPUT]) {
        try {
            //lib.SendInput(new DWORD(msgs.length), msgs, msgs(0).size())
            inputQueue.add(msgs)
        }
        catch {
            case e: Exception => logger.warn(e.toString())
        }
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

    def startWheelCount {
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
                    if (n - ad < Math.abs(thr(i - 1) - ad)) i else i - 1
                case _ =>
                    if (i != thr.length - 1) loop(i + 1) else i
            }
        }

        loop(0)
    }

    private var accelThreshold: Array[Int] = null
    private var accelMultiplier: Array[Double] = null
    private def passInt(d: Int) = d
    private var addAccelIf = passInt _

    private def addAccel(d: Int): Int = {
        val i = getNearestIndex(d, accelThreshold)
        (d * accelMultiplier(i)).toInt
    }

    private def reverseIfFlip(d: Int) = -d
    private var reverseIfV = passInt _
    private var reverseIfH = reverseIfFlip _
    private var reverseIfDelta = reverseIfFlip _
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

    private def sendRealVWheel(pt: POINT, d: Int) {
        def send = sendInput(pt, getVWheelDelta(d), MOUSEEVENTF_WHEEL, 0, 0)
        vwCount += Math.abs(d)

        if (quickTurn && isTurnMove(vLastMove, d))
            send
        else if (vwCount >= vWheelMove) {
            send
            vwCount -= vWheelMove
        }

        vLastMove = if (d > 0) Plus() else Minus()
    }

    private def sendDirectVWheel(pt: POINT, d: Int) {
        //println(s"d: $d")
        sendInput(pt, reverseIfV(addAccelIf(d)),  MOUSEEVENTF_WHEEL, 0, 0)
    }

    private def sendRealHWheel(pt: POINT, d: Int) {
        def send = sendInput(pt, getHWheelDelta(d), MOUSEEVENTF_HWHEEL, 0, 0)
        hwCount += Math.abs(d)

        if (quickTurn && isTurnMove(hLastMove, d))
            send
        else if (hwCount >= hWheelMove) {
            send
            hwCount -= hWheelMove
        }

        hLastMove = if (d > 0) Plus() else Minus()
    }

    private def sendDirectHWheel(pt: POINT, d: Int) {
        sendInput(pt, reverseIfH(addAccelIf(d)), MOUSEEVENTF_HWHEEL, 0, 0)
    }

    private var sendVWheel = sendDirectVWheel _
    private var sendHWheel = sendDirectHWheel _

    sealed abstract class VHDirection
    case class Vertical() extends VHDirection
    case class Horizontal() extends VHDirection
    case class NonDirection() extends VHDirection

    //private var vhDirection: VHDirection = NonDirection()
    private var fixedVHD: VHDirection = NonDirection()
    private var latestVHD: VHDirection = NonDirection()

    private def initFuncs {
        addAccelIf = if (ctx.isAccelTable) addAccel else passInt
        swapIf = if (ctx.isSwapScroll) swapIfOn _ else swapIfOff _

        reverseIfV = if (ctx.isReverseScroll) passInt else reverseIfFlip
        reverseIfH = if (ctx.isReverseScroll) reverseIfFlip else passInt

        sendVWheel = if (ctx.isRealWheelMode) sendRealVWheel else sendDirectVWheel
        sendHWheel = if (ctx.isRealWheelMode) sendRealHWheel else sendDirectHWheel

        sendWheelIf = if (ctx.isHorizontalScroll && ctx.isVhAdjusterMode) sendWheelVHA else sendWheelStd
    }

    private def initAccelTable {
        accelThreshold = ctx.getAccelThreshold
        accelMultiplier = ctx.getAccelMultiplier
    }

    private def initRealWheelMode {
        vWheelMove = ctx.getVWheelMove
        hWheelMove = ctx.getHWheelMove
        quickTurn = ctx.isQuickTurn
        wheelDelta = ctx.getWheelDelta
        reverseIfDelta = if (ctx.isReverseScroll) reverseIfFlip else passInt

        startWheelCount
    }

    private def initVhAdjusterMode {
        //vhDirection = NonDirection()
        fixedVHD = NonDirection()
        latestVHD = NonDirection()
        switchingThreshold = ctx.getSwitchingThreshold
        switchVHDif = if (ctx.isVhAdjusterSwitching) switchVHD _ else switchVHDifNone _
    }

    private def initStdMode {
        verticalThreshold = ctx.getVerticalThreshold
        horizontalThreshold = ctx.getHorizontalThreshold
        sendWheelStdIfHorizontal = if (ctx.isHorizontalScroll) sendWheelStdHorizontal _ else sendWheelStdNone _
    }

    def initScroll {
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
    private var switchVHDif = switchVHD _

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

    private def sendWheelStdHorizontal(wspt: POINT, dx: Int, dy: Int) {
        if (Math.abs(dx) > horizontalThreshold)
            sendHWheel(wspt, dx)
    }

    private def sendWheelStdNone(wspt: POINT, dx: Int, dy: Int) {}
    private var sendWheelStdIfHorizontal = sendWheelStdHorizontal _
    private var sendWheelIf = sendWheelStd _

    private def sendWheelStd(wspt: POINT, dx: Int, dy: Int) {
        if (Math.abs(dy) > verticalThreshold)
            sendVWheel(wspt, dy)

        sendWheelStdIfHorizontal(wspt, dx, dy)
    }

    private def swapIfOff(x: Int, y: Int) = (x, y)
    private def swapIfOn(x: Int, y: Int) = (y, x)
    private var swapIf = swapIfOff _

    private var scrollStartPoint: (Int, Int) = null

    def sendWheel(movePt: POINT) {
         //logger.debug("movePT: " + movePt.x + "," + movePt.y);

        val (sx, sy) = scrollStartPoint
        val (dx, dy) = swapIf(movePt.x - sx, movePt.y - sy)

        sendWheelIf(new POINT(sx, sy), dx, dy)
    }

    def sendWheelRaw(x: Int, y: Int) {
        //logger.debug("sendWheelRaw: " + x + "," + y)

        val (sx, sy) = scrollStartPoint
        if (sx != x && sy != y) {
            val (dx, dy) = swapIf(x, y)
            sendWheelIf(new POINT(sx, sy), dx, dy)
        }
    }

    private def createClick(mc: MouseClick) = {
        val extra = resendClickTag
        val input = createInputArray(2)

        def set(mouseData: Int, down: Int, up: Int) {
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

    def resendClick(mc: MouseClick) {
        sendInput(createClick(mc))
    }

    def resendClick(down: MouseEvent, up: MouseEvent) {
        (down, up) match {
            case (LeftDown(_), LeftUp(_)) => resendClick(LeftClick(down.info))
            case (RightDown(_), RightUp(_)) => resendClick(RightClick(down.info))
            case x => {
                throw new IllegalStateException("Not matched: " + x)
            }
        }
    }

    def resendDown(me: MouseEvent) {
        me match {
            case LeftDown(info) => sendInput(info.pt, 0, MOUSEEVENTF_LEFTDOWN, 0, resendTag)
            case RightDown(info) => sendInput(info.pt, 0, MOUSEEVENTF_RIGHTDOWN, 0, resendTag)
            case _ => throw new IllegalArgumentException()
        }
    }

    def resendUp(me: MouseEvent) {
        me match {
            case LeftUp(info) => sendInput(info.pt, 0, MOUSEEVENTF_LEFTUP, 0, resendTag)
            case RightUp(info) => sendInput(info.pt, 0, MOUSEEVENTF_RIGHTUP, 0, resendTag)
            case _ => throw new IllegalArgumentException()
        }
    }

    // https://github.com/java-native-access/jna/blob/master/contrib/platform/src/com/sun/jna/platform/win32/Kernel32Util.java
    private def loadCursor(id: Int) = {
        val id_ptr = new Pointer(id)
        u32ex.LoadImageW(null, id_ptr, IMAGE_CURSOR, 0, 0, LR_DEFAULTSIZE | LR_SHARED)
    }

    private val CURSOR_V = loadCursor(IDC_SIZENS)
    private val CURSOR_H = loadCursor(IDC_SIZEWE)

    private def copyCursor(icon: HICON) =
        u32.CopyIcon(icon).getPointer

    def changeCursor(hCur: Pointer) = {
        val icon = new HICON(hCur)
        u32ex.SetSystemCursor(copyCursor(icon), OCR_NORMAL)
        u32ex.SetSystemCursor(copyCursor(icon), OCR_IBEAM)
        u32ex.SetSystemCursor(copyCursor(icon), OCR_HAND)
    }

    def changeCursorV: Unit =
        changeCursor(CURSOR_V)

    def changeCursorH: Unit =
        changeCursor(CURSOR_H)

    //val SPIF_UPDATEINIFILE = 0x01;
    //val SPIF_SENDCHANGE = 0x02;
    //val SPIF_SENDWININICHANGE = 0x02;

    def restoreCursor = {
        //val fWinIni = if (!sendChange) 0 else (SPIF_UPDATEINIFILE | SPIF_SENDWININICHANGE)
        u32ex.SystemParametersInfoW(SPI_SETCURSORS, 0, null, 0)
    }

    private def getAsyncKeyState(vKey: Int) =
        (u32ex.GetAsyncKeyState(vKey) & 0xf000) != 0

    def getShiftState = getAsyncKeyState(VK_SHIFT)
    def getCtrlState = getAsyncKeyState(VK_CONTROL)
    def getAltState = getAsyncKeyState(VK_MENU)
    def getLeftState = getAsyncKeyState(VK_LBUTTON)
    def getRightState = getAsyncKeyState(VK_RBUTTON)
    def getEscState = getAsyncKeyState(VK_ESCAPE)

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
        def setPriorityClass(pc: Int) = k32ex.SetPriorityClass(process, pc)

        p match {
            case Normal() => setPriorityClass(NORMAL_PRIORITY_CLASS)
            case AboveNormal() => setPriorityClass(ABOVE_NORMAL_PRIORITY_CLASS)
            case High() => setPriorityClass(HIGH_PRIORITY_CLASS)
        }
    }

    def getCursorPos = {
        val pos = new POINT
        u32.GetCursorPos(pos)
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

        u32ex.RegisterRawInputDevices(rid, 1, rid(0).size)
    }
    
    def getLastErrorCode: Int = {
        return k32.GetLastError();
    }
    
    def getLastErrorMessage: String = {
        return Kernel32Util.formatMessage(getLastErrorCode);
    }

    def registerRawInput {
        if (!registerMouseRawInputDevice(RIDEV_INPUTSINK, messageHwnd))
           Dialog.errorMessage("Failed register RawInput: " + getLastErrorMessage)
    }

    def unregisterRawInput {
        if (!registerMouseRawInputDevice(RIDEV_REMOVE, null))
            Dialog.errorMessage("Failed unregister RawInput: " + getLastErrorMessage)
    }
}