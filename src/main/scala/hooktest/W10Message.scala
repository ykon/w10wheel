package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import win32ex.User32Ex._

object W10Message {
    private val logger = Context.logger

    private val W10_MESSAGE_BASE = 264816059 & 0x0FFFFFFF
    val W10_MESSAGE_EXIT = W10_MESSAGE_BASE + 1
    val W10_MESSAGE_PASSMODE = W10_MESSAGE_BASE + 2
    val W10_MESSAGE_RELOAD_PROP = W10_MESSAGE_BASE + 3
    val W10_MESSAGE_INIT_STATE = W10_MESSAGE_BASE + 4

    private def sendMessage(msg: Int) {
        val pt = Windows.getCursorPos
        Windows.sendInputDirect(pt, 1, MOUSEEVENTF_HWHEEL, 0, msg)
    }

    def sendExit {
        logger.debug("send W10_MESSAGE_EXIT")
        sendMessage(W10_MESSAGE_EXIT)
    }

    private def recvExit: Boolean = {
        logger.debug("recv W10_MESSAGE_EXIT")
        Context.exitAction(null)
        true
    }

    private def setBoolBit(msg: Int, b: Boolean) =
        msg | (if (b) 0x10000000 else 0x00000000)

    private def getBoolBit(msg: Int) =
        (msg & 0xF0000000) != 0

    private def getFlag(msg: Int) =
        msg & 0x0FFFFFFF

    def sendPassMode(b: Boolean) {
        logger.debug("send W10_MESSAGE_PASSMODE")
        val msg = setBoolBit(W10_MESSAGE_PASSMODE, b)
        sendMessage(msg)
    }

    private def recvPassMode(msg: Int): Boolean = {
        logger.debug("recv W10_MESSAGE_PASSMODE")
        Context.setPassMode(getBoolBit(msg))
        true
    }

    def sendReloadProp {
        logger.debug("send W10_MESSAGE_RELOAD_PROP")
        sendMessage(W10_MESSAGE_RELOAD_PROP)
    }

    private def recvReloadProp: Boolean = {
        logger.debug("recv W10_MESSAGE_RELOAD_PROP")
        Context.reloadProperties
        true
    }

    def sendInitState {
        logger.debug("send W10_MESSAGE_INIT_STATE")
        sendMessage(W10_MESSAGE_INIT_STATE)
    }

    private def recvInitState: Boolean = {
        logger.debug("recv W10_MESSAGE_INIT_STATE")
        Context.initState
        true
    }

    def procMessage(msg: Int): Boolean = {
        getFlag(msg) match {
            case W10_MESSAGE_EXIT => recvExit
            case W10_MESSAGE_PASSMODE => recvPassMode(msg)
            case W10_MESSAGE_RELOAD_PROP => recvReloadProp
            case W10_MESSAGE_INIT_STATE => recvInitState
            case _ => false
        }
    }
}

