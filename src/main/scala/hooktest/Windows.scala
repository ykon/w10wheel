package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

//import scala.concurrent._
//import ExecutionContext.Implicits.global

import java.util.concurrent.ArrayBlockingQueue

import com.sun.jna.Pointer
import com.sun.jna.WString
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
    
    private def createInputArray(size: Int) =
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
    
    def isResendEvent(me: MouseEvent): Boolean = {
        val ext = me.info.dwExtraInfo.intValue()
        ext == resendTag
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
    
    def startWheelCount = {
        vwCount = if (ctx.isQuickFirst) ctx.getVWheelMove else ctx.getVWheelMove / 2
        hwCount = if (ctx.isQuickFirst) ctx.getHWheelMove else ctx.getHWheelMove / 2
        
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
    
    private def addAccel(d: Int): Int = {
        if (!ctx.isAccelTable)
            d
        else {
            val i = getNearestIndex(d, ctx.getAccelThreshold)
            (d * ctx.getAccelMultiplier(i)).toInt
        }
    }
        
    private def getVWheelDelta(input: Int) = {
        val delta = ctx.getWheelDelta
        val res = if (input > 0) -delta else delta
        
        if (ctx.isReverseScroll) -res else res
    }
    
    private def getHWheelDelta(input: Int) = {
        -(getVWheelDelta(input))
    }
    
    private def isTurnMove(last: MoveDirection, d: Int) = last match {
        case null => false
        case Plus() => d < 0
        case Minus() => d > 0
    }
    
    //@volatile private var sendVWheel: (POINT, Int) => Unit = null
    //@volatile private var sendHWheel: (POINT, Int) => Unit = null
    
    private def sendRealVWheel(pt: POINT, d: Int) {
        def send = sendInput(pt, getVWheelDelta(d), MOUSEEVENTF_WHEEL, 0, 0)
        vwCount += Math.abs(d)
        
        if (ctx.isQuickTurn && isTurnMove(vLastMove, d))
            send
        else if (vwCount >= ctx.getVWheelMove) {
            send
            vwCount -= ctx.getVWheelMove
        }
        
        vLastMove = if (d > 0) Plus() else Minus() 
    }
    
    def sendVWheel(pt: POINT, d: Int) {
        if (ctx.isRealWheelMode)
            sendRealVWheel(pt, d)
        else {
            def rev(d: Int) = if (ctx.isReverseScroll) d else -d
            sendInput(pt, rev(addAccel(d)),  MOUSEEVENTF_WHEEL, 0, 0)
        }
    }
    
    private def sendRealHWheel(pt: POINT, d: Int) {
        def send = sendInput(pt, getHWheelDelta(d), MOUSEEVENTF_HWHEEL, 0, 0)
        hwCount += Math.abs(d)
        
        if (ctx.isQuickTurn && isTurnMove(hLastMove, d))
            send
        else if (hwCount >= ctx.getHWheelMove) {
            send
            hwCount -= ctx.getHWheelMove
        }
        
        hLastMove = if (d > 0) Plus() else Minus() 
    }
    
    def sendHWheel(pt: POINT, d: Int) {
        if (ctx.isRealWheelMode)
            sendRealHWheel(pt, d)
        else {
            def rev(d: Int) = if (ctx.isReverseScroll) -d else d
            sendInput(pt, rev(addAccel(d)), MOUSEEVENTF_HWHEEL, 0, 0)
        }
    }
    
    /*
    def changeScrollMode {
        sendVWheel = if (ctx.isRealWheelMode) sendRealVWheel else sendNormalVWheel
        sendHWheel = if (ctx.isRealWheelMode) sendRealHWheel else sendNormalHWheel
    }
    */
    
    abstract class VHDirection
    case class Vertical() extends VHDirection
    case class Horizontal() extends VHDirection
    
    private var vhDirection: VHDirection = null
    
    private def resetVHA {
        vhDirection = null
    }
    
    def initScroll {
        if (ctx.isRealWheelMode)
            startWheelCount
        if (ctx.isVhAdjusterMode)
            resetVHA
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
            val iy = if (ctx.isFirstPreferVertical) ady * 2 else ady
            if (iy >= adx) setVerticalVHA else setHorizontalVHA
        }
    }
    
    private def checkSwitchingVHA(adx: Int, ady: Int) {
        val sthr = ctx.getSwitchingThreshold
        if (ady > sthr) setVerticalVHA else if (adx > sthr) setHorizontalVHA
    }
    
    private def sendWheelVHA(wspt: POINT, dx: Int, dy: Int) {        
        val adx = Math.abs(dx)
        val ady = Math.abs(dy)
    
        if (vhDirection == null) // first
            checkFirstVHA(adx, ady)
        else {
            if (ctx.isVhAdjusterSwitching)
                checkSwitchingVHA(adx, ady)
        }
        
        vhDirection match {
            case null => {}
            case Vertical() => if (dy != 0) sendVWheel(wspt, dy)
            case Horizontal() =>if (dx != 0) sendHWheel(wspt, dx)
        }
    }
    
    def sendWheel(pt: POINT) {
        val (sx, sy) = ctx.getScrollStartPoint
        
        def swap(x: Int, y: Int) = if (ctx.isSwapScroll) (y, x) else (x, y)
        val (dx, dy) = swap(pt.x - sx, pt.y - sy)
        
        val wspt = new POINT(sx, sy)
       
        if (ctx.isVhAdjusterMode && ctx.isHorizontalScroll) {
            //logger.debug(s"dx: $dx, dy: $dy")
            sendWheelVHA(wspt, dx, dy)
        }
        else {
            if (Math.abs(dy) > ctx.getVerticalThreshold)
                sendVWheel(wspt, dy)
            
            if (ctx.isHorizontalScroll) {
                if (Math.abs(dx) > ctx.getHorizontalThreshold)
                    sendHWheel(wspt, dx)
            }
        }
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
    
    def resendClick(mc: MouseClick) = {
        sendInput(createClick(mc, resendTag))
    }
    
    def resendDown(me: MouseEvent) = {
        me match {
            case LeftDown(info) => sendInput(info.pt, 0, MOUSEEVENTF_LEFTDOWN, 0, resendTag)
            case RightDown(info) => sendInput(info.pt, 0, MOUSEEVENTF_RIGHTDOWN, 0, resendTag)
        }
    }
    
    def resendUp(me: MouseEvent) = {
        me match {
            case LeftUp(info) => sendInput(info.pt, 0, MOUSEEVENTF_LEFTUP, 0, resendTag)
            case RightUp(info) => sendInput(info.pt, 0, MOUSEEVENTF_RIGHTUP, 0, resendTag)
        }
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
    
    //@volatile private var cursorChanged = false
    
    def changeCursor(hCur: Pointer) = {
        u32ex.SetSystemCursor(copyCursor(hCur), OCR_NORMAL)
        u32ex.SetSystemCursor(copyCursor(hCur), OCR_IBEAM)
        u32ex.SetSystemCursor(copyCursor(hCur), OCR_HAND)
        
        //cursorChanged = true
    }
    
    def changeCursorV =
        changeCursor(CURSOR_V)
        
    def changeCursorH =
        changeCursor(CURSOR_H)
     
    //val SPIF_UPDATEINIFILE = 0x01;
    //val SPIF_SENDCHANGE = 0x02;  
    //val SPIF_SENDWININICHANGE = 0x02;  
    
    def restoreCursor = {
        //val fWinIni = if (!sendChange) 0 else (SPIF_UPDATEINIFILE | SPIF_SENDWININICHANGE)    
        u32ex.SystemParametersInfoW(SPI_SETCURSORS, 0, null, 0)
        //cursorChanged = false
    }
    
    /*
    def isCursorChanged =
        cursorChanged
    */
    
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
        val cursorPos = new POINT;
        u32ex.GetCursorPos(cursorPos)
        cursorPos
    }
}