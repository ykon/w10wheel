package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

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

    def procExit {
        logger.debug("procExit")

        Hook.unhook
        ctx.storeProperties
        PreventMultiInstance.unlock
    }

    private def messageDoubleLaunch {
        Dialog.errorMessage(shell, "Double Launch?")
    }

    private def swtMessageLoop {
        while (!shell.isDisposed) {
            if (!display.readAndDispatch)
                display.sleep
        }
    }

    def exitMessageLoop {
        display.dispose()
    }

    /*
    private val shutdown = new Thread {
        override def run {
            logger.debug("Shutdown Hook")

            if (!display.isDisposed)
                exitMessageLoop
        }
    }

    Runtime.getRuntime.addShutdownHook(shutdown)
    */

    private def getBool(args: Array[String], i: Int) = {
        try {
            if (args.length == 1) true else args(i).toBoolean
        }
        catch {
            case e: IllegalArgumentException => {
                Dialog.errorMessage(shell, e)
                System.exit(0)
                false
            }
        }
    }

    private def setSelectedProperties(name: String) {
        if (Properties.exists(name))
            Context.setSelectedProperties(name)
        else
            Dialog.errorMessage(shell, s"'$name' properties does not exist.")
    }

    private def unknownCommand(name: String) {
        Dialog.errorMessage(shell, "Unknown Command: " + name, "Command Error")
        System.exit(0)
    }

    private def procArgs(args: Array[String]) {
        logger.debug("procArgs")

        if (args.length > 0) {
            args(0) match {
                case "--sendExit" => W10Message.sendExit
                case "--sendPassMode" => W10Message.sendPassMode(getBool(args, 1))
                case name if name.startsWith("--") => unknownCommand(name)
                case name => setSelectedProperties(name)
            }

            if (args(0).startsWith("--send")) {
                Thread.sleep(1000)
                System.exit(0)
            }
        }
    }

    def main(args: Array[String]) {
        procArgs(args)

        if (!PreventMultiInstance.tryLock) {
            messageDoubleLaunch
            System.exit(0)
        }

        ctx.loadProperties
        ctx.setSystemTray

        Hook.setMouseHook
        logger.debug("Mouse hook installed")

        //println(s"depth: ${display.getDepth}")
        //println(s"dpi: ${display.getDPI}")


        //Windows.messageLoop
        //logger.debug("exit message loop")

        swtMessageLoop
        logger.debug("Exit message loop")
        procExit
    }
}