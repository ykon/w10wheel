package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.collection.immutable.List

// SWT
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.{Dialog as _, *}

object W10Wheel {
    private val ctx = Context
    private val logger = Logger.getLogger()
    private val display = Display.getDefault
    
    private lazy val convLang: String => String = ctx.convLang

    val shell = new Shell(display)

    private var exited = false

    // Called when the message loop ends and when the session ends (on the thread of the message
    // window): done once. The other call waits for it to be done, or the process could end while
    // the properties are stored.
    def procExit: Unit = synchronized {
        if (!exited) {
            exited = true
            closeAll()
        }
    }

    private def closeAll(): Unit = {
        logger.debug("procExit")

        // No more downs are resent: the hooks are removed next (see InputSender.beginClose)
        InputSender.beginClose()
        Hook.unhook

        // Cancel an LR trigger wait: after its timeout it would resend a down whose up does not
        // come any more (the hook is removed). Must not stop the rest (storing the properties).
        try {
            EventWaiter.offer(Cancel(null))
        }
        catch {
            case e: Exception => logger.warn(s"procExit: $e")
        }

        // Exiting while scrolling (e.g. --sendExit): restore the cursor and unregister RawInput.
        // This also waits for a scroll start in progress and refuses later ones. Maybe on the
        // thread of the message window (session end), where an error dialog would throw.
        try {
            ctx.closeScrollMode
        }
        catch {
            case e: Exception => logger.warn(s"procExit: $e")
        }

        // Wait for a resent click being sent (so that its up is sent), send the ups still queued
        // and stop sending
        InputSender.close()

        ctx.storeProperties
        PreventMultiInstance.unlock
    }

    private def messageDoubleLaunch: Unit = {
        Dialog.errorMessage(convLang("Double Launch?"))
    }

    private def swtMessageLoop: Unit = {
        while (!shell.isDisposed) {
            if (!display.readAndDispatch)
                display.sleep
        }
    }

    def exitMessageLoop: Unit = {
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

    private def setSelectedProperties(name: String): Unit = {
        // Default is always valid (its file is not written until exit)
        if (name == Properties.DEFAULT_DEF || Properties.exists(name))
            Context.setSelectedProperties(name)
        else
            Dialog.errorMessage(s"${convLang("Properties does not exist")}: $name")
    }

    private def unknownCommand(name: String): Unit = {
        Dialog.errorMessage(s"${convLang("Unknown Command")}: $name", convLang("Command Error"))
        System.exit(1)
    }

    private def procArgs(args: Array[String]): Unit = {
        logger.debug("procArgs")
        
        // Some(false) if the command could not be sent (SendInput failed)
        val sent = args.toList match {
            case "--sendExit" :: _ => Some(W10Message.sendExit())
            case "--sendPassMode" :: rest => Some(W10Message.sendPassMode(getBool(rest)))
            case "--sendReloadProp" :: _ => Some(W10Message.sendReloadProp)
            case "--sendInitState" :: _ => Some(W10Message.sendInitState)
            case name :: _ if name.startsWith("--") => unknownCommand(name); None
            case name :: _ => setSelectedProperties(name); None
            case _ => None
        }

        sent.foreach(ok =>
            if (ok) {
                Thread.sleep(1000)
                System.exit(0)
            }
            else
                System.exit(1)
        )
    }

    def main(args: Array[String]): Unit = {
        procArgs(args)

        if (!PreventMultiInstance.tryLock) {
            messageDoubleLaunch
            System.exit(1)
        }

        // If the file cannot be read (an error is shown), the default settings are used, and not
        // stored at exit (Context.storeProperties)
        ctx.loadProperties
        ctx.setSystemTray()

        Hook.setMouseHook match {
            case Some(err) =>
                // The icon would remain in the tray after System.exit
                ctx.hideSystemTray()
                Dialog.errorMessage(s"${convLang("Failed mouse hook install")}: $err")
                System.exit(1)
            case None => ()
        }
        
        logger.debug("Mouse hook installed")

        swtMessageLoop
        logger.debug("Exit message loop")
        procExit
    }
}