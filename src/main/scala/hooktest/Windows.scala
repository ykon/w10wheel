package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.util.concurrent.ArrayBlockingQueue

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.platform.win32._
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.WinDef._
import com.sun.jna.platform.win32.WinUser._
import win32ex.WinUserX._
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

import java.util.Random

object Windows {
    private val ctx = Context
    private val logger = ctx.logger
    private val u32 = User32.INSTANCE
    private val u32ex = User32ex.INSTANCE
    private val k32 = Kernel32.INSTANCE
    private val k32ex = Kernel32ex.INSTANCE
    //private val shcore = Shcore.INSTANCE
    
    def messageLoop: Unit = {
        val msg = new MSG
        
        while (true) {
            u32.GetMessage(msg, null, 0, 0) match {
                case 0 => return
                case -1 => {
                    logger.debug("error in get message")
                    return
                }
                case _ => {
                    logger.debug("got message")
                    u32.TranslateMessage(msg)
                    u32.DispatchMessage(msg)
                }
            }
        }
    }
    
    
    private val WM_QUERYENDSESSION = 0x0011
    private val GWL_WNDPROC = -4
    
    private val shutdownThread = new Thread {
        val windowProc = new WindowProc {
            override def callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT = {
                if (uMsg == WM_QUERYENDSESSION)
                    W10Wheel.procExit
                
                u32.DefWindowProc(hwnd, uMsg, wParam, lParam)
            }
        }
    
        override def run {
            val className = new WString(ctx.PROGRAM_NAME + "_Shutdown")
         
            val wx = new WNDCLASSEX
            wx.lpszClassName = className
            wx.lpfnWndProc = windowProc
            
            if (u32.RegisterClassEx(wx).intValue() != 0) {
                val hwnd = u32.CreateWindowEx(0, className, null, 0, 0, 0, 0, 0, null, null, null, null)
            
                messageLoop
            }
        }
    }
    shutdownThread.setDaemon(true)
    shutdownThread.start
    
    private val inputQueue = new ArrayBlockingQueue[Array[INPUT]](128, true)
    
    private val senderThread = new Thread(new Runnable {
        override def run {
            while (true) {
                val msgs = inputQueue.take()
                u32.SendInput(new DWORD(msgs.length), msgs, msgs(0).size())
            }
        }
    })
    
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
    private val reResendTag = createRandomNumber
    
    def isResendEvent(me: MouseEvent): Boolean = {
        me.info.dwExtraInfo.intValue() == resendTag
    }
    
    def isReResendEvent(me: MouseEvent): Boolean = {
        me.info.dwExtraInfo.intValue() == reResendTag 
    }
    
