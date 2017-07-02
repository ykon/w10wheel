package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.annotation.tailrec

import com.sun.jna.platform.win32.WinDef.LRESULT
import win32ex.{ MSLLHOOKSTRUCT => HookInfo }

object EventHandler {
    private val ctx = Context
    private val logger = ctx.logger

    private var lastEvent: MouseEvent = null
    private var lastResendLeftEvent: MouseEvent = null
    private var lastResendRightEvent: MouseEvent = null

    private var resentDownUp = false
    private var secondTriggerUp = false
    private var dragged = false

    private def initState {
        lastEvent = null
        lastResendLeftEvent = null
        lastResendRightEvent = null
        resentDownUp = false
        secondTriggerUp = false
        dragged = false
    }

    private var __callNextHook: () => LRESULT = null

    def setCallNextHook(f: () => LRESULT) =
        __callNextHook = f

    private def callNextHook: Option[LRESULT] = Some(__callNextHook())
    private def suppress: Option[LRESULT] = Some(new LRESULT(1))

    private def getLastResendEvent(me: MouseEvent) = me match {
        case LeftEvent(_) => lastResendLeftEvent
        case RightEvent(_) => lastResendRightEvent
    }

    private def setLastResendEvent(me: MouseEvent) = me match {
        case LeftEvent(_) => lastResendLeftEvent = me
        case RightEvent(_) => lastResendRightEvent = me
    }

    private def isCorrectOrder(pre: MouseEvent, cur: MouseEvent): Boolean = (pre, cur) match {
        case (null, LeftUp(_)) | (LeftUp(_), LeftUp(_)) | (null, RightUp(_)) | (RightUp(_), RightUp(_)) =>
            false
        case _ => true
    }

    private def checkCorrectOrder(me: MouseEvent): Boolean = {
        isCorrectOrder(getLastResendEvent(me), me)
    }

    private def skipResendEventLR(me: MouseEvent): Option[LRESULT] = {
        def pass = {
            logger.debug(s"pass resend event: ${me.name}")
            setLastResendEvent(me)
            callNextHook
        }

        if (!Windows.isInjectedEvent(me)) {
            None
        }
        else if (Windows.isResendClickEvent(me)) {
            logger.debug(s"pass resendClick event: ${me.name}")
            callNextHook
        }
        else if (Windows.isResendEvent(me)) {
            if (resentDownUp) {
                logger.debug(s"ResendEvent: resentDownUp: ${me.name}")
                resentDownUp = false

                if (checkCorrectOrder(me))
                    pass
                else {
                    logger.warn(s"Bad: resendUp retry: ${me.name}")
                    Thread.sleep(1)
                    Windows.resendUp(me)
                    suppress
                }
            }
            else
                pass
        }
        else {
            logger.info(s"pass other software event: ${me.name}")
            callNextHook
        }
    }

    private def skipResendEventSingle(me: MouseEvent): Option[LRESULT] = {
        if (!Windows.isInjectedEvent(me)) {
            None
        }
        else if (Windows.isResendClickEvent(me)) {
            logger.debug(s"pass resendClick event: ${me.name}")
            callNextHook
        }
        else {
            logger.debug(s"pass other software event: ${me.name}")
            callNextHook
        }
    }

