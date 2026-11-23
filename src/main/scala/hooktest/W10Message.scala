package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import win32ex.User32Ex._

object W10Message {
    private val logger = Logger.getLogger()

    private val W10_MESSAGE_BASE = 264816059 & 0x0FFFFFFF
    val W10_MESSAGE_EXIT = W10_MESSAGE_BASE + 1
    val W10_MESSAGE_PASSMODE = W10_MESSAGE_BASE + 2
    val W10_MESSAGE_RELOAD_PROP = W10_MESSAGE_BASE + 3
    val W10_MESSAGE_INIT_STATE = W10_MESSAGE_BASE + 4

    // false if SendInput failed
    private def sendMessage(msg: Int): Boolean = {
        val pt = Windows.getCursorPos
        Windows.sendInputDirect(pt, 1, MOUSEEVENTF_HWHEEL, 0, msg)
    }

    def sendExit(): Boolean = {
        logger.debug("send W10_MESSAGE_EXIT")
        sendMessage(W10_MESSAGE_EXIT)
    }

    private def recvExit(): Unit = {
        logger.debug("recv W10_MESSAGE_EXIT")
        Context.exitAction()
    }

    private def setBoolBit(msg: Int, b: Boolean): Int =
        msg | (if (b) 0x10000000 else 0x00000000)

    private def getBoolBit(msg: Int): Boolean =
        (msg & 0xF0000000) != 0

    private def getFlag(msg: Int): Int =
        msg & 0x0FFFFFFF

    def sendPassMode(b: Boolean): Boolean = {
        logger.debug("send W10_MESSAGE_PASSMODE")
        val msg = setBoolBit(W10_MESSAGE_PASSMODE, b)
        sendMessage(msg)
    }

    private def recvPassMode(msg: Int): Unit = {
        logger.debug("recv W10_MESSAGE_PASSMODE")
        Context.setPassMode(getBoolBit(msg))
    }

    def sendReloadProp: Boolean = {
        logger.debug("send W10_MESSAGE_RELOAD_PROP")
        sendMessage(W10_MESSAGE_RELOAD_PROP)
    }

    private def recvReloadProp: Unit = {
        logger.debug("recv W10_MESSAGE_RELOAD_PROP")
        Context.reloadProperties
    }

    def sendInitState: Boolean = {
        logger.debug("send W10_MESSAGE_INIT_STATE")
        sendMessage(W10_MESSAGE_INIT_STATE)
    }

    private def recvInitState: Unit = {
        logger.debug("recv W10_MESSAGE_INIT_STATE")
        Context.initState
    }

    // Run after the mouse hook has returned, on the hook thread (HookThread.post): a hook must return
    // quickly, or Windows removes it (W10Wheel would stop working, saying nothing). A load reads the
    // file and installs the keyboard hook again (an error shown if it fails). After the message loop
    // has ended (W10Wheel is exiting), the command is dropped. An exception is logged, as the hook
    // does (Hook.mouseProc).
    private def afterHook(f: () => Unit): Unit = {
        HookThread.post(() => {
            try {
                f()
            }
            catch {
                case e: Exception => logger.warn(s"command: $e")
            }
        })
    }

    // Called by the mouse hook (Hook.procCommand): true if msg is a command (its event is not
    // passed). All run after the hook, in the order they have been received (e.g. --sendPassMode
    // after --sendReloadProp sent just before).
    def procMessage(msg: Int): Boolean = {
        getFlag(msg) match {
            case W10_MESSAGE_EXIT => afterHook(() => recvExit()); true
            case W10_MESSAGE_PASSMODE => afterHook(() => recvPassMode(msg)); true
            case W10_MESSAGE_RELOAD_PROP => afterHook(() => recvReloadProp); true
            case W10_MESSAGE_INIT_STATE => afterHook(() => recvInitState); true
            case _ => false
        }
    }
}

