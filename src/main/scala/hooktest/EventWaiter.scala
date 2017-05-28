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
    private val logger = ctx.logger
    private val sync = new SynchronousQueue[MouseEvent](true)
    
    private val waiting = new AtomicBoolean(false)
    private var waitingEvent: MouseEvent = null
    
    private def setFlagsOffer(me: MouseEvent) {
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
    
    private def fromTimeout(down: MouseEvent) {
        ctx.LastFlags.setResent(down)
        
        logger.debug(s"wait Trigger (${down.name} -->> Timeout): resend ${down.name}")
        Windows.resendDown(down)
    }
    
    private def fromMove(down: MouseEvent) {
        //ctx.LastFlags.setResent(down)
        logger.debug(s"wait Trigger (${down.name} -->> Move): resend ${down.name}")
        Windows.resendDown(down)
    }
    
    private def fromUp(down: MouseEvent, up: MouseEvent) {
        //ctx.LastFlags.setResent(down)
        
        def resendC(mc: MouseClick) = {
            logger.debug(s"wait Trigger (${down.name} -->> ${up.name}): resend ${mc.name}")
            Windows.resendClick(mc)
        }
        
        def resendUD = {
            logger.debug(s"wait Trigger (${down.name} -->> ${up.name}): resend ${down.name}, ${up.name}")
            Windows.resendDown(down)
            Windows.resendUp(up)
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
    
    private def fromDown(d1: MouseEvent, d2: MouseEvent) {
        //ctx.LastFlags.setSuppressed(d1)
        //ctx.LastFlags.setSuppressed(d2)
        
        logger.debug(s"wait Trigger (${d1.name} -->> ${d2.name}): start scroll mode")
        Context.startScrollMode(d2.info)
    }
    
    private def dispatchEvent(down: MouseEvent, res: MouseEvent) = res match {
        case Move(_) => fromMove(down)
        case LeftUp(_) | RightUp(_) => fromUp(down, res)
        case LeftDown(_) | RightDown(_) => fromDown(down, res)
    }
    
    private val waiterQueue = new SynchronousQueue[MouseEvent](true)
    
    private val waiterThread = new Thread(() =>
        while (true) {
            val down = waiterQueue.take
            
            poll(Context.getPollTimeout) match {
                case Some(res) => dispatchEvent(down, res)
                case None => fromTimeout(down)
            }
        }
    )
    
    waiterThread.setDaemon(true)
    waiterThread.setPriority(THREAD_PRIORITY)
    waiterThread.start
    
    // RightDown or LeftDown
    def start(down: MouseEvent) {
        if (!down.isDown)
            throw new IllegalArgumentException
        
        waitingEvent = down
        waiterQueue.put(down)
        waiting.set(true)
    }
}