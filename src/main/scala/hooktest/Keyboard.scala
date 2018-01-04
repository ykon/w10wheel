package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

sealed trait KeyboardEvent {
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

case class KNonEvent (info: KHookInfo) extends KeyboardEvent

object Keyboard {
    // https://msdn.microsoft.com/library/windows/desktop/dd375731
    private val vkCodeMap = Map(
        DataID.None -> 0,
        DataID.VK_TAB -> 0x09,
        DataID.VK_PAUSE -> 0x13,
        DataID.VK_CAPITAL -> 0x14,
        DataID.VK_CONVERT -> 0x1C,
        DataID.VK_NONCONVERT -> 0x1D,
        DataID.VK_PRIOR -> 0x21,
        DataID.VK_NEXT -> 0x22,
        DataID.VK_END -> 0x23,
        DataID.VK_HOME -> 0x24,
        DataID.VK_SNAPSHOT -> 0x2C,
        DataID.VK_INSERT -> 0x2D,
        DataID.VK_DELETE -> 0x2E,
        DataID.VK_LWIN -> 0x5B,
        DataID.VK_RWIN -> 0x5C,
        DataID.VK_APPS -> 0x5D,
        DataID.VK_NUMLOCK -> 0x90,
        DataID.VK_SCROLL -> 0x91,
        DataID.VK_LSHIFT -> 0xA0,
        DataID.VK_RSHIFT -> 0xA1,
        DataID.VK_LCONTROL -> 0xA2,
        DataID.VK_RCONTROL -> 0xA3,
        DataID.VK_LMENU -> 0xA4,
        DataID.VK_RMENU -> 0xA5
    )
    
    private val revVKCodeMap = vkCodeMap.map(_.swap)
    
    def getVKCode(name: String) = vkCodeMap(name)  
    def getName(vkCode: Int) = revVKCodeMap(vkCode)
}