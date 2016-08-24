package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import win32ex.WinUserX._
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

trait MouseEvent {
    val info: HookInfo
    def name = getClass.getSimpleName

    def isDown = this match {
        case LeftDown(_) | RightDown(_) | MiddleDown(_) | X1Down(_) | X2Down(_) => true
        case _ => false
    }
    
    def isUp = this match {
        case LeftUp(_) | RightUp(_) | MiddleUp(_) | X1Up(_) | X2Up(_) => true
        case _ => false
    }

    def isSingle = this match {
        case MiddleDown(_) | MiddleUp(_) => true
        case X1Down(_) | X1Up(_) => true
        case X2Down(_) | X2Up(_) => true
        case _ => false
    }
    
    def isLR = this match {
        case LeftDown(_) | LeftUp(_) | RightDown(_) | RightUp(_) => true
        case _ => false
    }
    
    def sameEvent(me2: MouseEvent) = {
        (this, me2) match {
            case (LeftDown(_), LeftDown(_)) => true
            case (LeftUp(_), LeftUp(_)) => true
            case (RightDown(_), RightDown(_)) => true
            case (RightUp(_), RightUp(_)) => true
            case (MiddleDown(_), MiddleDown(_)) => true
            case (MiddleUp(_), MiddleUp(_)) => true
            case (X1Down(_), X1Down(_)) => true
            case (X1Up(_), X1Up(_)) => true
            case (X2Down(_), X2Down(_)) => true
            case (X2Up(_), X2Up(_)) => true
            case _ => false
        }
    }
    
    def sameButton(me2: MouseEvent) = {
        (this, me2) match {
            case (LeftDown(_), LeftUp(_)) => true
            case (LeftUp(_), LeftDown(_)) => true
            case (RightDown(_), RightUp(_)) => true
            case (RightUp(_), RightDown(_)) => true
            case (MiddleDown(_), MiddleUp(_)) => true
            case (MiddleUp(_), MiddleDown(_)) => true
            case (X1Down(_), X1Up(_)) => true
            case (X1Up(_), X1Down(_)) => true
            case (X2Down(_), X2Up(_)) => true
            case (X2Up(_), X2Down(_)) => true
            case _ => false
        }
    }
}
case class LeftDown (info: HookInfo) extends MouseEvent
case class LeftUp (info: HookInfo) extends MouseEvent
case class RightDown (info: HookInfo) extends MouseEvent
case class RightUp (info: HookInfo) extends MouseEvent
case class MiddleDown (info: HookInfo) extends MouseEvent
case class MiddleUp (info: HookInfo) extends MouseEvent
case class X1Down (info: HookInfo) extends MouseEvent
case class X1Up (info: HookInfo) extends MouseEvent
case class X2Down (info: HookInfo) extends MouseEvent
case class X2Up (info: HookInfo) extends MouseEvent
case class Move (info: HookInfo) extends MouseEvent

object LeftEvent {
    def unapply(me: MouseEvent) = me match {
        case LeftDown(_) | LeftUp(_) => Some(me.info)
        case _ => None
    }
}

object RightEvent {
    def unapply(me: MouseEvent) = me match {
        case RightDown(_) | RightUp(_) => Some(me.info)
        case _ => None
    }
}

trait MouseClick {
    val info: HookInfo
    def name = getClass.getSimpleName
}
case class LeftClick (info: HookInfo) extends MouseClick
case class RightClick (info: HookInfo) extends MouseClick
case class MiddleClick (info: HookInfo) extends MouseClick
case class X1Click (info: HookInfo) extends MouseClick
case class X2Click (info: HookInfo) extends MouseClick

trait Trigger {
    def name = getClass.getSimpleName

    def isSingle = this match {
        case MiddleTrigger() | X1Trigger() | X2Trigger() => true
        case _ => false
    }

    def isDouble = this match {
        case LRTrigger() | LeftTrigger() | RightTrigger() => true
        case _ => false
    }

    def isDrag = this match {
        case LeftDragTrigger() | RightDragTrigger() | MiddleDragTrigger() | X1DragTrigger() | X2DragTrigger() => true
        case _ => false
    }

    def isNone = (this == NoneTrigger())
}
case class LRTrigger () extends Trigger
case class LeftTrigger () extends Trigger
case class RightTrigger () extends Trigger
case class MiddleTrigger () extends Trigger
case class X1Trigger () extends Trigger
case class X2Trigger () extends Trigger

case class LeftDragTrigger () extends Trigger
case class RightDragTrigger () extends Trigger
case class MiddleDragTrigger () extends Trigger
case class X1DragTrigger () extends Trigger
case class X2DragTrigger () extends Trigger

case class NoneTrigger () extends Trigger

object Mouse {
    def isXButton1(mouseData: Int) =
        (mouseData >>> 16) == XBUTTON1
    
    def isXButton2(mouseData: Int) =
        !isXButton1(mouseData)
        
    def getTrigger(me: MouseEvent): Trigger = me match {
        case LeftDown(_) | LeftUp(_) => LeftTrigger()
        case RightDown(_) | RightUp(_) => RightTrigger()
        case MiddleDown(_) | MiddleUp(_) => MiddleTrigger()
        case X1Down(_) | X1Up(_) => X1Trigger()
        case X2Down(_) | X2Up(_) => X2Trigger()
    }
    
    def getTrigger(s: String): Trigger = s match {
        case "LR" | "LRTrigger" => LRTrigger()
        case "Left" | "LeftTrigger" => LeftTrigger()
        case "Right" | "RightTrigger" => RightTrigger()
        case "Middle" | "MiddleTrigger" => MiddleTrigger()
        case "X1" | "X1Trigger" => X1Trigger()
        case "X2" | "X2Trigger" => X2Trigger()
        case "LeftDrag" | "LeftDragTrigger" => LeftDragTrigger()
        case "RightDrag" | "RightDragTrigger" => RightDragTrigger()
        case "MiddleDrag" | "MiddleDragTrigger" => MiddleDragTrigger()
        case "X1Drag" | "X1DragTrigger" => X1DragTrigger()
        case "X2Drag" | "X2DragTrigger" => X2DragTrigger()
        case "None" | "NoneTrigger" => NoneTrigger()
    }
}