    def setInput(msg: INPUT, pt: POINT, data: Int, flags: Int, time: Int, extra: Int) {
        msg.`type` = new DWORD(INPUT.INPUT_MOUSE)
        msg.input.setType("mi")
        msg.input.mi.dx = new LONG(pt.x)
        msg.input.mi.dy = new LONG(pt.y)
        msg.input.mi.mouseData = new DWORD(data)
        msg.input.mi.dwFlags = new DWORD(flags)
        msg.input.mi.time = new DWORD(time)
        msg.input.mi.dwExtraInfo = new ULONG_PTR(extra)
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
    
    abstract class MoveDirection
    case class Plus() extends MoveDirection
    case class Minus() extends MoveDirection
    
    private var vLastMove: MoveDirection = null
    private var hLastMove: MoveDirection = null
    
    private var vWheelMove = 0
    private var hWheelMove = 0
    private var quickTurn = false
    
    def startWheelCount {
        vwCount = if (ctx.isQuickFirst) vWheelMove else vWheelMove / 2
        hwCount = if (ctx.isQuickFirst) hWheelMove else hWheelMove / 2
        
        vLastMove = null
        hLastMove = null
    }
    
    import scala.annotation.tailrec
    
    // d == Not Zero
    private def getNearestIndex(d: Int, thr: Array[Int]): Int = {
        val ad = Math.abs(d)
        
        @tailrec
        def loop(i: Int): Int = {
            val n = thr(i)
            if (n == ad)
                i
            else if (n > ad)
                if (n - ad < Math.abs(thr(i - 1) - ad)) i else i - 1
            else
                if (i != thr.length - 1) loop(i + 1) else i
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
        case null => false
        case Plus() => d < 0
        case Minus() => d > 0
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
    
    abstract class VHDirection
    case class Vertical() extends VHDirection
    case class Horizontal() extends VHDirection
    
    private var vhDirection: VHDirection = null
    
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
        vhDirection = null
        switchingThreshold = ctx.getSwitchingThreshold
        checkSwitchVHAif = if (ctx.isVhAdjusterSwitching) checkSwitchVHA _ else checkSwitchVHAifNone _
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
    
    private def setVerticalVHA {
        vhDirection = Vertical()
        if (ctx.isCursorChange) changeCursorV
    }
    
    private def setHorizontalVHA {
        vhDirection = Horizontal()
        if (ctx.isCursorChange) changeCursorH
    }
    
    private def checkFirstVHA(adx: Int, ady: Int) {
        val mthr = ctx.getFirstMinThreshold
        if (adx > mthr || ady > mthr) {
            val y = if (ctx.isFirstPreferVertical) ady * 2 else ady
            if (y >= adx) setVerticalVHA else setHorizontalVHA
        }
    }
    
    private var switchingThreshold = 0
    
    private def checkSwitchVHA(adx: Int, ady: Int) {
        val sthr = switchingThreshold
        if (ady > sthr) setVerticalVHA else if (adx > sthr) setHorizontalVHA
    }
    
    private def checkSwitchVHAifNone(adx: Int, ady: Int) {}
    private var checkSwitchVHAif = checkSwitchVHA _
    
    private def sendWheelVHA(wspt: POINT, dx: Int, dy: Int) {        
        val adx = Math.abs(dx)
        val ady = Math.abs(dy)
    
        if (vhDirection == null) // first
            checkFirstVHA(adx, ady)
        else
            checkSwitchVHAif(adx, ady)
        
        vhDirection match {
            case null => ()
            case Vertical() => if (dy != 0) sendVWheel(wspt, dy)
            case Horizontal() => if (dx != 0) sendHWheel(wspt, dx)
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
        val (sx, sy) = scrollStartPoint        
        val (dx, dy) = swapIf(movePt.x - sx, movePt.y - sy)
        val wspt = new POINT(sx, sy)
        
        sendWheelIf(wspt, dx, dy)
    }
    
    private def createClick(mc: MouseClick, extra: Int) = {
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
        sendInput(createClick(mc, resendTag))
    }
    
    def resendDown(me: MouseEvent) {
        me match {
            case LeftDown(info) => sendInput(info.pt, 0, MOUSEEVENTF_LEFTDOWN, 0, resendTag)
            case RightDown(info) => sendInput(info.pt, 0, MOUSEEVENTF_RIGHTDOWN, 0, resendTag)
        }
    }
    
    def resendUp(me: MouseEvent, extra: Int = resendTag) {
        me match {
            case LeftUp(info) => sendInput(info.pt, 0, MOUSEEVENTF_LEFTUP, 0, extra)
            case RightUp(info) => sendInput(info.pt, 0, MOUSEEVENTF_RIGHTUP, 0, extra)
        }
    }
    
    def reResendUp(me: MouseEvent) {
        resendUp(me, reResendTag)
    }
    
    // https://github.com/java-native-access/jna/blob/master/contrib/platform/src/com/sun/jna/platform/win32/Kernel32Util.java
    private def loadCursor(id: Int) = {
        val id_ptr = new Pointer(id)
        u32ex.LoadImageW(null, id_ptr, IMAGE_CURSOR, 0, 0, LR_DEFAULTSIZE | LR_SHARED)
    }
    
    private val CURSOR_V = loadCursor(IDC_SIZENS)
    private val CURSOR_H = loadCursor(IDC_SIZEWE)
    
    private def copyCursor(hCur: Pointer) =
        u32ex.CopyIcon(hCur)
    
    def changeCursor(hCur: Pointer) = {
        u32ex.SetSystemCursor(copyCursor(hCur), OCR_NORMAL)
        u32ex.SetSystemCursor(copyCursor(hCur), OCR_IBEAM)
        u32ex.SetSystemCursor(copyCursor(hCur), OCR_HAND)
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
    
    private def checkAsyncKeyState(vKey: Int) =
        (u32ex.GetAsyncKeyState(vKey) & 0xf000) != 0
    
    def checkShiftState =
        checkAsyncKeyState(VK_SHIFT)
    
    def checkCtrlState =
        checkAsyncKeyState(VK_CONTROL)
    
    def checkAltState =
        checkAsyncKeyState(VK_MENU)
        
    trait Priority {
        def name = this.getClass.getSimpleName
    }
    case class Normal() extends Priority
    case class AboveNormal() extends Priority
    case class High() extends Priority
    
    def getPriority(name: String) = name match {
        case "High" => High()
        case "AboveNormal" | "Above Normal" => AboveNormal()
        case "Normal" => Normal()
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
        u32ex.GetCursorPos(pos)
        pos
    }
    
    def getPhysicalCursorPos = {
        val pos = new POINT
        u32ex.GetPhysicalCursorPos(pos)
        pos
    }
    
    /*
    def setProcessDPIAware {
        u32ex.SetProcessDPIAware()
    }
    */
    
    /*
    def setProcessPerMonitorDpiAware = {
        shcore.SetProcessDpiAwareness(PROCESS_DPI_AWARENESS.PROCESS_PER_MONITOR_DPI_AWARE).intValue()
    }
    */
    
    /*
    def setThreadDpiAwarenessContext = {
        u32ex.SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT.DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE)
    }
    */
    
    /*
    private def getMonitor(pt: POINT) = {
        u32ex.MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST)
    }
    
    def getDpi(pt: POINT) = {
        val dpiX = new IntByReference
        val dpiY = new IntByReference
        
        shcore.GetDpiForMonitor(getMonitor(pt), DpiType.Effective, dpiX, dpiY)
        
        (dpiX.getValue, dpiY.getValue)
    }
    */
}