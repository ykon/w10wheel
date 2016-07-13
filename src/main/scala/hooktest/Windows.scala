package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.concurrent._
import ExecutionContext.Implicits.global

import java.util.concurrent.ArrayBlockingQueue

import com.sun.jna.Pointer
import com.sun.jna.platform.win32._
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.WinDef._
import com.sun.jna.platform.win32.WinUser._
import win32ex.WinUserX._
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

import java.util.Random

object Windows {
	private val ctx = Context
	private val logger = ctx.logger
	private val u32 = User32.INSTANCE
	private val u32ex = User32ex.INSTANCE
	private val k32 = Kernel32.INSTANCE
	private val k32ex = Kernel32ex.INSTANCE
	private var hhk: HHOOK = null
	
	def messageLoop: Unit = {
		val msg = new MSG()
		
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
	
	private val inputQueue = new ArrayBlockingQueue[Array[INPUT]](128, true)
	
	private def inputSender = Future {
		while (true) {
			val msgs = inputQueue.take()
			u32.SendInput(new DWORD(msgs.length), msgs, msgs(0).size())
		}
	}
	
	inputSender // start
	
	def unhook =
		u32.UnhookWindowsHookEx(hhk)
	
	def setHook(proc: LowLevelMouseProc) = {
		val hMod = Kernel32.INSTANCE.GetModuleHandle(null)
		hhk = u32.SetWindowsHookEx(WH_MOUSE_LL, proc, hMod, 0)
	}
	
	def callNextHook(nCode: Int, wParam: WPARAM, info: HookInfo): LRESULT = {
		val ptr = info.getPointer
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
	
	def startWheelCount = {
		vwCount = ctx.getVWheelMove / 2
		hwCount = ctx.getHWheelMove / 2
	}
	
	private def addAccel(d: Int, a: Int) =
		if (d > 0) d + a else if (d < 0) d - a else 0
		
	private def setVScrollDirection(d: Int) =
		if (ctx.isReverseScroll) d else d * -1
		
	private def getVWheelDelta(input: Int) = {
		val delta = ctx.getWheelDelta
		
		if (ctx.isReverseScroll)
			if (input >= 0) delta else delta * -1
		else
			if (input >= 0) delta * -1 else delta
	}
	
	private def getHWheelDelta(input: Int) = {
		getVWheelDelta(input) * -1
	}
	
	def sendVerticalWheel(pt: POINT, d: Int) = {
		if (ctx.isRealWheelMode) {
			vwCount += Math.abs(d)
			
			if (vwCount >= ctx.getVWheelMove) {
				sendInput(pt, getVWheelDelta(d), MOUSEEVENTF_WHEEL, 0, 0)
				vwCount = 0
			}
		}
		else {
			val data = setVScrollDirection(addAccel(d, ctx.getVerticalAccel))
			sendInput(pt, data,  MOUSEEVENTF_WHEEL, 0, 0)
		}
	}
	
	private def setHScrollDirection(d: Int) = {
		if (ctx.isReverseScroll) d * -1 else d
	}
	
	def sendHorizontalWheel(pt: POINT, d: Int) = {
		if (ctx.isRealWheelMode) {
			hwCount += Math.abs(d)
			
			if (hwCount >= ctx.getHWheelMove) {
				sendInput(pt, getHWheelDelta(d), MOUSEEVENTF_HWHEEL, 0, 0)
				hwCount = 0
			}
		}
		else {
			val data = setHScrollDirection(addAccel(d, ctx.getHorizontalAccel))
			sendInput(pt, data, MOUSEEVENTF_HWHEEL, 0, 0)
		}
	}
	
	def sendWheel(pt: POINT) {
		val (sx, sy) = ctx.getScrollStartPoint
		val dx = pt.x - sx
		val dy = pt.y - sy
		
		val spt = new POINT(sx, sy)
		
		if (Math.abs(dy) > ctx.getVerticalThreshold) {
			//logger.debug(s"dy: $dy")
			sendVerticalWheel(spt, dy)
		}
		
		if (ctx.isHorizontalScroll) {
			if (Math.abs(dx) > ctx.getHorizontalThreshold)
				sendHorizontalWheel(spt, dx)
		}
	}
	
	def createClick(mc: MouseClick, extra: Int) = {
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
		//ctx.setSkip(mc, true)
		sendInput(createClick(mc, resendTag))
	}
	
	def resendDown(me: MouseEvent) = {
		val ext = resendTag
		//ctx.setSkip(me, true)
		
		me match {
			case LeftDown(info) => {
				sendInput(info.pt, 0, MOUSEEVENTF_LEFTDOWN, 0, ext)
			}
			case RightDown(info) => {
				sendInput(info.pt, 0, MOUSEEVENTF_RIGHTDOWN, 0, ext)
			}
		}
	}
	
	def resendUp(me: MouseEvent) = {
		//ctx.setSkip(me, true)
		me match {
			case LeftUp(info) => sendInput(info.pt, 0, MOUSEEVENTF_LEFTUP, 0, resendTag)
			case RightUp(info) => sendInput(info.pt, 0, MOUSEEVENTF_RIGHTUP, 0, resendTag)
		}
	}
	
	private val CURSOR_ID = IDC_SIZENS
	
	// https://github.com/java-native-access/jna/blob/master/contrib/platform/src/com/sun/jna/platform/win32/Kernel32Util.java
	private def loadCursor(id: Int) = {
		val id = new Pointer(CURSOR_ID)
		//ex.LoadCursorW(null, id)
		u32ex.LoadImageW(null, id, IMAGE_CURSOR, 0, 0, LR_DEFAULTSIZE | LR_SHARED)
	}
	
	private val hCur = loadCursor(IDC_SIZENS)
	
	private def copyCursor(hCur: Pointer) =
		u32ex.CopyIcon(hCur)
	
	//@volatile private var cursorChanged = false
	
	def changeCursor = {
		u32ex.SetSystemCursor(copyCursor(hCur), OCR_NORMAL)
		u32ex.SetSystemCursor(copyCursor(hCur), OCR_IBEAM)
		u32ex.SetSystemCursor(copyCursor(hCur), OCR_HAND)
		
		//cursorChanged = true
	}
	 
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
	
	def getAsyncShiftState =
		(u32ex.GetAsyncKeyState(VK_SHIFT) & 0xf000) != 0
	
	def getAsyncCtrlState =
		(u32ex.GetAsyncKeyState(VK_CONTROL) & 0xf000) != 0
	
	def getAsyncAltState =
		(u32ex.GetAsyncKeyState(VK_MENU) & 0xf000) != 0
		
	trait Priority {
		def name = this.getClass.getSimpleName
	}
	case class Normal() extends Priority
	case class AboveNormal() extends Priority
	case class High() extends Priority
	
	def setPriority(p: Priority) = {
		val process = k32.GetCurrentProcess()
		def setPriorityClass(pc: Int) = k32ex.SetPriorityClass(process, pc)
			
		p match {
			case Normal() => setPriorityClass(NORMAL_PRIORITY_CLASS)
			case AboveNormal() => setPriorityClass(ABOVE_NORMAL_PRIORITY_CLASS)
			case High() => setPriorityClass(HIGH_PRIORITY_CLASS)
		}
	}
}