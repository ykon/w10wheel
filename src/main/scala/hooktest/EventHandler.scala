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
    // by its up, or by the next down of its button (the up was missed, see checkSameLastEvent). An
    // up of another button (e.g. the trigger was changed by Reload while pressed), or without it,
    // has its down passed W10Wheel (e.g. pressed in Pass Mode), so the up passes.
    private var triggerStarted: MouseEvent = NonEvent(null)

    // The earlier down of the button of the down handled was suppressed, and no up of the button
    // has come since (the flag before procDown resets it for this down): the up was missed, the
    // buttons do not repeat (see checkSameLastEvent)
    private var earlierDownSuppressed = false

    // The downs that started scroll mode of a trigger before it was changed (changeTrigger), by
    // button (buttonIndex): changed again while pressed, there can be several. Their up is
    // suppressed before the handlers of the current trigger, which would handle it as their own
    // (e.g. the LR trigger's wait would resend it). Reset by the up or the next down of the button.
    private val canceledDowns = Array.fill(5)(false)

    // The downs of the left and right buttons resent alone (LR trigger) whose up had not come when
    // the trigger was changed (changeTrigger), by button: their up is resent (and the physical one
    // suppressed) before the handlers of the current trigger, which do not resend it (e.g. the
    // middle trigger's would pass it, and the hook drop the down resent after it: a lost click).
    // Reset by the up or the next down of the button.
    private val resentDowns = Array.fill(2)(false)

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
        java.util.Arrays.fill(resentDowns, false)
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

    private def isLR(me: MouseEvent) = me match {
        case LeftDown(_) | RightDown(_) => true
        case _ => false
    }

    // The earlier press of the button of a down held scroll mode, and its up was missed: the press
    // of the single or drag trigger (triggerStarted), or one of the pair of the LR, left and right
    // triggers (both downs suppressed, see EventWaiter.setFlagsOffer; earlierDownSuppressed). Not
    // e.g. the middle trigger's for an X1 down, nor a down passed in the LR trigger's scroll mode
    // after the first up (the other button still holds it). By button, whatever the other buttons
    // have done since (lastEvent).
    private def isHeldByEarlierPress(down: MouseEvent): Boolean =
        triggerStarted.sameEvent(down) || (ctx.isDoubleTrigger && isLR(down) && earlierDownSuppressed)

    private def checkSameLastEvent(me: MouseEvent): Option[LRESULT] = {
        val held = me.isDown && isHeldByEarlierPress(me)
        if (me.isDown && (me.sameEvent(lastEvent) || held)) {
            // A down again with no up of the button between (the buttons do not repeat): the up of
            // the earlier press was missed (e.g. released on the secure desktop or over a window of
            // a higher integrity level). A new press: passed, its up would be handled by the state
            // of the earlier one (e.g. suppressed as the trigger's), leaving the button pressed. The
            // scroll mode that press held could not end by its up any more: it is taken for locked,
            // so that this down ends it (checkExitScrollDown, its up suppressed too). Only the
            // scroll mode of this button (isHeldByEarlierPress), also if other buttons were pressed
            // since.
            debug("down again (the up was missed)", me)
            if (held && ctx.isPressedMouseScrollMode)
                ctx.setReleasedScrollMode

            if (triggerStarted.sameEvent(me))
                triggerStarted = NonEvent(null)

            lastEvent = me
            None
        }
        else if (me.sameEvent(lastEvent)) {
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

    // The scroll mode of the keyboard trigger is held: pressing a mouse trigger ends it. Its down is
    // suppressed, and so is its up (checkSuppressedDown). For the LR, left and right triggers, see
    // passPressedScrollMode.
    private def checkExitOtherScroll(down: MouseEvent): Option[LRESULT] = {
        if (ctx.isPressedKeyboardScrollMode) {
            debug("exit scroll mode (other trigger)", down)
            ctx.exitScrollMode
            ctx.LastFlags.setSuppressed(down)
            suppress
        }
        else
            None
    }

    // While the keyboard trigger's scroll mode is held, the buttons of the LR, left and right
    // triggers wait as when not scrolling: if the trigger is pressed (the second down, offered to
    // the wait before, whichever button it is), it ends that scroll mode instead of starting one
    // (EventWaiter.waiterThread), as another trigger does; otherwise the down is resent (or the
    // click) as usual
    private def passPressedScrollMode(down: MouseEvent): Option[LRESULT] = {
        val isTriggerButton = ctx.isLRTrigger || ctx.isTriggerEvent(down)
        if (ctx.isPressedScrollMode && !(ctx.isPressedKeyboardScrollMode && isTriggerButton)) {
            debug("pass scroll mode (Pressed)", down)
            ctx.LastFlags.setPassed(down)
            callNextHook
        }
        else
            None
    }

    // The trigger pressed on the window of an excluded program (under the cursor, see
    // Windows.isExcludedWindowAt): its down passes, and its up (checkPassedDown), as when it is not
    // the trigger. Only where a wait would start: a scroll mode (and a wait) in progress gets the
    // down as before (e.g. the locked scroll mode ends). Not looked up for the other button of the
    // Left and Right triggers, which passes anyway (endNotTrigger). Nor while the keyboard trigger
    // holds scroll mode: its wait ends it (passPressedScrollMode), as the other triggers end it on
    // an excluded window (checkExitOtherScroll before passExcludedWindow).
    private def passExcludedWindowLR(down: MouseEvent): Option[LRESULT] = {
        if ((ctx.isLRTrigger || ctx.isTriggerEvent(down)) && !ctx.isPressedKeyboardScrollMode &&
            Windows.isExcludedWindowAt(down.info.pt)) {
            debug("pass (excluded program)", down)
            ctx.LastFlags.setPassed(down)
            callNextHook
        }
        else
            None
    }

    // As passExcludedWindowLR for the single and drag triggers, where scroll mode would start: their
    // up passes as the up of a down that did not start it (passNotStartedTrigger)
    private def passExcludedWindow(down: MouseEvent): Option[LRESULT] = {
        if (Windows.isExcludedWindowAt(down.info.pt)) {
            debug("pass (excluded program)", down)
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

    // The wait of the earlier press of the button of a down may have timed out after procDown reset
    // the flags of this down: its resent flag (EventWaiter.fromTimeout) is not of this press, whose
    // up would be resent for a down the hook drops (its press is not the current one), with the
    // wait of this press not offered that up (its down resent later, left pressed). Reset it when
    // no wait is in progress any more: a timeout is handled before the wait ends (EventWaiter.poll).
    // No flag of this press has been set yet (lrDown).
    private def resetRacedResentDown(down: MouseEvent): Unit =
        ctx.LastFlags.resetFor(down)

    private def offerEventWaiter(me: MouseEvent): Option[LRESULT] = {
        // The up of the other button than the waiting down, whose own down did not reach the
        // application (e.g. suppressed by a wait canceled when Pass Mode was turned on): resent
        // with the waiting down, it would be an up without a down. Suppress it, the wait goes on.
        if (me.isUp && EventWaiter.isWaitingOtherThan(me) && Windows.dropLoneUpOf(me)) {
            debug("suppress (up without its down, other button)", me)
            suppress
        }
        // A down of the button whose down is waiting (the up was missed, or of another mouse): not
        // the second down of the trigger (the same button would start scroll mode). A new press,
        // as for checkSameLastEvent: the wait is canceled (its down is not resent: the hook would
        // drop it, its press is not the current one, see Windows.dropResent), this down is handled
        // after.
        else if (me.isDown && EventWaiter.isWaitingFor(me)) {
            debug("cancel the wait (down of the waiting button)", me)
            EventWaiter.cancel()
            resetRacedResentDown(me)
            None
        }
        else if (EventWaiter.offer(me)) {
            debug("success to offer", me)
            // The second down starts LR scroll mode: no up of the pair counted yet (the count of
            // an earlier one may remain, e.g. when the keyboard trigger took over its scroll)
            if (me.isDown) secondTriggerUp = false
            suppress
        }
        else {
            if (me.isDown) resetRacedResentDown(me)
            None
        }
    }

    private def checkSuppressedDown(up: MouseEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_SuppressedDown(up)) {
            debug(s"suppress (checkSuppressedDown)", up)
            suppress
        }
        else
            None
    }

    // Also before skipFirstUp (lrUp): the flag is of this press (reset at its down), e.g. the last
    // event was reset by a trigger change after the down was resent. The last event is this up
    // then, as checkSameLastEvent would set it.
    private def checkResentDown(up: MouseEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_ResentDown(up)) {
            debug("resendUp and suppress (checkResentDown)", up)
            lastEvent = up
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

    // Scroll mode could not start (RawInput could not be registered, see Context.Scroll.start, or
    // W10Wheel is exiting): the down of the trigger passes, as if its button were not a trigger,
    // and so does its up (not the started trigger: passNotStartedTrigger). Suppressed, the click
    // would be lost (no scroll, and no click resent at the up).
    private def passNotStartedScroll(me: MouseEvent): Option[LRESULT] = {
        if (!ctx.isScrollMode) {
            debug("pass (scroll mode not started)", me)
            callNextHook
        }
        else
            None
    }

    private def checkTriggerScrollStart(me: MouseEvent): Option[LRESULT] = {
        if (ctx.isTriggerEvent(me)) {
            debug("start scroll mode", me);
            ctx.startScrollMode(me.info)
            passNotStartedScroll(me).orElse {
                triggerStarted = me
                suppress
            }
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
        passNotStartedScroll(me).orElse {
            triggerStarted = me

            drag = dragStart
            dragged = false

            suppress
        }
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
        if (ctx.isDraggedLock && isDragged) {
            debug("continueScrollDrag (Released)", up)
            ctx.setReleasedScrollMode
            suppress
        }
        else
            None
    }

    private def exitAndResendDrag(me: MouseEvent): Option[LRESULT] = {
        debug("exit scroll mode (Drag)", me)
        val dragged = isDragged
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
            // (the flags of the earlier press are reset by procDown)
            // A wait in progress gets the down first, whatever the scroll mode: started during the
            // keyboard trigger's scroll mode, the second down ends it (see EventWaiter.waiterThread),
            // also if it is locked now (the keyboard trigger released quickly in between); it would
            // not be offered after checkExitScrollDown, and the wait would resend its down
            offerEventWaiter,
            checkExitScrollDown,
            passPressedScrollMode,
            //passSingleEvent,
            // Only to start a wait: scroll mode is left, and a wait in progress gets the down,
            // also on the excluded window (or the wait would resend its down after this one)
            passExcludedWindowLR,
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
            // The down was resent: its up must be resent (and the down it follows), even without a
            // last event (skipFirstUp would pass this up, and the hook drop the down not sent yet)
            checkResentDown,
            skipFirstUp,
            checkSameLastEvent,
            //checkSingleSuppressed,
            checkPassedDown,
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
            checkExitOtherScroll,
            // Also before the middle click it would send (Send MiddleClick): the button as it is
            passExcludedWindow,
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
            checkExitOtherScroll,
            passExcludedWindow,
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

    // As the other handlers, the injected events (of other software, or resent by W10Wheel) pass
    // first: they neither end the locked scroll mode nor initialize the state (Escape)
    private def noneDown(me: MouseEvent): LRESULT = {
        //logger.debug("noneDown")
        val cs: Checkers = List(
            skipResendEventSingle,
            //resetLastFlags,
            checkExitScrollDown,
            endPass
        )

        getResult(cs, me)
    }

    private def noneUp(me: MouseEvent): LRESULT = {
        //logger.debug("noneUp")
        val cs: Checkers = List(
            skipResendEventSingle,
            checkEscape,
            checkSuppressedDown,
            endPass
        )

        getResult(cs, me)
    }

    private type Handler = MouseEvent => LRESULT

    // The handlers of the trigger: of the left and right buttons, of the other buttons
    private def handlersOfTrigger: (Handler, Handler, Handler, Handler) = {
        if (ctx.isDoubleTrigger) {
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
    }

    // Those of the default trigger at first: also used if the properties cannot be read at startup
    // (nothing is applied then, see Context.loadProperties), then set by changeTrigger
    private val (initDownLR, initUpLR, initDownS, initUpS) = handlersOfTrigger
    @volatile private var procDownLR: Handler = initDownLR
    @volatile private var procUpLR: Handler = initUpLR
    @volatile private var procDownS: Handler = initDownS
    @volatile private var procUpS: Handler = initUpS

    // Before the handlers of the trigger (see canceledDowns)
    private def procDown(proc: MouseEvent => LRESULT, d: MouseEvent): LRESULT = {
        earlierDownSuppressed = false
        if (!Windows.isInjectedEvent(d)) {
            val i = buttonIndex(d)
            canceledDowns(i) = false
            if (i < resentDowns.length) resentDowns(i) = false

            // Before the reset (see checkSameLastEvent)
            earlierDownSuppressed = ctx.LastFlags.isSuppressedDown(d)

            // A new press: the state of the earlier one (resent, suppressed, passed) is reset
            // whatever the trigger, for all the buttons (only the LR trigger's handlers did, so
            // after changing the trigger they could take e.g. the next up for that of a resent or
            // suppressed down, and the missed up of a suppressed middle down would make the up of
            // the next middle click suppressed)
            ctx.LastFlags.resetFor(d)
        }

        proc(d)
    }

    private def procUpBody(proc: MouseEvent => LRESULT, u: MouseEvent): LRESULT = {
        val i = buttonIndex(u)
        val physical = !Windows.isInjectedEvent(u)
        if (physical && canceledDowns(i)) {
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
        else if (physical && i < resentDowns.length && resentDowns(i)) {
            resentDowns(i) = false

            // As checkResentDown does (after Esc, as lrUp)
            checkEscape(u) match {
                case Some(res) => res
                case None =>
                    debug("resendUp and suppress (the down was resent before changing the trigger)", u)
                    lastEvent = u
                    Windows.resendUp(u)
                    suppress.get
            }
        }
        else
            proc(u)
    }

    private def procUp(proc: MouseEvent => LRESULT, u: MouseEvent): LRESULT = {
        val res = procUpBody(proc, u)

        // The press is over: its state (resent, suppressed, passed) is not needed any more, reset
        // it whatever handled the up (e.g. after changing the trigger, the handlers of the new
        // one do not use it, and it would be taken for that of a later up whose down was not
        // seen)
        if (!Windows.isInjectedEvent(u))
            ctx.LastFlags.resetFor(u)

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

    // The press of the drag trigger is a drag (its click is not resent at the up) if the cursor
    // would have moved from the start point (scroll mode suppresses the moves), or if it has
    // scrolled (Context.isScrolled: the scroll uses RawInput, a slight move with enhanced pointer
    // precision can scroll without moving the cursor, and so can a move toward the edge of the
    // screen from it). Not for a move of other software (injected), nor for one that neither moved
    // the cursor nor scrolled: the click is resent then, the scroll and the click never both.
    private def isDragged: Boolean =
        dragged || ctx.isScrolled

    private def dragStart(info: HookInfo): Unit = {
        val (sx, sy) = ctx.getScrollStartPoint
        if (!Windows.isInjectedEvent(Move(info)) && (info.pt.x != sx || info.pt.y != sy)) {
            if (ctx.isCursorChange && !ctx.isVhAdjusterMode)
                Windows.changeCursorV

            drag = dragDefault
            dragged = true
        }
    }

    def move(info: HookInfo): LRESULT = {
        if (ctx.isScrollMode) {
            drag(info)
            //Windows.sendWheel(info.pt)
            //Windows.sendWheel(info.pt.x, info.pt.y)
            suppress.get
        }
        // The moves of other software (injected) and those at the point of the waiting down (the
        // cursor has not moved) do not end the LR trigger wait (its down would be resent as for a
        // drag): they pass
        else if (Windows.isInjectedEvent(Move(info)) || EventWaiter.isWaitingAt(Move(info))) {
            callNextHook.get
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

        val (downLR, upLR, downS, upS) = handlersOfTrigger

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

            // The downs resent alone (the wait timed out or was ended by a move): their up is resent
            // before the new handlers, which would not resend it (see resentDowns)
            ctx.LastFlags.takeResentDowns.zipWithIndex.foreach { case (b, i) =>
                if (b) resentDowns(i) = true
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