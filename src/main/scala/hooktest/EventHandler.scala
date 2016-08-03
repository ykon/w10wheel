package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.annotation.tailrec

import com.sun.jna.platform.win32.WinDef.LRESULT
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

object EventHandler {
    private val ctx = Context
    private val logger = ctx.logger
    
    private var lastEvent: MouseEvent = null
    private var lastResendEvent: MouseEvent = null
    
    private var __callNextHook: () => LRESULT = null
    
    def setCallNextHook(f: () => LRESULT) =
        __callNextHook = f
    
    private def callNextHook: Option[LRESULT] = Some(__callNextHook())
    private def suppress: Option[LRESULT] = Some(new LRESULT(1))
        
    private def skipResendEventLR(me: MouseEvent): Option[LRESULT] = {
        if (Windows.isResendEvent(me)) {
            (lastResendEvent, me) match {
                case (LeftUp(_), LeftUp(_)) | (RightUp(_), RightUp(_)) => {
                    logger.warn(s"re-resend event: ${me.name}")
                    Windows.resendUp(me)
                    suppress
                }
                case _ => {
                    logger.debug(s"skip resend event: ${me.name}")
                    lastResendEvent = me
                    callNextHook       
                }
            }
        }
        else
            None
    }
    
    private def skipResendEventSingle(me: MouseEvent): Option[LRESULT] = {
        if (Windows.isResendEvent(me)) {
            logger.debug(s"skip resend event: ${me.name}")
            callNextHook
        }
        else
            None
    }
    
    private def skipFirstUp(me: MouseEvent): Option[LRESULT] = {
        if (lastEvent == null) {
            logger.debug(s"skip first Up: ${me.name}")
            callNextHook
        }
        else
            None
    }
    
    /*
    private def skipFirstUpOrSingle(me: MouseEvent): Option[LRESULT] = {
        if (lastEvent == null || lastEvent.isSingle) {
            logger.debug(s"skip first Up or Single: ${me.name}")
            callNextHook
        }
        else
            None
    }
    
    private def skipFirstUpOrLR(me: MouseEvent): Option[LRESULT] = {
        if (lastEvent == null || lastEvent.isLR) {
            logger.debug(s"skip first Up or LR: ${me.name}")
            callNextHook
        }
        else
            None
    }
    */
    
    private def resetLastFlags(me: MouseEvent): Option[LRESULT] = {
        logger.debug(s"reset last flag: ${me.name}")
        ctx.LastFlags.reset(me)
        None
    }
    
    private def checkSameLastEvent(me: MouseEvent): Option[LRESULT] = {
        if (me.sameEvent(lastEvent)) {
            logger.warn(s"same last event: ${me.name}")
            callNextHook
        }
        else {
            lastEvent = me
            None
        }
    }
    
