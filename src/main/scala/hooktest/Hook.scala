package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import com.sun.jna.Pointer
import com.sun.jna.platform.win32._
import com.sun.jna.platform.win32.WinDef._
import com.sun.jna.platform.win32.WinUser._

import win32ex.WinUserX._
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

object Hook {
    private val ctx = Context
    private val logger = ctx.logger
    
    @volatile private var mouseHhk: HHOOK = null
    @volatile private var keyboardHhk: HHOOK = null
    
    private val mouseProc = new LowLevelMouseProc() {
        override def callback(nCode: Int, wParam: WPARAM, info: HookInfo): LRESULT = {
            val eh = EventHandler
            val callNextHook = () => Windows.callNextHook(mouseHhk, nCode, wParam, info.getPointer)
            eh.setCallNextHook(callNextHook)
            
            if (nCode < 0 || ctx.isPassMode)
                return callNextHook()
            
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
                case WM_MOUSEWHEEL | WM_MOUSEHWHEEL => {
                    //logger.debug(s"mouseData: ${info.mouseData}")
                    callNextHook()
                }
            }
        }
    }
    
    private val keyboardProc = new LowLevelKeyboardProc() {
        override def callback(nCode: Int, wParam: WPARAM, info: KHookInfo): LRESULT = {
            val keh = KEventHandler
            val callNextHook = () => Windows.callNextHook(keyboardHhk, nCode, wParam, info.getPointer)
            keh.setCallNextHook(callNextHook)
            
            if (nCode < 0 || ctx.isPassMode)
                return callNextHook()
                
            wParam.intValue match {
                case WM_KEYDOWN | WM_SYSKEYDOWN  => keh.keyDown(info)
                case WM_KEYUP | WM_SYSKEYUP =>keh.keyUp(info)
                case _ => callNextHook()
            }
        }
    }
    
    def setMouseHook {
        mouseHhk = Windows.setHook(mouseProc)
    }
    
    def setKeyboardHook {
        keyboardHhk = Windows.setHook(keyboardProc)
    }
    
    def unhookMouse {
        if (mouseHhk != null) {
            Windows.unhook(mouseHhk)
            mouseHhk = null
        }
    }
    
    def unhookKeyboard {
        if (keyboardHhk != null) {
            Windows.unhook(keyboardHhk)
            keyboardHhk = null
        }
    }
    
    def setOrUnsetKeyboardHook(b: Boolean) {
        if (b) setKeyboardHook else unhookKeyboard
    }
    
    def unhook {
        unhookMouse
        unhookKeyboard
    }
}