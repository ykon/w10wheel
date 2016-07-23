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

import scala.collection.immutable.List

// SWT
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets._

import win32ex.WinUserX._
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

// for swt.jar
import javax.swing.UIManager
import javax.swing.JOptionPane
import java.nio.file.Paths

object W10Wheel {
    private val ctx = Context
    private val logger = ctx.logger
    val unhook: Promise[Boolean] = Promise[Boolean]
    
    private def errorMessage(msg: String) {
        JOptionPane.showMessageDialog(null, msg, "Error", JOptionPane.ERROR_MESSAGE)
    }
    
    private val REPLACE_SWT_NAME = "ReplaceSWT.exe"
    
    private def getSelfPath = {
        Paths.get(getClass.getProtectionDomain().getCodeSource().getLocation().toURI())
    }
    
    private def exeReplaceSWT {
        val selfPath = getSelfPath
        val replaceSwtPath = selfPath.resolveSibling(REPLACE_SWT_NAME)
        val pb = new ProcessBuilder(replaceSwtPath.toString, selfPath.toString)
        pb.start
    }
    
    private val display = {
        try {
            Display.getDefault
        }
        catch {
            case e: UnsatisfiedLinkError => {
                exeReplaceSWT
                System.exit(0)
                null
            }
        }
    }
    
    val shell = new Shell(display)
        
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
                case WM_MOUSEWHEEL | WM_MOUSEHWHEEL => {
                    //logger.debug(s"mouseData: ${info.mouseData}")
                    callNextHook()
                }
            }
        }
    }
    
    private def processExit = {
        logger.debug("unhook and exit")
        Windows.unhook
        
        ctx.storeProperties
        PreventMultiInstance.unlock
    }
    
    private def messageDoubleLaunch {
        val mb = new MessageBox(shell, SWT.OK | SWT.ICON_ERROR)
        mb.setText("Error")
        mb.setMessage("Double Launch?")
        mb.open()
    }
    
    private def messageLoop {
        while (!shell.isDisposed) {
            if (!display.readAndDispatch)
                display.sleep
        }
    }
    
    def main(args: Array[String]) {
        if (!PreventMultiInstance.tryLock) {
            messageDoubleLaunch
            display.dispose()
            System.exit(0)
        }
        
        unhook.future.foreach(_ => {
            processExit
            display.syncExec(new Runnable() {
                override def run() = display.dispose()
            })
            System.exit(0)
        })
        
        ctx.loadProperties
        ctx.setSystemTray
        
        Windows.setHook(eventDispatcher)
        logger.debug("Mouse hook installed")
        
        //Windows.messageLoop
        //logger.debug("exit message loop")
        
        messageLoop
    }
}