    private def checkExitScrollDown(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isScrollMode) {
            logger.debug(s"exit scroll mode: ${me.name}");
            ctx.exitScrollMode
            ctx.LastFlags.setSuppressed(me)
            suppress
        }
        else
            None
    }
    
    /*
    private def passSingleTrigger(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isSingleTrigger) {
            logger.debug(s"pass single trigger: ${me.name}")
            callNextHook
        }
        else
            None
    }
    */
    
    private def checkExitScrollUp(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isScrollMode) {
            if (ctx.checkExitScroll(me.info.time)) {
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
        if (EventWaiter.offer(me)) {
            logger.debug(s"success to offer: ${me.name}")
            suppress
        }
        else
            None
    }
    
    private def checkDownSuppressed(up: MouseEvent): Option[LRESULT] = {
        val suppressed = ctx.LastFlags.isDownSuppressed(up)
        
        if (suppressed) {
            logger.debug(s"suppress (checkDownSuppressed): ${up.name}")
            suppress
        }
        else
            None
    }
    
    private def checkDownResent(up: MouseEvent): Option[LRESULT] = {
        val resent = ctx.LastFlags.isDownResent(up)
        
        if (resent) {
            logger.debug(s"resendUp and suppress (checkDownResent): ${up.name}")
            Windows.resendUp(up)
            suppress
        }
        else
            None
    }
    
    private def checkTriggerWaitStart(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isLRTrigger || ctx.isTriggerEvent(me)) {
            logger.debug(s"start wait trigger: ${me.name}")
            EventWaiter.start(me)
            suppress
        }
        else
            None
    }
    
    private def checkKeySendMiddle(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isSendMiddleClick && (Windows.getAsyncShiftState || Windows.getAsyncCtrlState || Windows.getAsyncAltState)) {
            logger.debug(s"send middle click")
            Windows.resendClick(MiddleClick(me.info))
            ctx.LastFlags.setSuppressed(me)
            suppress
        }
        else
            None
    }
    
    private def checkTriggerScrollStart(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isTriggerEvent(me)) {
            logger.debug(s"start scroll mode: ${me.name}");
            ctx.startScrollMode(me.info)
            suppress
        }
        else
            None
    }
    
    private def passNotDragTrigger(me: MouseEvent): Option[LRESULT] = {
        if (!ctx.isDragTriggerEvent(me)) {
            logger.debug(s"pass not trigger: ${me.name}")
            callNextHook
        }
        else
            None
    }
    
    private def startScrollDrag(me: MouseEvent): Option[LRESULT] = {
        logger.debug(s"start scroll mode: ${me.name}");
        ctx.startScrollMode(me.info)
        
        drag = dragStart
        dragged = false
            
        suppress
    }
    
    private def continueScrollDrag(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isDraggedLock && dragged) {
            logger.debug(s"continueScrollDrag: ${me.name}")
            suppress
        }
        else
            None
    }
    
    private def exitAndResendDrag(me: MouseEvent): Option[LRESULT] = {
        logger.debug(s"exit scroll mode: ${me.name}")
        ctx.exitScrollMode
        
        if (!dragged) {
            logger.debug(s"resend click: ${me.name}")
            
            me match {
                case LeftUp(info) => Windows.resendClick(LeftClick(info))
                case RightUp(info) => Windows.resendClick(RightClick(info))
                case MiddleUp(info) => Windows.resendClick(MiddleClick(info))
                case X1Up(info) => Windows.resendClick(X1Click(info))
                case X2Up(info) => Windows.resendClick(X2Click(info))
            }
        }
        
        suppress
    }
    
    private def passNotTrigger(me: MouseEvent): Option[LRESULT] = {
        if (!ctx.isTriggerEvent(me)) {
            logger.debug(s"pass not trigger: ${me.name}")
            callNextHook
        }
        else
            None
    }
   
    private def passNotTriggerLR(me: MouseEvent): Option[LRESULT] = {
        if (!ctx.isLRTrigger && !ctx.isTriggerEvent(me)) {
            logger.debug(s"pass not trigger: ${me.name}")
            callNextHook
        }
        else
            None
    }
    
    private def endCallNextHook(me: MouseEvent, msg: String): Option[LRESULT] = {
        logger.debug(msg)
        callNextHook
    }
    
    private def endNotTrigger(me: MouseEvent): Option[LRESULT] = {
        endCallNextHook(me, s"endNotTrigger: ${me.name}")
    }
    
    private def endPass(me: MouseEvent): Option[LRESULT] = {
        endCallNextHook(me, s"endPass: ${me.name}")
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
    
    type Checkers = List[MouseEvent => Option[LRESULT]]
    
    @tailrec
    private def getResult(cs: Checkers, me: MouseEvent): LRESULT = cs match {
        case f :: fs => {
            val res = f(me)
            if (res.isDefined) res.get else getResult(fs, me)
        }
        case _ => throw new IllegalArgumentException()
    }
    
    private def doubleDown(me: MouseEvent): LRESULT = {
        val cs: Checkers = List(
            skipResendEventLR,
            checkSameLastEvent,
            resetLastFlags,
            checkExitScrollDown,
            offerEventWaiter,
            checkTriggerWaitStart,
            endNotTrigger
        )
        
        getResult(cs, me)
    }
    
    private def doubleUp(me: MouseEvent): LRESULT = {
        val cs: Checkers = List(
            skipResendEventLR,
            skipFirstUp,
            checkSameLastEvent,
            checkExitScrollUp,
            checkDownResent,
            offerEventWaiter,
            checkDownSuppressed,
            endNotTrigger
        )
                
        getResult(cs, me)
    }
    
    private def singleDown(me: MouseEvent): LRESULT = {
        val cs: Checkers = List(
            skipResendEventSingle,
            checkSameLastEvent,
            resetLastFlags,
            checkExitScrollDown,
            passNotTrigger,
            checkKeySendMiddle,
            checkTriggerScrollStart,
            endIllegalState
        )
        
        getResult(cs, me)
    }
    
    private def singleUp(me: MouseEvent): LRESULT = {
        val cs: Checkers = List(
            skipResendEventSingle,
            skipFirstUp,
            checkSameLastEvent,
            checkDownSuppressed,
            passNotTrigger,
            checkExitScrollUp,
            endIllegalState
        )
                
        getResult(cs, me)
    }
    
    private def dragDown(me: MouseEvent): LRESULT = {
        val cs: Checkers = List(
            skipResendEventSingle,
            checkSameLastEvent,
            resetLastFlags,
            checkExitScrollDown,
            passNotDragTrigger,
            startScrollDrag
        )
        
        getResult(cs, me)
    }
    
    private def dragUp(me: MouseEvent): LRESULT = {
        val cs: Checkers = List(
            skipResendEventSingle,
            skipFirstUp,
            checkSameLastEvent,
            checkDownSuppressed,
            passNotDragTrigger,
            continueScrollDrag,
            exitAndResendDrag
        )
        
        getResult(cs, me)
    }
    
    private def noneDown(me: MouseEvent): LRESULT = {
        val cs: Checkers = List(
            resetLastFlags,
            checkExitScrollDown,
            endPass
        )
        
        getResult(cs, me)
    }
    
    private def noneUp(me: MouseEvent): LRESULT = {
        val cs: Checkers = List(
            checkDownSuppressed,
            endPass
        )
        
        getResult(cs, me)
    }
    
    private def dispatchDown(d: MouseEvent): LRESULT = {
        if (ctx.isDoubleTrigger) doubleDown(d)
        else if (ctx.isSingleTrigger) singleDown(d)
        else if (ctx.isDragTrigger) dragDown(d)
        else noneDown(d)
    }
    
    private def dispatchUp(u: MouseEvent): LRESULT = {
        if (ctx.isDoubleTrigger) doubleUp(u)
        else if (ctx.isSingleTrigger) singleUp(u)
        else if (ctx.isDragTrigger) dragUp(u)
        else noneUp(u)
    }
    
    private def dispatchDownS(d: MouseEvent): LRESULT = {
        if (ctx.isSingleTrigger) singleDown(d)
        else if (ctx.isDragTrigger) dragDown(d)
        else noneDown(d)
    }
    
    private def dispatchUpS(u: MouseEvent): LRESULT = {
        if (ctx.isSingleTrigger) singleUp(u)
        else if (ctx.isDragTrigger) dragUp(u)
        else noneUp(u)
    }
    
    def leftDown(info: HookInfo): LRESULT = {
        //logger.debug("leftDown")
        val ld = LeftDown(info)
        dispatchDown(ld)
    }
    
    def leftUp(info: HookInfo): LRESULT = {
        //logger.debug("leftUp");
        val lu = LeftUp(info)
        dispatchUp(lu)
    }
    
    def rightDown(info: HookInfo): LRESULT = {
        //logger.debug("rightDown")]
        val rd = RightDown(info)
        dispatchDown(rd)
    }
    
    def rightUp(info: HookInfo): LRESULT = {
        //logger.debug("rightUp")
        val ru = RightUp(info)
        dispatchUp(ru)
    }
    
    def middleDown(info: HookInfo): LRESULT = {
        val md = MiddleDown(info)
        dispatchDownS(md)
    }
    
    def middleUp(info: HookInfo): LRESULT = {
        val mu = MiddleUp(info)
        dispatchUpS(mu)
    }
    
    def xDown(info: HookInfo): LRESULT = {
        val xd = if (Mouse.isXButton1(info.mouseData)) X1Down(info) else X2Down(info)
        dispatchDownS(xd)
    }
    
    def xUp(info: HookInfo): LRESULT = {
        val xu = if (Mouse.isXButton1(info.mouseData)) X1Up(info) else X2Up(info)
        dispatchUpS(xu)
    }
    
    private def dragDefault(info: HookInfo): Unit = {}
    
    private var drag: HookInfo => Unit = dragDefault
    private var dragged = false

    private def dragStart(info: HookInfo): Unit = {
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
        else if (EventWaiter.offer(Move(info))) {
            logger.debug("success to offer: Move")
            suppress.get
        }
        else
            callNextHook.get
    }
    
    /*
    def changeTrigger: Unit = {
        logger.debug("changeTrigger")
        
        if (ctx.isLROnlyTrigger) {
            lrDownFunc = lrOnlyDown
            lrUpFunc = lrOnlyUp
        }
        else {
            lrDownFunc = lrDown
            lrUpFunc = lrUp
        }
    }
    */
}