package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.collection.immutable.List

// SWT
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets._

// for swt.jar
import javax.swing.UIManager
import javax.swing.JOptionPane
import java.nio.file.Paths

object W10Wheel {
    private val ctx = Context
    private val logger = ctx.logger
    val unhook: Promise[Boolean] = Promise[Boolean]
    
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
    
    def procExit = {
        logger.debug("procExit")
        
        Hook.unhook
        ctx.storeProperties
        PreventMultiInstance.unlock
        
        display.syncExec(new Runnable() {
            override def run() = display.dispose
        })
    }
    
    private def messageDoubleLaunch {
        Dialog.errorMessage(shell, "Double Launch?")
    }
    
    private def messageLoop {
        while (!shell.isDisposed) {
            if (!display.readAndDispatch)
                display.sleep
        }
    }
    
    private val shutdown = new Thread {
        override def run {
            logger.debug("Shutdown Hook")
            procExit
        }
    }
     
    Runtime.getRuntime.addShutdownHook(shutdown)
    
    private def procArgs(args: Array[String]) {
        if (args.length == 1) {
            val name = args(0)
            if (Properties.exists(name))
                Context.setSelectedProperties(name)
            else
                Dialog.errorMessage(shell, s"'$name' properties does not exist.")
        }
    }

    def main(args: Array[String]) {
        if (!PreventMultiInstance.tryLock) {
            messageDoubleLaunch
            System.exit(0)
        }
        
        unhook.future.foreach(_ => System.exit(0))
        
        procArgs(args)
        ctx.loadProperties
        ctx.setSystemTray
        
        Hook.setMouseHook
        logger.debug("Mouse hook installed")
        
        //Windows.messageLoop
        //logger.debug("exit message loop")
        
        messageLoop
    }
}