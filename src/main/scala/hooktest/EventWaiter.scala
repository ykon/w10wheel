package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.concurrent._
import ExecutionContext.Implicits.global
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

object EventWaiter {
    private val logger = Context.logger
    private val sync = new SynchronousQueue[MouseEvent](true)
    @volatile private var waiting = false
    
    def offer(me: MouseEvent) =
        sync.offer(me)
        
    def isWaiting =
        waiting
    
    private def fromTimeout(we: MouseEvent) {
        //we.resent = true
        Context.LastFlags.setResent(we)
        logger.debug(s"wait Trigger (${we.name} -->> Timeout): resend ${we.name}")
        Windows.resendDown(we)
    }
    
    private def fromMove(we: MouseEvent) {
        //we.resent = true
        Context.LastFlags.setResent(we)
        logger.debug(s"wait Trigger (${we.name} -->> Move): resend ${we.name}")
        Windows.resendDown(we)
    }
    
    private def fromUp(we: MouseEvent, res: MouseEvent) {
        //we.resent = true
        //res.resent = true
        Context.LastFlags.setResent(we)
        
        def resendC(mc: MouseClick) = {
            logger.debug(s"wait Trigger (${we.name} -->> ${res.name}): resend ${mc.name}")
            Windows.resendClick(mc)
        }
        
        def resendUD = {
            logger.debug(s"wait Trigger (${we.name} -->> ${res.name}): resend ${we.name}, ${res.name}")
            Windows.resendDown(we)
            Windows.resendUp(res)
        }
        
        we match {
            case LeftDown(_) => res match {
                case LeftUp(_) => resendC(LeftClick(we.info))
                case RightUp(_) => resendUD
            }
            case RightDown(_) => res match {
                case RightUp(_) => resendC(RightClick(we.info))
                case LeftUp(_) => resendUD
            }
        }
    }
    
    private def fromDown(we: MouseEvent, res: MouseEvent) {
        //we.suppressed = true
        //res.suppressed = true
        Context.LastFlags.setSuppressed(we)
        Context.LastFlags.setSuppressed(res)
        
        logger.debug(s"wait Trigger (${we.name} -->> ${res.name}): start scroll mode")
        Context.startScrollMode(res.info)
    }
    
    // RightDown or LeftDown
    def start(we: MouseEvent) = Future {
        if (!we.isDown)
            throw new IllegalArgumentException
        
        waiting = true
        val res = sync.poll(Context.getPollTimeout, TimeUnit.MILLISECONDS)
        waiting = false
        
        res match {
            case null => fromTimeout(we)
            case Move(_) => fromMove(we)
            case LeftUp(_) | RightUp(_) => fromUp(we, res)
            case LeftDown(_) | RightDown(_) => fromDown(we, res)
        }
    }
}