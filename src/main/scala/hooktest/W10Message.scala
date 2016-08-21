package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import win32ex.WinUserX._

object W10Message {
    private val logger = Context.logger
    
    private val W10_MESSAGE_BASE = 264816059 & 0x0FFFFFFF
    val W10_MESSAGE_EXIT = W10_MESSAGE_BASE + 1
    val W10_MESSAGE_PASSMODE = W10_MESSAGE_BASE + 2
    
    private def sendMessage(msg: Int) {
        val pt = Windows.getCursorPos
        Windows.sendInputDirect(pt, 1, MOUSEEVENTF_HWHEEL, 0, msg)
    }
    
    def sendExit {
        logger.debug("send W10_MESSAGE_EXIT")
        sendMessage(W10_MESSAGE_EXIT)
    }
    
    def setBoolBit(msg: Int, b: Boolean) =
        msg | (if (b) 0x10000000 else 0x00000000)
    
    def getBoolBit(msg: Int) =
        (msg & 0xF0000000) != 0
    
    def getFlag(msg: Int) =
        msg & 0x0FFFFFFF
    
    def sendPassMode(b: Boolean) {
        logger.debug("send W10_MESSAGE_PASSMODE")
        val msg = setBoolBit(W10_MESSAGE_PASSMODE, b)
        sendMessage(msg)
    }
}

