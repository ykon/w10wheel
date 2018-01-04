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

    private def sendMessage(msg: Int): Unit = {
        val pt = Windows.getCursorPos
        Windows.sendInputDirect(pt, 1, MOUSEEVENTF_HWHEEL, 0, msg)
    }

    def sendExit(): Unit = {
        logger.debug("send W10_MESSAGE_EXIT")
        sendMessage(W10_MESSAGE_EXIT)
    }

    private def recvExit(): Unit = {
        logger.debug("recv W10_MESSAGE_EXIT")
        Context.exitAction(null)
    }

    private def setBoolBit(msg: Int, b: Boolean): Int =
        msg | (if (b) 0x10000000 else 0x00000000)

    private def getBoolBit(msg: Int): Boolean =
        (msg & 0xF0000000) != 0

    private def getFlag(msg: Int): Int =
        msg & 0x0FFFFFFF

    def sendPassMode(b: Boolean): Unit = {
        logger.debug("send W10_MESSAGE_PASSMODE")
        val msg = setBoolBit(W10_MESSAGE_PASSMODE, b)
        sendMessage(msg)
    }

    private def recvPassMode(msg: Int): Unit = {
        logger.debug("recv W10_MESSAGE_PASSMODE")
        Context.setPassMode(getBoolBit(msg))
    }

    def sendReloadProp: Unit = {
        logger.debug("send W10_MESSAGE_RELOAD_PROP")
        sendMessage(W10_MESSAGE_RELOAD_PROP)
    }

    private def recvReloadProp: Unit = {
        logger.debug("recv W10_MESSAGE_RELOAD_PROP")
        Context.reloadProperties
    }

    def sendInitState: Unit = {
        logger.debug("send W10_MESSAGE_INIT_STATE")
        sendMessage(W10_MESSAGE_INIT_STATE)
    }

    private def recvInitState: Unit = {
        logger.debug("recv W10_MESSAGE_INIT_STATE")
        Context.initState
    }

    def procMessage(msg: Int): Boolean = {
        getFlag(msg) match {
            case W10_MESSAGE_EXIT => recvExit; true
            case W10_MESSAGE_PASSMODE => recvPassMode(msg); true
            case W10_MESSAGE_RELOAD_PROP => recvReloadProp; true
            case W10_MESSAGE_INIT_STATE => recvInitState; true
            case _ => false
        }
    }
}

