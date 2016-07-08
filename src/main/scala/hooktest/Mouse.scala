package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import win32ex.WinUserX._
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

trait MouseEvent {
	val info: HookInfo
	def name = Mouse.getSimpleName(this)
	def isDown = Mouse.isDownEvent(this)
	def isUp = Mouse.isUpEvent(this)
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

trait MouseClick {
	val info: HookInfo
	def name = Mouse.getSimpleName(this)
}
case class LeftClick (info: HookInfo) extends MouseClick
case class RightClick (info: HookInfo) extends MouseClick
case class MiddleClick (info: HookInfo) extends MouseClick
case class X1Click (info: HookInfo) extends MouseClick
case class X2Click (info: HookInfo) extends MouseClick

trait Trigger {
	def name = Mouse.getSimpleName(this)
}
case class LRTrigger () extends Trigger
case class LeftTrigger () extends Trigger
case class RightTrigger () extends Trigger
case class MiddleTrigger () extends Trigger
case class X1Trigger () extends Trigger
case class X2Trigger () extends Trigger
case class LeftOnlyTrigger () extends Trigger
case class RightOnlyTrigger () extends Trigger

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
		case "LRTrigger" => LRTrigger()
		case "Left" | "LeftTrigger" => LeftTrigger()
		case "Right" | "RightTrigger" => RightTrigger()
		case "Middle" | "MiddleTrigger" => MiddleTrigger()
		case "X1" | "X1Trigger" => X1Trigger()
		case "X2" | "X2Trigger" => X2Trigger()
		case "LeftOnly" | "LeftOnlyTrigger" => LeftOnlyTrigger()
		case "RightOnly" | "RightOnlyTrigger" => RightOnlyTrigger()
	}
	
	def isSingleEvent(event: MouseEvent) = event match {
		case MiddleDown(_) | MiddleUp(_) => true
		case X1Down(_) | X1Up(_) => true
		case X2Down(_) | X2Up(_) => true
		case _ => false
	}
	
	def getSimpleName(obj: Object) = {
		obj.getClass.getSimpleName
	}
	
	def isAdapted(me1: MouseEvent, me2: MouseEvent) = {
		me1 match {
			case LeftDown(_) => me2 match {
				case LeftUp(_) | RightDown(_) | Move(_) => true
				case _ => false
			}
			case RightDown(_) => me2 match {
				case RightUp(_) | LeftDown(_) | Move(_) => true
				case _ => false
			}
		}
	}
	
	def sameDownUp(down: MouseEvent, up: MouseEvent) = {
		down match {
			case LeftDown(_) => up match {
				case LeftUp(_) => true
				case _ => false
			}
			case RightDown(_) => up match {
				case RightUp(_) => true
				case _ => false
			}
		}
	}
	
	
	def sameEvent(me1: MouseEvent, me2: MouseEvent) = {
		if (me1 != null && me2 != null)
			me1.getClass == me2.getClass
		else
			false
	}
	
	def isDownEvent(me: MouseEvent) = me match {
		case LeftDown(_) | RightDown(_) | MiddleDown(_) | X1Down(_) | X2Down(_) => true
		case _ => false
	}
	
	def isUpEvent(me: MouseEvent) = me match {
		case LeftUp(_) | RightUp(_) | MiddleUp(_) | X1Up(_) | X2Up(_) => true
		case _ => false
	}
}