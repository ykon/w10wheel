package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.concurrent._
import ExecutionContext.Implicits.global

import com.sun.jna.platform.win32.WinDef.LRESULT
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

object EventHandler {
	private val ctx = Context
	private val logger = ctx.logger
	
	private var lastEvent: MouseEvent = null
	private var lastLD: MouseEvent = null
	private var lastRD: MouseEvent = null
	private var lastMD: MouseEvent = null
	private var lastX1D: MouseEvent = null
	private var lastX2D: MouseEvent = null
	
	/*
	private var lastResendLD: MouseEvent = null
	private var lastResendRD: MouseEvent = null
	*/
	
	private var __callNextHook: () => LRESULT = null
	
	def setCallNextHook(f: () => LRESULT) =
		__callNextHook = f
	
	private def callNextHook: Option[LRESULT] =
		Some(__callNextHook())
		
	private def suppress: Option[LRESULT] =
	    Some(new LRESULT(1))
	    
	/*
	private def setLastResend(me: MouseEvent) = me match {
		case LeftDown(_) => lastResendLD = me
		case RightDown(_) => lastResendRD = me
		case _ => {}
	}
	
	private def getLastResendDown(down: MouseEvent) = down match {
		case LeftDown(_) => lastResendLD
		case RightDown(_) => lastResendRD
	}
	*/
	
	private def setLastEvent(me: MouseEvent) = {
		lastEvent = me
		
		me match {
			case LeftDown(_) => lastLD = me
			case RightDown(_) => lastRD = me
			case MiddleDown(_) => lastMD = me
			case X1Down(_) => lastX1D = me
			case X2Down(_) => lastX2D = me
			case _ => {}
		}
	}
	
	private def getLastDown(up: MouseEvent) = up match {
		case LeftUp(_) => lastLD
		case RightUp(_) => lastRD
		case MiddleUp(_) => lastMD
		case X1Up(_) => lastX1D
		case X2Up(_) => lastX2D
	}
	
	private def skipResendEvent(me: MouseEvent): Option[LRESULT] = {
		//if (ctx.checkSkip(me)) {
		if (Windows.isResendEvent(me)) {
			logger.debug(s"skip resend event: ${me.name}")
			//setLastResend(me)
			callNextHook
		}
		else
			None
	}
	
	private def skipFirstUp(me: MouseEvent): Option[LRESULT] = {
		if (lastEvent == null) {
			logger.debug(s"skip first up event: ${me.name}")
			callNextHook
		}
		else
			None
	}
	
	private def skipFirstSingle(me: MouseEvent): Option[LRESULT] = {
		if (Mouse.isSingleEvent(lastEvent)) {
			logger.debug(s"skip first single event: ${me.name}")
			callNextHook
		}
		else
			None
	}
	
	private def skipFirstLR(me: MouseEvent): Option[LRESULT] = {
		if (!Mouse.isSingleEvent(lastEvent)) {
			logger.debug(s"skip first left or right event: ${me.name}")
			callNextHook
		}
		else
			None
	}
	
	private def checkSameLastEvent(me: MouseEvent): Option[LRESULT] = {
		if (Mouse.sameEvent(me, lastEvent)) {
			logger.warn(s"same last event: ${me.name}")
			callNextHook
			
			//logger.warn(me.toString())
			//logger.warn(lastEvent.toString())
			//suppress
		}
		else {
			//lastEvent = me
			setLastEvent(me)
			None
		}
	}
	
	private def checkExitScrollDown(me: MouseEvent): Option[LRESULT] = {
		if (ctx.isScrollMode) {
			logger.debug(s"exit scroll mode: ${me.name}");
			ctx.exitScrollMode
			me.suppressed = true
			suppress
		}
		else
			None
	}
	
	private def passSingleTrigger(me: MouseEvent): Option[LRESULT] = {
		if (ctx.isSingleTrigger) {
			logger.debug(s"pass single trigger: ${me.name}")
			callNextHook
		}
		else
			None
	}
	
	private def checkExitScrollUp(me: MouseEvent): Option[LRESULT] = {
		if (ctx.isScrollMode) {
			if (ctx.checkTimeExitScroll(me.info.time)) {
				logger.debug(s"exit scroll mode: ${me.name}")
				ctx.exitScrollMode
			}
			else
				logger.debug(s"continue scroll mode: ${me.name}")

			suppress
		}
		else
			None
	}

	private def offerEventWaiter(me: MouseEvent): Option[LRESULT] = {
		if (EventWaiter.isWaiting && EventWaiter.offer(me)) {
			logger.debug(s"success to offer to the event waiter: ${me.name}")
			suppress
		}
		else
			None
	}
	
