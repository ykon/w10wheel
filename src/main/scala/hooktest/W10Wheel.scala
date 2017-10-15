package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.collection.immutable.List

// SWT
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets._

object W10Wheel {
    private val ctx = Context
    private val logger = ctx.logger
    private val display = Display.getDefault

    val shell = new Shell(display)

    def procExit {
        logger.debug("procExit")

        Hook.unhook
        ctx.storeProperties
        PreventMultiInstance.unlock
    }

    private def messageDoubleLaunch {
        Dialog.errorMessage("Double Launch?")
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

    private def getBool(sl: List[String]) = {
        try {
            sl match {
                case s :: _ => s.toBoolean
                case _ => true
            }
        }
        catch {
            case e: IllegalArgumentException => {
                Dialog.errorMessageE(e)
                System.exit(1)
                false
            }
        }
    }

    private def setSelectedProperties(name: String) {
        if (Properties.exists(name))
            Context.setSelectedProperties(name)
        else
            Dialog.errorMessage(s"'$name' properties does not exist.")
    }

    private def unknownCommand(name: String) {
        Dialog.errorMessage("Unknown Command: " + name, "Command Error")
        System.exit(1)
    }

    private def procArgs(args: Array[String]) {
        logger.debug("procArgs")
        
        args.toList match {
            case "--sendExit" :: _ => W10Message.sendExit
            case "--sendPassMode" :: rest => W10Message.sendPassMode(getBool(rest))
            case "--sendReloadProp" :: _ => W10Message.sendReloadProp
            case "--sendInitState" :: _ => W10Message.sendInitState
            case name :: _ if name.startsWith("--") => unknownCommand(name)
            case name :: _ => setSelectedProperties(name)
            case _ => ()
        }
        
        if (args.length > 0 && args(0).startsWith("--send")) {
            Thread.sleep(1000)
            System.exit(0)
        }
    }

    def main(args: Array[String]) {
        procArgs(args)

        if (!PreventMultiInstance.tryLock) {
            messageDoubleLaunch
            System.exit(1)
        }

        ctx.loadProperties
        ctx.setSystemTray

        if (!Hook.setMouseHook) {
            Dialog.errorMessage("Failed mouse hook install: " + Windows.getLastErrorMessage)
            System.exit(1)
        }
        
        logger.debug("Mouse hook installed")

        swtMessageLoop
        logger.debug("Exit message loop")
        procExit
    }
}