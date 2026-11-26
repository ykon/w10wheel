package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object EventWaiter {
    // http://hsmemo.github.io/articles/no3059sSJ.html
    private val THREAD_PRIORITY = 7 // THREAD_PRIORITY_ABOVE_NORMAL

    private val ctx = Context
    private val logger = Logger.getLogger()

    // The event offered to the wait (one at most)
    private val sync = new ArrayBlockingQueue[MouseEvent](1)

    // A wait is in progress: set by start (before the wait is queued), reset when the wait has
    // been handled (see poll)
    private val waiting = new AtomicBoolean(false)
    private var waitingEvent: MouseEvent = NonEvent(null)

    private def setFlagsOffer(me: MouseEvent): Unit = {
        //logger.debug("setFlagsOffer")
        me match {
            case Move(_) => {
                logger.debug(s"setFlagsOffer - setResent (Move): ${waitingEvent.name}")
                ctx.LastFlags.setResent(waitingEvent)
                //Thread.sleep(1)
            }
            case LeftUp(_) | RightUp(_) => {
                // Only for the up of the other button: the waiting down is resent and its physical
                // up comes later. The up of the same button is resent with the down (the click is
                // done).
                if (!waitingEvent.sameButton(me)) {
                    logger.debug(s"setFlagsOffer - setResent (Up): ${waitingEvent.name}")
                    ctx.LastFlags.setResent(waitingEvent)
                }
            }
            case LeftDown(_) | RightDown(_) => {
                logger.debug(s"setFlagsOffer - setSuppressed: ${waitingEvent.name}")
                ctx.LastFlags.setSuppressed(waitingEvent)
                ctx.LastFlags.setSuppressed(me)
                // The scroll is starting already (waiterThread)
            }
            case Cancel(_) => logger.debug("setFlagsOffer: cancel")
            case _ => throw new IllegalStateException(me.name)
        }
    }

    // Returns when the wait has handled the event (true) or has ended without it (false): what
    // the wait queues (resends) comes before what the hook queues for the events after (e.g. the
    // resent up for the physical up of a resent down)
    def offer(me: MouseEvent): Boolean = {
        if (waiting.get) {
            if (!sync.offer(me))
                throw new IllegalStateException(s"EventWaiter.offer: ${me.name}")

            while (waiting.get)
                Thread.`yield`()

            // Still there: the wait ended (timeout) before taking it
            if (sync.poll() == null) {
                setFlagsOffer(me)
                true
            }
            else
                false
        }
        else
            false
    }

    // Ends the wait in progress as a move does (on the hook thread): its down is resent now, its up
    // at its physical up (EventHandler.checkResentDown). For an event that passes as it is (me, at
    // whose point): another button's down or up, or the up of the other button of the pair whose
    // down passed (EventHandler.endWaitByOtherButton, checkPassedDown). False if no wait got it
    // (none, or it has timed out meanwhile: its down resent).
    def endByResend(me: MouseEvent): Boolean =
        offer(Move(me.info))

    // Cancels the wait (on the hook thread, e.g. when the trigger changes): the down that was
    // waiting, if the wait was canceled (the down is not resent)
    def cancel(): Option[MouseEvent] = {
        val down = waitingEvent
        if (offer(Cancel(null))) Some(down) else None
    }

    // Whether a wait is in progress for a down of another button than up's (on the hook thread)
    def isWaitingOtherThan(up: MouseEvent): Boolean =
        waiting.get && !waitingEvent.sameButton(up)

    // Whether a wait is in progress for a down of the same button as down's (on the hook thread)
    def isWaitingFor(down: MouseEvent): Boolean =
        waiting.get && waitingEvent.sameEvent(down)

    // Whether a wait is in progress for a down at the point of me (on the hook thread): a move
    // there has not moved the cursor
    def isWaitingAt(me: MouseEvent): Boolean =
        waiting.get && Mouse.samePoint(waitingEvent, me)

    // handle is called with the result (None: timeout) before waiting is reset, so before an
    // offer made meanwhile returns
    private def poll(timeout: Long)(handle: Option[MouseEvent] => Unit): Unit = {
        try {
            handle(Option(sync.poll(timeout, TimeUnit.MILLISECONDS)))
        }
        finally {
            waiting.set(false)
        }
    }

    // w: the physical down waited for, with the Pass Mode generation and the press (see
    // Windows.pressIdOf) recorded when it was received (see start), and whether the keyboard
    // trigger's scroll mode was on then
    private case class Wait(down: MouseEvent, generation: Int, press: Int, keyboardScroll: Boolean)

    private def fromTimeout(w: Wait): Unit = {
        val down = w.down

        // The resent flag is also seen by the up of a later press: if Pass Mode has been turned on
        // (or the state initialized) since the physical down, neither set it nor resend. Checked
        // under passModeLock, under which Pass Mode is switched.
        ctx.passModeLock.synchronized {
            if (ctx.isPassMode || ctx.getPassModeGeneration != w.generation) {
                logger.debug(s"wait Trigger (${down.name} -->> Timeout): dropped (Pass Mode)")
            }
            else {
                logger.debug(s"wait Trigger (${down.name} -->> Timeout): resend ${down.name}")
                // Queue the down first: the up of a hook seeing the flag is queued after it
                Windows.resendDown(w.generation, w.press, down)
                ctx.LastFlags.setResent(down)
            }
        }
    }

    private def fromMove(w: Wait): Unit = {
        val down = w.down
        //ctx.LastFlags.setResent(down)
        logger.debug(s"wait Trigger (${down.name} -->> Move): resend ${down.name}")
        Windows.resendDown(w.generation, w.press, down)
    }

    private def fromUp(w: Wait, up: MouseEvent): Unit = {
        val down = w.down
        //ctx.LastFlags.setResent(down)

        def resendC(mc: MouseClick) = {
            logger.debug(s"wait Trigger (${down.name} -->> ${up.name}): resend ${mc.name}")
            Windows.resendClick(mc)
        }

        def resendUD = {
            logger.debug(s"wait Trigger (${down.name} -->> ${up.name}): resend ${down.name}, ${up.name}")
            Windows.resendDownUp(w.generation, w.press, down, up)
        }

        (down, up) match {
            case (LeftDown(_), LeftUp(_)) => {
                if (Mouse.samePoint(down, up))
                    resendC(LeftClick(down.info))
                else
                    resendUD
            }
            case (LeftDown(_), RightUp(_)) => resendUD
            case (RightDown(_), RightUp(_)) => {
                if (Mouse.samePoint(down, up))
                    resendC(RightClick(down.info))
                else
                    resendUD
            }
            case (RightDown(_), LeftUp(_)) => resendUD
            case x => {
                throw new IllegalStateException("Not matched: " + x);
            }
        }
    }

    private def fromDown(w: Wait, d2: MouseEvent): Unit = {
        //ctx.LastFlags.setSuppressed(d1)
        //ctx.LastFlags.setSuppressed(d2)

        logger.debug(s"wait Trigger (${w.down.name} -->> ${d2.name}): start scroll mode")
        // Refused if Pass Mode has been turned on (or the state initialized) since the physical down
        Context.startScrollModeFor(w.generation, d2.info)
    }

    private def dispatchEvent(w: Wait, res: MouseEvent) = res match {
        case Move(_) => fromMove(w)
        case LeftUp(_) | RightUp(_) => fromUp(w, res)
        case LeftDown(_) | RightDown(_) => fromDown(w, res)
        case Cancel(_) => logger.debug("dispatchEvent: cancel")
        case _ => throw new IllegalStateException()
    }

    private val waiterQueue = new ArrayBlockingQueue[Wait](1)

    private val waiterThread = new Thread(() =>
        while (true) {
            try {
                val w = waiterQueue.take

                // The scroll start (second down) takes longer: it is done after the offer returns,
                // the hook handles the ups meanwhile as starting (set before the offer returns)
                var second: MouseEvent = NonEvent(null)
                poll(Context.getPollTimeout)(res => res match {
                    case Some(d2 @ (LeftDown(_) | RightDown(_))) =>
                        // The keyboard trigger's scroll mode is on (see EventHandler.
                        // passPressedScrollMode): the LR trigger ends it instead of starting one, as
                        // another trigger does. Decided here, before the offer returns: the hook
                        // waits for it, so the keyboard trigger cannot end the scroll mode meanwhile
                        // (its up), which would let the LR trigger start one after (not as starting
                        // either). Neither started if it was on when the wait started and has ended
                        // since (the keyboard trigger released in between): the trigger was pressed
                        // to end it.
                        if (w.keyboardScroll || ctx.isKeyboardScrollMode) {
                            logger.debug(s"wait Trigger (${w.down.name} -->> ${d2.name}): exit scroll mode (keyboard)")
                            if (ctx.isKeyboardScrollMode)
                                ctx.exitScrollMode
                        }
                        else {
                            ctx.setStartingScrollModeFor(w.generation)
                            second = d2
                        }
                    case Some(res) => dispatchEvent(w, res)
                    case None => fromTimeout(w)
                })

                if (second.isDown)
                    dispatchEvent(w, second)
            }
            catch {
                case e: Exception => logger.warn(s"EventWaiter: $e")
            }
        }
    )

    waiterThread.setDaemon(true)
    waiterThread.setPriority(THREAD_PRIORITY)
    waiterThread.start

    // RightDown or LeftDown
    def start(down: MouseEvent): Unit = {
        if (!down.isDown)
            throw new IllegalArgumentException

        waitingEvent = down
        // Before queuing the wait: set after it, the wait could already have timed out (and reset
        // waiting), leaving waiting set without a wait, so that the next offer would never return
        waiting.set(true)
        // Called by the mouse hook when the physical down is received: record the Pass Mode
        // generation and the press now, the resend happens later on the waiter thread
        waiterQueue.put(Wait(down, ctx.getPassModeGeneration, Windows.pressIdOf(down), ctx.isKeyboardScrollMode))
    }
}