    private def checkEscape(me: MouseEvent): Option[LRESULT] = {
        if (Windows.getEscState) {
            logger.debug(s"Esc: init state and exit scroll: ${me.name}")
            initState
            ctx.LastFlags.init
            ctx.exitScrollMode
            EventWaiter.offer(Cancel(null))
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

    private def resetLastFlagsLR(me: MouseEvent): Option[LRESULT] = {
        logger.debug(s"reset last flag: ${me.name}")
        ctx.LastFlags.resetLR(me)
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
        if (ctx.isReleasedScrollMode) {
            logger.debug(s"exit scroll mode (Released): ${me.name}")
            ctx.exitScrollMode
            ctx.LastFlags.setSuppressed(me)
            suppress
        }
        else
            None
    }

    private def passPressedScrollMode(down: MouseEvent): Option[LRESULT] = {
        if (ctx.isPressedScrollMode) {
            logger.debug(s"pass scroll mode (Pressed): ${down.name}")
            ctx.LastFlags.setPassed(down)
            callNextHook
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

    private def checkExitScrollUp(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isPressedScrollMode) {
            if (ctx.checkExitScroll(me.info.time)) {
                logger.debug(s"exit scroll mode (Pressed): ${me.name}")
                ctx.exitScrollMode
            }
            else {
                logger.debug(s"continue scroll mode (Released): ${me.name}")
                ctx.setReleasedScrollMode
            }

            suppress
        }
        else
            None
    }

    private def checkExitScrollUpLR(up: MouseEvent): Option[LRESULT] = {
        if (ctx.isPressedScrollMode) {
            if (!secondTriggerUp) {
                logger.debug(s"ignore first up: ${up.name}")
            }
            else if (ctx.checkExitScroll(up.info.time)) {
                logger.debug(s"exit scroll mode (Pressed): ${up.name}")
                ctx.exitScrollMode
            }
            else {
                logger.debug(s"continue scroll mode (Released): ${up.name}")
                ctx.setReleasedScrollMode
            }

            secondTriggerUp = !secondTriggerUp
            suppress
        }
        else
            None
    }

    private def checkStartingScroll(up: MouseEvent): Option[LRESULT] = {
        if (ctx.isStartingScrollMode) {
            logger.debug("check starting scroll")

            if (!secondTriggerUp) {
                logger.debug(s"ignore first up (starting): ${up.name}")
                Thread.sleep(1)
            }
            else {
                logger.debug(s"exit scroll mode (starting): ${up.name}")
                Thread.sleep(1)
                ctx.exitScrollMode
            }

            secondTriggerUp = !secondTriggerUp
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

    private def checkSuppressedDown(up: MouseEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_SuppressedDown(up)) {
            logger.debug(s"suppress (checkSuppressedDown): ${up.name}")
            suppress
        }
        else
            None
    }

    private def checkResentDown(up: MouseEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_ResentDown(up)) {
            logger.debug(s"resendUp and suppress (checkResentDown): ${up.name}")
            resentDownUp = true
            Windows.resendUp(up)
            suppress
        }
        else
            None
    }

    private def checkPassedDown(up: MouseEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_PassedDown(up)) {
            logger.debug(s"pass (checkPassedDown): ${up.name}")
            callNextHook
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
        if (ctx.isSendMiddleClick && (w.getShiftState || w.getCtrlState || w.getAltState)) {
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
        logger.debug(s"start scroll mode (Drag): ${me.name}");
        ctx.startScrollMode(me.info)

        drag = dragStart
        dragged = false

        suppress
    }

    private def continueScrollDrag(up: MouseEvent): Option[LRESULT] = {
        if (ctx.isDraggedLock && dragged) {
            logger.debug(s"continueScrollDrag (Released): ${up.name}")
            ctx.setReleasedScrollMode
            suppress
        }
        else
            None
    }

    private def exitAndResendDrag(me: MouseEvent): Option[LRESULT] = {
        logger.debug(s"exit scroll mode (Drag): ${me.name}")
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
            resetLastFlagsLR,
            checkExitScrollDown,
            passPressedScrollMode,
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
            checkEscape,
            skipFirstUp,
            checkSameLastEvent,
            //checkSingleSuppressed,
            checkPassedDown,
            checkResentDown,
            checkExitScrollUpLR,
            checkStartingScroll,
            offerEventWaiter,
            checkSuppressedDown,
            endNotTrigger
        )

        getResult(cs, me)
    }

    private def singleDown(me: MouseEvent): LRESULT = {
        //logger.debug("singleDown")
        val cs: Checkers = List(
            skipResendEventSingle,
            checkSameLastEvent,
            //resetLastFlags,
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
            checkEscape,
            skipFirstUp,
            checkSameLastEvent,
            checkSuppressedDown,
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
            //resetLastFlags,
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
            checkEscape,
            skipFirstUp,
            checkSameLastEvent,
            checkSuppressedDown,
            passNotDragTrigger,
            continueScrollDrag,
            exitAndResendDrag
        )

        getResult(cs, me)
    }

    private def noneDown(me: MouseEvent): LRESULT = {
        //logger.debug("noneDown")
        val cs: Checkers = List(
            //resetLastFlags,
            checkExitScrollDown,
            endPass
        )

        getResult(cs, me)
    }

    private def noneUp(me: MouseEvent): LRESULT = {
        //logger.debug("noneUp")
        val cs: Checkers = List(
            checkEscape,
            checkSuppressedDown,
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

    private def dragStart(info: HookInfo): Unit = {
        if (ctx.isCursorChange && !ctx.isVhAdjusterMode)
            Windows.changeCursorV

        drag = dragDefault
        dragged = true
    }

    def move(info: HookInfo): LRESULT = {
        if (ctx.isScrollMode) {
            drag(info)
            //Windows.sendWheel(info.pt)
            //Windows.sendWheel(info.pt.x, info.pt.y)
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