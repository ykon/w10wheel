package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

//import scala.concurrent._
//import ExecutionContext.Implicits.global
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

object EventWaiter {
    // http://hsmemo.github.io/articles/no3059sSJ.html
    private val THREAD_PRIORITY = 7 // THREAD_PRIORITY_ABOVE_NORMAL
    
    private val ctx = Context
    private val logger = ctx.logger
    private val sync = new SynchronousQueue[MouseEvent](true)
    
    @volatile private var waiting = false
    private var waitingEvent: MouseEvent = null
    
    private def setFlagsOffer(me: MouseEvent) {
        //logger.debug("setFlagsOffer")
        me match {
            case Move(_) | LeftUp(_) | RightUp(_) => {
                logger.debug(s"setFlagsOffer - setResent: ${waitingEvent.name}")
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
    
    private def isWaiting = waiting
    
    def offer(me: MouseEvent): Boolean = {
        if (isWaiting && sync.offer(me)) {
            setFlagsOffer(me)
            true
        }
        else
            false
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
        }
    }
    
    private def fromDown(d1: MouseEvent, d2: MouseEvent) {
        //ctx.LastFlags.setSuppressed(d1)
        //ctx.LastFlags.setSuppressed(d2)
        
        logger.debug(s"wait Trigger (${d1.name} -->> ${d2.name}): start scroll mode")
        Context.startScrollMode(d2.info)
    }
    
    private val waiterQueue = new SynchronousQueue[MouseEvent](true)
    
    private val waiterThread = new Thread(new Runnable {
        override def run {
            while (true) {
                val down = waiterQueue.take
                var res = sync.poll(Context.getPollTimeout, TimeUnit.MILLISECONDS)
                waiting = false
                
                if (res == null) {
                    Thread.sleep(0)
                    res = sync.poll()
                }
                
                res match {
                    case null => fromTimeout(down)
                    case Move(_) => fromMove(down)
                    case LeftUp(_) | RightUp(_) => fromUp(down, res)
                    case LeftDown(_) | RightDown(_) => fromDown(down, res)
                }
            }
        }
    })
    
    waiterThread.setDaemon(true)
    waiterThread.setPriority(THREAD_PRIORITY)
    waiterThread.start
    
    // RightDown or LeftDown
    def start(down: MouseEvent) {
        if (!down.isDown)
            throw new IllegalArgumentException
        
        waiting = true
        waitingEvent = down
        waiterQueue.put(down)
    }
}