package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.concurrent._
import scala.collection.Iterator
import ExecutionContext.Implicits.global

import com.sun.jna.platform.win32.WinDef.LRESULT
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

object EventHandler {
    private val ctx = Context
    private val logger = ctx.logger
    
    private var lastEvent: MouseEvent = null
    
    private var __callNextHook: () => LRESULT = null
    
    def setCallNextHook(f: () => LRESULT) =
        __callNextHook = f
    
    private def callNextHook: Option[LRESULT] = Some(__callNextHook())
    private def suppress: Option[LRESULT] = Some(new LRESULT(1))
        
    private def skipResendEvent(me: MouseEvent): Option[LRESULT] = {
        //if (ctx.checkSkip(me)) {
        if (Windows.isResendEvent(me)) {
            logger.debug(s"skip resend event: ${me.name}")
            callNextHook
        }
        else
            None
    }
    
    private def skipFirstUpOrSingle(me: MouseEvent): Option[LRESULT] = {
        if (lastEvent == null || lastEvent.isSingle) {
            logger.debug(s"skip first Up or Single: ${me.name}")
            callNextHook
        }
        else
            None
    }
    
    private def skipFirstUpOrLR(me: MouseEvent): Option[LRESULT] = {
        if (lastEvent == null || !lastEvent.isSingle) {
            logger.debug(s"skip first Up or LR: ${me.name}")
            callNextHook
        }
        else
            None
    }
    
    private def resetLastFlags(me: MouseEvent): Option[LRESULT] = {
        logger.debug(s"reset last flag: ${me.name}")
        ctx.LastFlags.reset(me)
        None
    }
    
