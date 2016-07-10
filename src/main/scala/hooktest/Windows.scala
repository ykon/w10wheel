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
	private val lib = User32.INSTANCE
	private val ex = User32ex.INSTANCE
	private var hhk: HHOOK = null
	
	def messageLoop: Unit = {
		val msg = new MSG()
		
		while (true) {
			lib.GetMessage(msg, null, 0, 0) match {
				case 0 => return
				case -1 => {
					logger.debug("error in get message")
					return
				}
				case _ => {
					logger.debug("got message")
					lib.TranslateMessage(msg)
					lib.DispatchMessage(msg)
				}
			}
		}
	}
	
	private val inputQueue = new ArrayBlockingQueue[Array[INPUT]](128, true)
	
	private def inputSender = Future {
		while (true) {
			val msgs = inputQueue.take()
			lib.SendInput(new DWORD(msgs.length), msgs, msgs(0).size())
		}
	}
	
	inputSender // start
	
	def unhook =
		lib.UnhookWindowsHookEx(hhk)
	
	def setHook(proc: LowLevelMouseProc) = {
		val hMod = Kernel32.INSTANCE.GetModuleHandle(null)
		hhk = lib.SetWindowsHookEx(WH_MOUSE_LL, proc, hMod, 0)
	}
	
	def callNextHook(nCode: Int, wParam: WPARAM, info: HookInfo): LRESULT = {
		val ptr = info.getPointer
		val peer = Pointer.nativeValue(ptr)
		lib.CallNextHookEx(hhk, nCode, wParam, new LPARAM(peer))
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
	
	private def addAccel(d: Int, a: Int) =
		if (d > 0) d + a else if (d < 0) d - a else 0
		
	private def setVScrollDirection(d: Int) =
		if (ctx.isReverseScroll) d else d * -1
	
	def sendVerticalWheel(pt: POINT, d: Int) = {
		val data = setVScrollDirection(addAccel(d, ctx.getVerticalAccel))
		sendInput(pt, data,  MOUSEEVENTF_WHEEL, 0, 0)
	}
	
	private def setHScrollDirection(d: Int) = {
		if (ctx.isReverseScroll) d * -1 else d
	}
	
	def sendHorizontalWheel(pt: POINT, d: Int) = {
		val data = setHScrollDirection(addAccel(d, ctx.getHorizontalAccel))
		sendInput(pt, data, MOUSEEVENTF_HWHEEL, 0, 0)
	}
	
	def sendWheel(pt: POINT) {
		val (sx, sy) = ctx.getScrollStartPoint
		val dx = pt.x - sx
		val dy = pt.y - sy
		
		val spt = new POINT(sx, sy)
		
		if (Math.abs(dy) > ctx.getVerticalThreshold)
			sendVerticalWheel(spt, dy)
		
		if (ctx.isHorizontalScroll) {
			if (Math.abs(dx) > ctx.getHorizontalThreshold)
				sendHorizontalWheel(spt, dx)
		}
	}
	
	def createClick(mc: MouseClick, extra: Int) = {
		val input = createInputArray(2)
		mc match {
			case LeftClick(info) => {
				setInput(input(0), info.pt, 0, MOUSEEVENTF_LEFTDOWN, 0, extra)
				setInput(input(1), info.pt, 0, MOUSEEVENTF_LEFTUP, 0, extra)
			}
			case RightClick(info) => {
				setInput(input(0), info.pt, 0, MOUSEEVENTF_RIGHTDOWN, 0, extra)
				setInput(input(1), info.pt, 0, MOUSEEVENTF_RIGHTUP, 0, extra)
			}
			case MiddleClick(info) => {
				setInput(input(0), info.pt, 0, MOUSEEVENTF_MIDDLEDOWN, 0, extra)
				setInput(input(1), info.pt, 0, MOUSEEVENTF_MIDDLEUP, 0, extra)
			}
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
		ex.LoadImageW(null, id, IMAGE_CURSOR, 0, 0, LR_DEFAULTSIZE | LR_SHARED)
	}
	
	private val hCur = new HCURSOR(loadCursor(IDC_SIZENS).getPointer)
	
	private def copyCursor(hCur: HCURSOR): HCURSOR =
		new HCURSOR(ex.CopyIcon(new HICON(hCur.getPointer)).getPointer)
	
	//@volatile private var cursorChanged = false
	
	def changeCursor = {
		ex.SetSystemCursor(copyCursor(hCur), OCR_NORMAL)
		ex.SetSystemCursor(copyCursor(hCur), OCR_IBEAM)
		ex.SetSystemCursor(copyCursor(hCur), OCR_HAND)
		
		//cursorChanged = true
	}
	 
	//val SPIF_UPDATEINIFILE = 0x01;
	//val SPIF_SENDCHANGE = 0x02;  
	//val SPIF_SENDWININICHANGE = 0x02;  
	
	def restoreCursor = {
		//val fWinIni = if (!sendChange) 0 else (SPIF_UPDATEINIFILE | SPIF_SENDWININICHANGE)	
		ex.SystemParametersInfoW(SPI_SETCURSORS, 0, null, 0)
		//cursorChanged = false
	}
	
	/*
	def isCursorChanged =
		cursorChanged
	*/
	
	def getAsyncShiftState =
		(ex.GetAsyncKeyState(VK_SHIFT) & 0xf000) != 0
	
	def getAsyncCtrlState =
		(ex.GetAsyncKeyState(VK_CONTROL) & 0xf000) != 0
	
	def getAsyncAltState =
		(ex.GetAsyncKeyState(VK_MENU) & 0xf000) != 0
}