	private def checkSuppressedDown(up: MouseEvent): Option[LRESULT] = {
		val down = getLastDown(up)
		
		if (down != null && down.suppressed) {
			logger.debug(s"after suppressed down event: ${up.name}")
			suppress
		}
		else
			None
	}
	
	/*
	private def isPassedResend(down: MouseEvent): Boolean = {
		val last = getLastResendDown(down)
		
		if (last != null)
			down.info.dwExtraInfo.intValue() == last.info.dwExtraInfo.intValue()
		else
			false
	}
	*/
	
	private def checkResendDown(up: MouseEvent): Option[LRESULT] = {
		val down = getLastDown(up)
		
		if (down != null && down.resent) {
			/*
			if (isPassedResend(down)) {
				logger.debug(s"pass after resendDown: ${up.name}")
				callNextHook
			}
			else {
				logger.warn(s"resendUp because not passed resendDown: ${up.name}")
				Windows.resendUp(up)
				suppress
			}
			*/
			
			// waiter thread timing issue
			logger.debug(s"forced to resendUp: ${up.name}")
			Windows.resendUp(up)
			suppress
		}
		else {
			logger.debug(s"down evnet is not resend: ${up.name}")
			None
		}
	}
	
	private def checkTriggerWaitStart(me: MouseEvent): Option[LRESULT] = {
		if (ctx.isLRTrigger || ctx.isTrigger(me)) {
			logger.debug(s"start wait trigger: ${me.name}")
			EventWaiter.start(me)
			suppress
		}
		else
			None
	}
	
	private def checkKeySendMiddle(me: MouseEvent): Option[LRESULT] = {
		if (Windows.getAsyncShiftState || Windows.getAsyncCtrlState || Windows.getAsyncAltState) {
			logger.debug(s"send middle click")
			Windows.resendClick(MiddleClick(me.info))
			me.suppressed = true
			suppress
		}
		else
			None
	}
	
	private def checkTriggerScrollStart(me: MouseEvent): Option[LRESULT] = {
		if (ctx.isTrigger(me)) {
			logger.debug(s"start scroll mode: ${me.name}");
			ctx.startScrollMode(me.info)
			suppress
		}
		else
			None
	}
	
	private def startScrollLROnly(me: MouseEvent): Option[LRESULT] = {
		logger.debug(s"start scroll mode: ${me.name}");
		ctx.startScrollMode(me.info)
		
		drag = dragLROnly
		dragged = false
			
		suppress
	}
	
	private def exitAndResendLROnly(me: MouseEvent): Option[LRESULT] = {
		logger.debug(s"exit scroll mode: ${me.name}")
		ctx.exitScrollMode
		
		if (!dragged) {
			logger.debug(s"resend click: ${me.name}")
			
			me match {
				case LeftUp(info) => Windows.resendClick(LeftClick(info))
				case RightUp(info) => Windows.resendClick(RightClick(info))
			}
		}
		
		suppress
	}
	
	private def passNotTrigger(me: MouseEvent): Option[LRESULT] = {
		if (!ctx.isTrigger(me)) {
			logger.debug(s"pass not trigger: ${me.name}")
			callNextHook
		}
		else
			None
	}
	
	private def passNotTriggerLR(me: MouseEvent): Option[LRESULT] = {
		if (!ctx.isLRTrigger && !ctx.isTrigger(me)) {
			logger.debug(s"pass not trigger: ${me.name}")
			callNextHook
		}
		else
			None
	}
	
	private def passNotTriggerLROnly(me: MouseEvent): Option[LRESULT] = {
		if (!ctx.isTriggerLROnly(me)) {
			logger.debug(s"pass not trigger: ${me.name}")
			callNextHook
		}
		else
			None
	}
	
	private def endCallNextHook(me: MouseEvent, msg: Option[String] = None): Option[LRESULT] = {
		msg.foreach(logger.debug(_))
		callNextHook
	}
	
	private def endNotTrigger(me: MouseEvent): Option[LRESULT] = {
		endCallNextHook(me, Some(s"pass not trigger: ${me.name}"))
	}
	
	private def endUnknownEvent(me: MouseEvent): Option[LRESULT] = {
		logger.warn(s"unknown event: ${me.name}")
		callNextHook
	}
	
	private def endSuppress(me: MouseEvent, msg: Option[String] = None): Option[LRESULT] = {
		msg.foreach(logger.debug(_))
		suppress
	}
	
	private def endIllegalState(me: MouseEvent): Option[LRESULT] = {
		logger.warn(s"illegal state: ${me.name}")
		suppress
	}
	
	type Checkers = Stream[MouseEvent => Option[LRESULT]]
	
	private def getResult(me: MouseEvent, cs: Checkers): LRESULT =
		cs.flatMap(_.apply(me)).head

