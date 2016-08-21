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
    //private var lastResendEvent: MouseEvent = null
    
    private var preLeftResendEvent: MouseEvent = null
    private var preRightResendEvent: MouseEvent = null
    
    private var __callNextHook: () => LRESULT = null
    
    def setCallNextHook(f: () => LRESULT) =
        __callNextHook = f
    
    private def callNextHook: Option[LRESULT] = Some(__callNextHook())
    private def suppress: Option[LRESULT] = Some(new LRESULT(1))
    
    private def getPreResendEvent(me: MouseEvent) = me match {
        case LeftDown(_) | LeftUp(_) => preLeftResendEvent
        case RightDown(_) | RightUp(_) => preRightResendEvent
    }
    
    private def setPreResendEvent(me: MouseEvent) = me match {
        case LeftDown(_) | LeftUp(_) => preLeftResendEvent = me
        case RightDown(_) | RightUp(_) => preRightResendEvent = me
    }
        
    private def skipResendEventLR(me: MouseEvent): Option[LRESULT] = {
        if (Windows.isResendEvent(me)) {
            (getPreResendEvent(me), me) match {
                case (null, LeftUp(_)) | (LeftUp(_), LeftUp(_)) | (null, RightUp(_)) | (RightUp(_), RightUp(_)) => {
                    logger.warn(s"re-resend event: ${me.name}")
                    Windows.resendUp(me)
                    suppress
                }
                case _ => {
                    logger.debug(s"skip resend event: ${me.name}")
                    setPreResendEvent(me)
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
    
    private def passSingleEvent(me: MouseEvent): Option[LRESULT] = {
        if (me.isSingle) {
            logger.debug(s"pass single event: ${me.name}")
            callNextHook
        }
        else
            None
    }
    
    private def checkSingleSuppressed(up: MouseEvent): Option[LRESULT] = {
        if (up.isSingle) {
            if (ctx.LastFlags.isDownSuppressed(up)) {
                logger.debug(s"suppress (checkSingleSuppressed): ${up.name}")
                suppress
            }
            else {
                logger.debug(s"pass (checkSingleSuppressed): ${up.name}")
                callNextHook
            }
        }
        else
            None
    }
    
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
        val w = Windows
        if (ctx.isSendMiddleClick && (w.checkShiftState || w.checkCtrlState || w.checkAltState)) {
            logger.debug(s"send middle click")
            w.resendClick(MiddleClick(me.info))
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
    
    private def lrDown(me: MouseEvent): LRESULT = {
        //logger.debug("lrDown")
        val cs: Checkers = List(
            skipResendEventLR,
            checkSameLastEvent,
            resetLastFlags,
            checkExitScrollDown,
            //passSingleEvent,
            offerEventWaiter,
            checkTriggerWaitStart,
            endNotTrigger
        )
        
        getResult(cs, me)
    }
    
    private def lrUp(me: MouseEvent): LRESULT = {
        //logger.debug("lrUp")
        val cs: Checkers = List(
            skipResendEventLR,
            skipFirstUp,
            checkSameLastEvent,
            //checkSingleSuppressed,
            checkExitScrollUp,
            checkDownResent,
            offerEventWaiter,
            checkDownSuppressed,
            endNotTrigger
        )
                
        getResult(cs, me)
    }
    
    private def singleDown(me: MouseEvent): LRESULT = {
        //logger.debug("singleDown")
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
        //logger.debug("singleUp")
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
        //logger.debug("dragDown")
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
        //logger.debug("dragUp")
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
        //logger.debug("noneDown")
        val cs: Checkers = List(
            resetLastFlags,
            checkExitScrollDown,
            endPass
        )
        
        getResult(cs, me)
    }
    
    private def noneUp(me: MouseEvent): LRESULT = {
        //logger.debug("noneUp")
        val cs: Checkers = List(
            checkDownSuppressed,
            endPass
        )
        
        getResult(cs, me)
    }
    
    @volatile private var procDownLR: MouseEvent => LRESULT = lrDown
    @volatile private var procUpLR: MouseEvent => LRESULT = lrUp
    @volatile private var procDownS: MouseEvent => LRESULT = noneDown
    @volatile private var procUpS: MouseEvent => LRESULT = noneUp
    
    def leftDown(info: HookInfo): LRESULT = {
        //logger.debug("leftDown")
        val ld = LeftDown(info)
        procDownLR(ld)
    }
    
    def leftUp(info: HookInfo): LRESULT = {
        //logger.debug("leftUp");
        val lu = LeftUp(info)
        procUpLR(lu)
    }
    
    def rightDown(info: HookInfo): LRESULT = {
        //logger.debug("rightDown")]
        val rd = RightDown(info)
        procDownLR(rd)
    }
    
    def rightUp(info: HookInfo): LRESULT = {
        //logger.debug("rightUp")
        val ru = RightUp(info)
        procUpLR(ru)
    }
    
    def middleDown(info: HookInfo): LRESULT = {
        val md = MiddleDown(info)
        procDownS(md)
    }
    
    def middleUp(info: HookInfo): LRESULT = {
        val mu = MiddleUp(info)
        procUpS(mu)
    }
    
    def xDown(info: HookInfo): LRESULT = {
        val xd = if (Mouse.isXButton1(info.mouseData)) X1Down(info) else X2Down(info)
        procDownS(xd)
    }
    
    def xUp(info: HookInfo): LRESULT = {
        val xu = if (Mouse.isXButton1(info.mouseData)) X1Up(info) else X2Up(info)
        procUpS(xu)
    }
    
    private def dragDefault(info: HookInfo): Unit = {}
    
    private var drag: HookInfo => Unit = dragDefault
    private var dragged = false

    private def dragStart(info: HookInfo): Unit = {
        if (ctx.isCursorChange && !ctx.isVhAdjusterMode)
            Windows.changeCursorV
        
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
    
    def changeTrigger {
        logger.debug("changeTrigger: EventHandler")
        
        val (downLR, upLR, downS, upS) = if (ctx.isDoubleTrigger) {
            logger.debug("set double down/up")
            (lrDown _, lrUp _, noneDown _, noneUp _)
        }
        else if (ctx.isSingleTrigger) {
            logger.debug("set single down/up")
            (noneDown _, noneUp _, singleDown _, singleUp _)
        }
        else if (ctx.isDragTrigger) {
            logger.debug("set drag down/up")
            (dragDown _, dragUp _, dragDown _, dragUp _)
        }
        else {
            logger.debug("set none down/up")
            (noneDown _, noneUp _, noneDown _, noneUp _)
        }
        
        procDownLR = downLR; procUpLR = upLR
        procDownS = downS; procUpS = upS
    }
}