    private def checkSameLastEvent(me: MouseEvent): Option[LRESULT] = {
        if (me.same(lastEvent)) {
            logger.warn(s"same last event: ${me.name}")
            callNextHook
            
            //logger.warn(me.toString())
            //logger.warn(lastEvent.toString())
            //suppress
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
        if (EventWaiter.isWaiting && EventWaiter.offer(me)) {
            logger.debug(s"success to offer: ${me.name}")
            suppress
        }
        else
            None
    }
    
    private def checkDownSuppressed(up: MouseEvent): Option[LRESULT] = {
        val suppressed = ctx.LastFlags.isDownSuppressed(up)
        
        if (suppressed) {
            logger.debug(s"after suppressed down event: ${up.name}")
            suppress
        }
        else
            None
    }
    
    private def checkDownResent(up: MouseEvent): Option[LRESULT] = {
        val resent = ctx.LastFlags.isDownResent(up)
        
        if (resent) {
            // waiter thread timing issue
            logger.debug(s"forced to resend: ${up.name}")
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
        if (Windows.getAsyncShiftState || Windows.getAsyncCtrlState || Windows.getAsyncAltState) {
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
    
    //type Checkers = Stream[MouseEvent => Option[LRESULT]]
    type LCheckers = List[MouseEvent => Option[LRESULT]]
    
    /*
    private def getResult(cs: Checkers, me: MouseEvent): LRESULT =
        cs.flatMap(_.apply(me)).head
    */
    
    import scala.annotation.tailrec
    
    @tailrec
    private def getResultL(lcs: LCheckers, me: MouseEvent): Option[LRESULT] = lcs match {
        case f :: fs => {
            val res = f(me)
            if (res.isDefined) res else getResultL(fs, me)
        }
        case _ => throw new IllegalArgumentException()
    }
        
    /*
    private def getResultBranch(cs: Checkers, me: MouseEvent): Option[LRESULT] =
        cs.map(_.apply(me)).find(_.isDefined).get
    */
    
    private def branchDragDown(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isDragTrigger) {
            logger.debug(s"branch drag down: ${me.name}")
            //val cs: Checkers = Stream(
            val lcs: LCheckers = List(
                    passNotDragTrigger, startScrollDrag)
                    
            //getResultBranch(cs, me)
            getResultL(lcs, me)
        }
        else
            None
    }
    
    private def branchDragUp(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isDragTrigger) {
            logger.debug(s"branch drag up: ${me.name}")
            //val cs: Checkers = Stream(
            val lcs: LCheckers = List(
                    passNotDragTrigger, exitAndResendDrag)
                        
            //getResultBranch(cs, me)
            getResultL(lcs, me)
        }
        else
            None
    }

    private def lrDown(me: MouseEvent): LRESULT = {
        //val start = System.nanoTime()
        //val cs: Checkers = Stream(
        val lcs: LCheckers = List( 
                skipResendEvent,
                checkSameLastEvent,
                branchDragDown,
                resetLastFlags,
                checkExitScrollDown,
                passSingleTrigger,
                offerEventWaiter,
                checkTriggerWaitStart,
                endNotTrigger)
        
        //val res = getResult(cs, me)
        val res = getResultL(lcs, me).get
        //logger.debug(s"lrDown: ${(System.nanoTime() - start) / 1000} us")
        res
    }
    
    private def lrUp(me: MouseEvent): LRESULT = {
        //val start = System.nanoTime
        //val cs: Checkers = Stream(
        val lcs: LCheckers = List(
                skipResendEvent,
                skipFirstUpOrSingle,
                checkSameLastEvent,
                branchDragUp,
                passSingleTrigger,
                checkExitScrollUp,
                passNotTriggerLR,
                offerEventWaiter,
                checkDownResent,
                checkDownSuppressed,
                endUnknownEvent)
                
        //val res = getResult(cs, me)
        val res = getResultL(lcs, me).get
        //logger.debug(s"lrUp: ${(System.nanoTime() - start) / 1000} us")
        res
    }
    
    /*
    private def lrOnlyDown(me: MouseEvent): LRESULT = {
        val cs: Checkers = Stream(
                skipResendEvent,
                checkSameLastEvent,
                passNotTriggerLROnly,
                startScrollLROnly)
        
        getResult(me, cs)
    }
    
    private def lrOnlyUp(me: MouseEvent): LRESULT = {
        val cs: Checkers = Stream(
                skipResendEvent,
                skipFirstUpOrSingle,
                checkSameLastEvent,
                passNotTriggerLROnly,
                exitAndResendLROnly)
                
        getResult(me, cs)
    }
    */
    
    //@volatile private var lrDownFunc: (MouseEvent => LRESULT) = null
    //@volatile private var lrUpFunc: (MouseEvent => LRESULT) = null
    
    def leftDown(info: HookInfo): LRESULT = {
        //logger.debug("leftDown")
        lrDown(LeftDown(info))
    }
    
    def rightDown(info: HookInfo): LRESULT = {
        //logger.debug("rightDown")
        lrDown(RightDown(info))
    }
    
    def leftUp(info: HookInfo): LRESULT = {
        //logger.debug("leftUp");
        lrUp(LeftUp(info))
    }
    
    def rightUp(info: HookInfo): LRESULT = {
        //logger.debug("rightUp")
        lrUp(RightUp(info))
    }
    
    private def singleDown(me: MouseEvent): LRESULT = {
        //val cs: Checkers = Stream(
        val lcs: LCheckers = List(
                skipResendEvent,
                checkSameLastEvent,
                branchDragDown,
                resetLastFlags,
                checkExitScrollDown,
                passNotTrigger,
                checkKeySendMiddle,
                checkTriggerScrollStart,
                endIllegalState)
        
        //getResult(cs, me)
        getResultL(lcs, me).get
    }
    
    private def singleUp(me: MouseEvent): LRESULT = {
        //val cs: Checkers = Stream(
        val lcs: LCheckers = List(
                skipResendEvent,
                skipFirstUpOrLR,
                checkSameLastEvent,
                branchDragUp,
                passNotTrigger,
                checkExitScrollUp,
                checkDownSuppressed,
                endIllegalState)
                
        //getResult(cs, me)
        getResultL(lcs, me).get
    }
    
    def middleDown(info: HookInfo): LRESULT = {            
        singleDown(MiddleDown(info))
    }
    
    def xDown(info: HookInfo): LRESULT = {
        val evt = if (Mouse.isXButton1(info.mouseData)) X1Down(info) else X2Down(info)
        singleDown(evt)
    }
    
    def middleUp(info: HookInfo): LRESULT = {
        singleUp(MiddleUp(info))
    }
    
    def xUp(info: HookInfo): LRESULT = {
        val evt = if (Mouse.isXButton1(info.mouseData)) X1Up(info) else X2Up(info)
        singleUp(evt)
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