	private def lrDown(me: MouseEvent): LRESULT = {		
		val checkers: Checkers = Stream(
				skipResendEvent,
				checkSameLastEvent,
				checkExitScrollDown,
				passSingleTrigger,
				offerEventWaiter,
				checkTriggerWaitStart,
				endNotTrigger)
		
		getResult(me, checkers)
	}
	
	private def lrUp(me: MouseEvent): LRESULT = {
		val checkers: Checkers = Stream(
				skipResendEvent,
				skipFirstUp,
				skipFirstSingle,
				checkSameLastEvent,
				passSingleTrigger,
				checkExitScrollUp,
				passNotTriggerLR,
				offerEventWaiter,
				checkResendDown,
				checkSuppressedDown,
				endUnknownEvent)
				
		getResult(me, checkers)
	}
	
	def lrOnlyDown(me: MouseEvent): LRESULT = {
		val checkers: Checkers = Stream(
				skipResendEvent,
				checkSameLastEvent,
				passNotTriggerLROnly,
				startScrollLROnly)
		
		getResult(me, checkers)
	}
	
	def leftDown(info: HookInfo): LRESULT = {
		//logger.debug("leftDown")
		val ld = LeftDown(info)
		if (ctx.isLROnlyTrigger) lrOnlyDown(ld) else lrDown(ld)
	}
	
	def rightDown(info: HookInfo): LRESULT = {
		//logger.debug("rightDown")
		
		val rd = RightDown(info)
		if (ctx.isLROnlyTrigger) lrOnlyDown(rd) else lrDown(rd)
	}
	
	def lrOnlyUp(me: MouseEvent): LRESULT = {
		val checkers: Checkers = Stream(
				skipResendEvent,
				skipFirstUp,
				skipFirstSingle,
				checkSameLastEvent,
				passNotTriggerLROnly,
				exitAndResendLROnly)
				
		getResult(me, checkers)
	}
	
	def leftUp(info: HookInfo): LRESULT = {
		//logger.debug("leftUp");
		
		val lu = LeftUp(info)
		if (ctx.isLROnlyTrigger) lrOnlyUp(lu) else lrUp(lu)
	}
	
	def rightUp(info: HookInfo): LRESULT = {
		//logger.debug("rightUp")
		val ru = RightUp(info)
		if (ctx.isLROnlyTrigger) lrOnlyUp(ru) else lrUp(ru)
	}
	
	private def singleDown(me: MouseEvent): LRESULT = {
		val checkers: Checkers = Stream(
				skipResendEvent,
				checkSameLastEvent,
				checkExitScrollDown,
				passNotTrigger,
				checkKeySendMiddle,
				checkTriggerScrollStart,
				endIllegalState)
		
		getResult(me, checkers)
	}
	
	def middleDown(info: HookInfo): LRESULT = {			
		singleDown(MiddleDown(info))
	}
	
	def xDown(info: HookInfo): LRESULT = {
	    val evt = if (Mouse.isXButton1(info.mouseData)) X1Down(info) else X2Down(info)
	    singleDown(evt)
	}
	
	private def singleUp(me: MouseEvent): LRESULT = {
		val checkers: Checkers = Stream(
				skipResendEvent,
				skipFirstUp,
				skipFirstLR,
				checkSameLastEvent,
				passNotTrigger,
				checkExitScrollUp,
				checkSuppressedDown,
				endIllegalState)
				
		getResult(me, checkers)
	}
	
	def middleUp(info: HookInfo): LRESULT = {
		singleUp(MiddleUp(info))
	}
	
	def xUp(info: HookInfo): LRESULT = {
	    val evt = if (Mouse.isXButton1(info.mouseData)) X1Up(info) else X2Up(info)
	    singleUp(evt)
	}
	
	def dragDefault(info: HookInfo): Unit = {}
	
	private var drag: HookInfo => Unit = dragDefault
	private var dragged = false

	def dragLROnly(info: HookInfo): Unit = {
		if (ctx.isCursorChange)
			Windows.changeCursor
		
		drag = dragDefault
		dragged = true
	}
	
	def move(info: HookInfo): LRESULT = {		
		if (ctx.isScrollMode) {
			drag(info)
			Windows.sendWheel(info.pt)
			suppress.get
		}
		else if (EventWaiter.isWaiting && EventWaiter.offer(Move(info))) {
			logger.debug("success to offer: Move")  
			suppress.get
		}
		else
			callNextHook.get
	}
	
	/*
	def changeTrigger: Unit = {
		logger.debug("changeTrigger")
		drag = if (ctx.isLROnlyTrigger) dragLROnly else dragDefault
	}
	*/
}