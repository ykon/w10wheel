package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import com.sun.jna.Pointer
import com.sun.jna.platform.win32._
import com.sun.jna.platform.win32.WinDef._
import com.sun.jna.platform.win32.WinUser._

import scala.concurrent._
import ExecutionContext.Implicits.global
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import javax.swing.JOptionPane
import javax.swing.UIManager

import scala.collection.immutable.List

import win32ex.WinUserX._
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

object W10Wheel {
	private val ctx = Context
	private val logger = ctx.logger	
	val unhook: Promise[Boolean] = Promise[Boolean]
		
	private val eventDispatcher = new LowLevelMouseProc() {
		override def callback(nCode: Int, wParam: WPARAM, info: HookInfo): LRESULT = {
			val eh = EventHandler
			val callNextHook = () => Windows.callNextHook(nCode, wParam, info)
			eh.setCallNextHook(callNextHook)
			
			if (nCode < 0 || ctx.isPassMode)
				return callNextHook()
			
			wParam.intValue() match {
				case WM_MOUSEMOVE => eh.move(info)
				case WM_LBUTTONDOWN => eh.leftDown(info)
				case WM_LBUTTONUP => eh.leftUp(info)
				case WM_RBUTTONDOWN => eh.rightDown(info)
				case WM_RBUTTONUP => eh.rightUp(info)
				case WM_MBUTTONDOWN => eh.middleDown(info)
				case WM_MBUTTONUP => eh.middleUp(info)
				case WM_XBUTTONDOWN => eh.xDown(info)
				case WM_XBUTTONUP => eh.xUp(info)
				case WM_MOUSEWHEEL | WM_MOUSEHWHEEL => callNextHook()
			}
		}
	}
	
	private def processExit = {
		logger.debug("unhook and exit")
		Windows.unhook
		ctx.storeProperties
		PreventMultiInstance.unlock
	}
	
	private def messageDoubleLaunch =
		JOptionPane.showMessageDialog(null, "Double Launch?", "Error", JOptionPane.ERROR_MESSAGE)
	
	def main(args: Array[String]) {
		UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		
		if (!PreventMultiInstance.tryLock) {
			messageDoubleLaunch
			System.exit(0)
		}
		
		unhook.future.foreach(_ => {
			processExit
			System.exit(0)
		});
		
		ctx.loadProperties
		ctx.setSystemTray
		
		Windows.setHook(eventDispatcher)
		logger.debug("Mouse hook installed")
		
		Windows.messageLoop
		logger.debug("exit message loop")
		
		processExit
	}
}