package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.annotation.tailrec

import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinUser.{ MSLLHOOKSTRUCT => HookInfo }

object EventHandler {
    private val ctx = Context
    private val logger = Logger.getLogger()

    private var lastEvent: MouseEvent = NonEvent(null)

    private var secondTriggerUp = false
    private var dragged = false

    // The down of the single or drag trigger that started scroll mode (and was suppressed). Reset
    // by its up. An up of another button (e.g. the trigger was changed by Reload while pressed), or
    // without it, has its down passed W10Wheel (e.g. pressed in Pass Mode), so the up passes.
    private var triggerStarted: MouseEvent = NonEvent(null)

    // The downs that started scroll mode of a trigger before it was changed (changeTrigger), by
    // button (buttonIndex): changed again while pressed, there can be several. Their up is
    // suppressed before the handlers of the current trigger, which would handle it as their own
    // (e.g. the LR trigger's wait would resend it). Reset by the up or the next down of the button.
    private val canceledDowns = Array.fill(5)(false)

    private def buttonIndex(me: MouseEvent): Int = me match {
        case LeftEvent(_) => 0
        case RightEvent(_) => 1
        case MiddleEvent(_) => 2
        case X1Event(_) => 3
        case X2Event(_) => 4
        case _ => throw new IllegalArgumentException(s"Not a button event: ${me.name}")
    }

    def initState: Unit = {
        lastEvent = NonEvent(null)
        secondTriggerUp = false
        dragged = false
        triggerStarted = NonEvent(null)
        java.util.Arrays.fill(canceledDowns, false)
    }

    private var __callNextHook: () => LRESULT = null

    def setCallNextHook(f: () => LRESULT) =
        __callNextHook = f

    def getCallNextHook: () => LRESULT = __callNextHook

    private def callNextHook: Option[LRESULT] = Some(__callNextHook())
    private def suppress: Option[LRESULT] = Some(new LRESULT(1))

    private def debug(msg: String, me: MouseEvent): Unit = {
        logger.debug(msg + ": " + me.name)
    }

    // The resent downs and ups are sent in order by InputSender (an up only after its down was
    // sent), so they are passed as they come
    private def skipResendEventLR(me: MouseEvent): Option[LRESULT] = {
        if (!Windows.isInjectedEvent(me)) {
            None
        }
        else if (Windows.isResendClickEvent(me)) {
            debug("pass resendClick event", me)
            callNextHook
        }
        else if (Windows.isResendEvent(me)) {
            debug("pass resend event", me)
            callNextHook
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
            debug("pass resendClick event", me)
            callNextHook
        }
        else {
            debug("pass other software event", me)
            callNextHook
        }
    }

    private def checkEscape(me: MouseEvent): Option[LRESULT] = {
        if (Windows.getEscState) {
            debug("Esc: init state and exit scroll", me)
            ctx.initState
            callNextHook
        }
        else
            None
    }

    private def skipFirstUp(me: MouseEvent): Option[LRESULT] = {
        if (lastEvent == NonEvent(null)) {
            debug("skip first Up", me)
            callNextHook
        }
        else
            None
    }

