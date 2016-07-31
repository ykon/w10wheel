package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

trait KeyboardEvent {
    val info: KHookInfo
    
    def vkCode = info.vkCode
    def name = this.getClass.getSimpleName + s" ($vkCode)"
    
    def sameEvent(ke2: KeyboardEvent) = (this, ke2) match {
        case (KeyDown(_), KeyDown(_)) => true
        case (KeyUp(_), KeyUp(_)) => true
        //case (SyskeyDown(_), SyskeyDown(_)) => true
        //case (SyskeyUp(_), SyskeyUp(_)) => true
        case _ => false
    }
    
    def sameKey(ke2: KeyboardEvent) =
        (this.vkCode == ke2.vkCode)
        
    def same(ke2: KeyboardEvent) =
        sameEvent(ke2) && sameKey(ke2)
}
case class KeyDown (info: KHookInfo) extends KeyboardEvent
case class KeyUp (info: KHookInfo) extends KeyboardEvent
//case class SyskeyDown(info: KHookInfo) extends KeyboardEvent
//case class SyskeyUp(info: KHookInfo) extends KeyboardEvent

object Keyboard {
    private val vkCodeMap = Map(
        "VK_PAUSE" -> 0x13,
        "VK_CAPITAL" -> 0x14,
        "VK_CONVERT" -> 0x1C,
        "VK_NONCONVERT" -> 0x1D,
        "VK_SNAPSHOT" -> 0x2C,
        "VK_LWIN" -> 0x5B,
        "VK_RWIN" -> 0x5C,
        "VK_APPS" -> 0x5D,
        "VK_NUMLOCK" -> 0x90,
        "VK_SCROLL" -> 0x91,
        "VK_LSHIFT" -> 0xA0,
        "VK_RSHIFT" -> 0xA1,
        "VK_LCONTROL" -> 0xA2,
        "VK_RCONTROL" -> 0xA3,
        "VK_LMENU" -> 0xA4,
        "VK_RMENU" -> 0xA5
    )
    
    private val revVKCodeMap = vkCodeMap.map(_.swap)
    
    def getVKCode(name: String) = vkCodeMap(name)  
    def getName(vkCode: Int) = revVKCodeMap(vkCode)
}