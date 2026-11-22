package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec

object EventWaiter {
    // http://hsmemo.github.io/articles/no3059sSJ.html
    private val THREAD_PRIORITY = 7 // THREAD_PRIORITY_ABOVE_NORMAL

    private val ctx = Context
    private val logger = Logger.getLogger()
    private val sync = new SynchronousQueue[MouseEvent](true)

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
                logger.debug(s"setFlagsOffer - setResent (Up): ${waitingEvent.name}")
                ctx.LastFlags.setResent(waitingEvent)
            }
            case LeftDown(_) | RightDown(_) => {
                logger.debug(s"setFlagsOffer - setSuppressed: ${waitingEvent.name}")
                ctx.LastFlags.setSuppressed(waitingEvent)
                ctx.LastFlags.setSuppressed(me)
                ctx.setStartingScrollMode
            }
            case Cancel(_) => logger.debug("setFlagsOffer: cancel")
            case _ => throw new IllegalStateException(me.name)
        }
    }

    def offer(me: MouseEvent): Boolean = {
        if (waiting.get) {
            @tailrec
            def loop(): Boolean = {
                if (sync.offer(me)) {
                    setFlagsOffer(me)
                    true
                }
                else {
                    if (waiting.get) {
                        Thread.sleep(0)
                        loop()
                    }
                    else
                        false
                }
            }

            loop()
        }
        else
            false
    }

    private def poll(timeout: Long): Option[MouseEvent] = {
        try {
            Option(sync.poll(timeout, TimeUnit.MILLISECONDS))
        }
        finally {
            waiting.set(false)
        }
    }

    // w: the physical down waited for, with the Pass Mode generation and the press recorded when
    // it was received (see start)
    private case class Wait(down: MouseEvent, generation: Int, press: Int)

    private def fromTimeout(w: Wait): Unit = {
        val down = w.down

        // The resent flag is also seen by the up of a later press: if Pass Mode has been turned on
        // (or the state initialized) since the physical down, neither set it nor resend
        if (ctx.isPassMode || ctx.getPassModeGeneration != w.generation) {
            logger.debug(s"wait Trigger (${down.name} -->> Timeout): dropped (Pass Mode)")
        }
        else {
            logger.debug(s"wait Trigger (${down.name} -->> Timeout): resend ${down.name}")
            // The flag first: the physical up coming before the down is queued is then resent too
            // (not passed before the down), and InputSender holds that up until the down is handled
            ctx.LastFlags.setResent(down)
            Windows.resendDown(w.generation, w.press, down)
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

    private val waiterQueue = new SynchronousQueue[Wait](true)

    private val waiterThread = new Thread(() =>
        while (true) {
            try {
                val w = waiterQueue.take

                poll(Context.getPollTimeout) match {
                    case Some(res) => dispatchEvent(w, res)
                    case None => fromTimeout(w)
                }
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
        // Called by the mouse hook when the physical down is received: record the Pass Mode
        // generation and a new press now, the resend happens later on the waiter thread
        val press = InputSender.newPress(Windows.getButton(down))
        waiterQueue.put(Wait(down, ctx.getPassModeGeneration, press))
        waiting.set(true)
    }


}