    private def resetLastFlagsLR(me: MouseEvent): Option[LRESULT] = {
        debug("reset last flag", me)
        ctx.LastFlags.resetLRFor(me)
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
            debug("exit scroll mode (Released)", me)
            ctx.exitScrollMode
            ctx.LastFlags.setSuppressed(me)
            suppress
        }
        else
            None
    }

    private def passPressedScrollMode(down: MouseEvent): Option[LRESULT] = {
        if (ctx.isPressedScrollMode) {
            debug("pass scroll mode (Pressed)", down)
            ctx.LastFlags.setPassed(down)
            callNextHook
        }
        else
            None
    }

    private def passSingleEvent(me: MouseEvent): Option[LRESULT] = {
        if (me.isSingle) {
            debug("pass single event", me)
            callNextHook
        }
        else
            None
    }

    private def checkExitScrollUp(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isPressedMouseScrollMode) {
            if (ctx.checkExitScroll(me.info.time)) {
                debug("exit scroll mode (Pressed)", me)
                ctx.exitScrollMode
            }
            else {
                debug("continue scroll mode (Released)", me)
                ctx.setReleasedScrollMode
            }

            suppress
        }
        else
            None
    }

    private def checkExitScrollUpLR(up: MouseEvent): Option[LRESULT] = {
        if (ctx.isPressedMouseScrollMode) {
            if (!secondTriggerUp) {
                debug("ignore first up", up)
            }
            else if (ctx.checkExitScroll(up.info.time)) {
                debug("exit scroll mode (Pressed)", up)
                ctx.exitScrollMode
            }
            else {
                debug("continue scroll mode (Released)", up)
                ctx.setReleasedScrollMode
            }

            // Its down was suppressed (setFlagsOffer): handled
            ctx.LastFlags.getAndReset_SuppressedDown(up)
            secondTriggerUp = !secondTriggerUp
            suppress
        }
        else
            None
    }

    private def checkStartingScroll(up: MouseEvent): Option[LRESULT] = {
        if (ctx.isStartingScrollMode) {
            logger.debug("check starting scroll")
            Thread.sleep(1)

            if (!secondTriggerUp) {
                debug("ignore first up (starting)", up)
            }
            else {
                debug("exit scroll mode (starting)", up)
                ctx.exitScrollMode
            }

            ctx.LastFlags.getAndReset_SuppressedDown(up)
            secondTriggerUp = !secondTriggerUp
            suppress
        }
        else
            None
    }

    private def offerEventWaiter(me: MouseEvent): Option[LRESULT] = {
        // The up of the other button than the waiting down, whose own down did not reach the
        // application (e.g. suppressed by a wait canceled when Pass Mode was turned on): resent
        // with the waiting down, it would be an up without a down. Suppress it, the wait goes on.
        if (me.isUp && EventWaiter.isWaitingOtherThan(me) && Windows.dropLoneUpOf(me)) {
            debug("suppress (up without its down, other button)", me)
            suppress
        }
        else if (EventWaiter.offer(me)) {
            debug("success to offer", me)
            // The second down starts LR scroll mode: no up of the pair counted yet (the count of
            // an earlier one may remain, e.g. when the keyboard trigger took over its scroll)
            if (me.isDown) secondTriggerUp = false
            suppress
        }
        else
            None
    }

    private def checkSuppressedDown(up: MouseEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_SuppressedDown(up)) {
            debug(s"suppress (checkSuppressedDown)", up)
            suppress
        }
        else
            None
    }

    private def checkResentDown(up: MouseEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_ResentDown(up)) {
            debug("resendUp and suppress (checkResentDown)", up)
            Windows.resendUp(up)
            suppress
        }
        else
            None
    }

    private def checkPassedDown(up: MouseEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_PassedDown(up)) {
            debug("pass (checkPassedDown)", up)
            callNextHook
        }
        else
            None
    }

    private def checkTriggerWaitStart(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isLRTrigger || ctx.isTriggerEvent(me)) {
            debug("start wait trigger", me)
            EventWaiter.start(me)
            suppress
        }
        else
            None
    }

    private def checkKeySendMiddle(me: MouseEvent): Option[LRESULT] = {
        val w = Windows
        if (ctx.isSendMiddleClick && (w.getShiftState || w.getCtrlState || w.getAltState)) {
            logger.debug("send middle click")
            w.resendClick(MiddleClick(me.info))
            ctx.LastFlags.setSuppressed(me)
            suppress
        }
        else
            None
    }

    private def checkTriggerScrollStart(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isTriggerEvent(me)) {
            debug("start scroll mode", me);
            ctx.startScrollMode(me.info)
            triggerStarted = me
            suppress
        }
        else
            None
    }

    private def passNotDragTrigger(me: MouseEvent): Option[LRESULT] = {
        if (!ctx.isDragTriggerEvent(me)) {
            debug("pass not trigger", me)
            callNextHook
        }
        else
            None
    }

    private def startScrollDrag(me: MouseEvent): Option[LRESULT] = {
        debug("start scroll mode (Drag)", me);
        ctx.startScrollMode(me.info)
        triggerStarted = me

        drag = dragStart
        dragged = false

        suppress
    }

    private def passNotStartedTrigger(up: MouseEvent): Option[LRESULT] = {
        if (triggerStarted.sameButton(up)) {
            triggerStarted = NonEvent(null)
            None
        }
        else {
            debug("pass (the down passed)", up)
            callNextHook
        }
    }

    // The drag is not the pressed scroll mode any more: the keyboard trigger has started scroll
    // mode since (maybe ended already). Its up neither continues nor ends it (nor resends a
    // click), and its down started the drag (suppressed), so the up is suppressed.
    private def suppressNotDragScroll(up: MouseEvent): Option[LRESULT] = {
        if (!ctx.isPressedMouseScrollMode) {
            debug("suppress (not drag scroll mode)", up)
            suppress
        }
        else
            None
    }

    private def continueScrollDrag(up: MouseEvent): Option[LRESULT] = {
        if (ctx.isDraggedLock && dragged) {
            debug("continueScrollDrag (Released)", up)
            ctx.setReleasedScrollMode
            suppress
        }
        else
            None
    }

    private def exitAndResendDrag(me: MouseEvent): Option[LRESULT] = {
        debug("exit scroll mode (Drag)", me)
        ctx.exitScrollMode

        if (!dragged) {
            debug("resend click", me)

            me match {
                case LeftUp(info) => Windows.resendClick(LeftClick(info))
                case RightUp(info) => Windows.resendClick(RightClick(info))
                case MiddleUp(info) => Windows.resendClick(MiddleClick(info))
                case X1Up(info) => Windows.resendClick(X1Click(info))
                case X2Up(info) => Windows.resendClick(X2Click(info))
                case _ => throw new IllegalStateException()
            }
        }

        suppress
    }

    private def passNotTrigger(me: MouseEvent): Option[LRESULT] = {
        if (!ctx.isTriggerEvent(me)) {
            debug("pass not trigger", me)
            callNextHook
        }
        else
            None
    }

    private def passNotTriggerLR(me: MouseEvent): Option[LRESULT] = {
        if (!ctx.isLRTrigger && !ctx.isTriggerEvent(me)) {
            debug("pass not trigger", me)
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
        msg.foreach(m => logger.debug(m))
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
            f(me) match {
                case Some(res) => res
                case None => getResult(fs, me)
            }
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
            // The wait timed out during the checks above: its down has been resent
            checkResentDown,
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
            passNotStartedTrigger,
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
            passNotStartedTrigger,
            suppressNotDragScroll,
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

    // Before the handlers of the trigger (see canceledDowns)
    private def procDown(proc: MouseEvent => LRESULT, d: MouseEvent): LRESULT = {
        if (!Windows.isInjectedEvent(d)) {
            canceledDowns(buttonIndex(d)) = false

            // A new press: the flags of the earlier one (resent, suppressed, passed) are reset
            // whatever the trigger (only the LR trigger's handlers did, so after changing the
            // trigger they could take e.g. the next up for that of a resent or suppressed down)
            ctx.LastFlags.resetLRFor(d)
        }

        proc(d)
    }

    private def procUpBody(proc: MouseEvent => LRESULT, u: MouseEvent): LRESULT = {
        val i = buttonIndex(u)
        if (!Windows.isInjectedEvent(u) && canceledDowns(i)) {
            canceledDowns(i) = false

            // As the handlers do for an up: Esc initializes the state (and passes the up), and the
            // up is the last event (or the next down of the button would be taken as the same)
            checkEscape(u) match {
                case Some(res) => res
                case None =>
                    debug("suppress (the down was canceled by changing the trigger)", u)
                    lastEvent = u
                    suppress.get
            }
        }
        else
            proc(u)
    }

    private def procUp(proc: MouseEvent => LRESULT, u: MouseEvent): LRESULT = {
        val res = procUpBody(proc, u)

        // The press is over: its flags (resent, suppressed, passed) are not needed any more, reset
        // them whatever handled the up (e.g. after changing the trigger, the handlers of the new
        // one do not use them, and they would be taken for those of a later up whose down was not
        // seen)
        if (!Windows.isInjectedEvent(u))
            ctx.LastFlags.resetLRFor(u)

        res
    }

    def leftDown(info: HookInfo): LRESULT = {
        //logger.debug("leftDown")
        procDown(procDownLR, LeftDown(info))
    }

    def leftUp(info: HookInfo): LRESULT = {
        //logger.debug("leftUp");
        procUp(procUpLR, LeftUp(info))
    }

    def rightDown(info: HookInfo): LRESULT = {
        //logger.debug("rightDown")]
        procDown(procDownLR, RightDown(info))
    }

    def rightUp(info: HookInfo): LRESULT = {
        //logger.debug("rightUp")
        procUp(procUpLR, RightUp(info))
    }

    def middleDown(info: HookInfo): LRESULT = {
        procDown(procDownS, MiddleDown(info))
    }

    def middleUp(info: HookInfo): LRESULT = {
        procUp(procUpS, MiddleUp(info))
    }

    def xDown(info: HookInfo): LRESULT = {
        val xd = if (Mouse.isXButton1(info.mouseData)) X1Down(info) else X2Down(info)
        procDown(procDownS, xd)
    }

    def xUp(info: HookInfo): LRESULT = {
        val xu = if (Mouse.isXButton1(info.mouseData)) X1Up(info) else X2Up(info)
        procUp(procUpS, xu)
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

    // changed: the trigger has changed (not on every properties load)
    def changeTrigger(changed: Boolean): Unit = {
        logger.debug("changeTrigger: EventHandler")

        val handlers: (MouseEvent => LRESULT, MouseEvent => LRESULT, MouseEvent => LRESULT, MouseEvent => LRESULT) = if (ctx.isDoubleTrigger) {
            logger.debug("set double down/up")
            (lrDown, lrUp, noneDown, noneUp)
        }
        else if (ctx.isSingleTrigger) {
            logger.debug("set single down/up")
            (noneDown, noneUp, singleDown, singleUp)
        }
        else if (ctx.isDragTrigger) {
            logger.debug("set drag down/up")
            (dragDown, dragUp, dragDown, dragUp)
        }
        else {
            logger.debug("set none down/up")
            (noneDown, noneUp, noneDown, noneUp)
        }
        val (downLR, upLR, downS, upS) = handlers

        procDownLR = downLR; procUpLR = upLR
        procDownS = downS; procUpS = upS

        // The handlers of the old trigger are not used any more, so its scroll mode would not end
        // (e.g. the up of the drag trigger is not handled as such). End it (not the keyboard
        // trigger's), and suppress the up of its down before the new handlers (see canceledDowns).
        if (changed) {
            // A pending LR trigger wait would still resend its down: cancel it, the down was
            // suppressed and so is its up
            EventWaiter.cancel() match {
                case Some(down) if down.isDown => canceledDowns(buttonIndex(down)) = true
                case _ => ()
            }

            if ((ctx.isScrollMode || ctx.isStartingScrollMode) && !ctx.isKeyboardScrollMode) {
                logger.debug("changeTrigger: exit scroll mode")
                ctx.exitScrollMode
            }

            if (triggerStarted.isDown)
                canceledDowns(buttonIndex(triggerStarted)) = true

            // The downs suppressed by the old trigger (e.g. the LR trigger's pair) too: the new
            // handlers could take their up first (e.g. a new LR trigger wait)
            ctx.LastFlags.takeSuppressedDowns.zipWithIndex.foreach { case (b, i) =>
                if (b) canceledDowns(i) = true
            }

            triggerStarted = NonEvent(null)
            drag = dragDefault
            dragged = false
            secondTriggerUp = false

            // Some handlers do not record the last event (none trigger): the event recorded by the
            // old ones could stay a down after its up, so that the next down was taken as the same
            lastEvent = NonEvent(null)
        }
    }
}