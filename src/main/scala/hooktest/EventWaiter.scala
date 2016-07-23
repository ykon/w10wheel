package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

//import scala.concurrent._
//import ExecutionContext.Implicits.global
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
    
    def isWaiting = waiting
    
    private def fromTimeout(down: MouseEvent) {
        Context.LastFlags.setResent(down)
        logger.debug(s"wait Trigger (${down.name} -->> Timeout): resend ${down.name}")
        Windows.resendDown(down)
    }
    
    private def fromMove(down: MouseEvent) {
        Context.LastFlags.setResent(down)
        logger.debug(s"wait Trigger (${down.name} -->> Move): resend ${down.name}")
        Windows.resendDown(down)
    }
    
    private def fromUp(down: MouseEvent, up: MouseEvent) {
        Context.LastFlags.setResent(down)
        
        def resendC(mc: MouseClick) = {
            logger.debug(s"wait Trigger (${down.name} -->> ${up.name}): resend ${mc.name}")
            Windows.resendClick(mc)
        }
        
        def resendUD = {
            logger.debug(s"wait Trigger (${down.name} -->> ${up.name}): resend ${down.name}, ${up.name}")
            Windows.resendDown(down)
            Windows.resendUp(up)
        }
        
        down match {
            case LeftDown(_) => up match {
                case LeftUp(_) => resendC(LeftClick(down.info))
                case RightUp(_) => resendUD
            }
            case RightDown(_) => up match {
                case RightUp(_) => resendC(RightClick(down.info))
                case LeftUp(_) => resendUD
            }
        }
    }
    
    private def fromDown(d1: MouseEvent, d2: MouseEvent) {
        Context.LastFlags.setSuppressed(d1)
        Context.LastFlags.setSuppressed(d2)
        
        logger.debug(s"wait Trigger (${d1.name} -->> ${d2.name}): start scroll mode")
        Context.startScrollMode(d2.info)
    }
    
    private val waiterQueue = new SynchronousQueue[MouseEvent](true)
    
    private val waiterThread = new Thread(new Runnable {
        override def run {
            while (true) {
                val down = waiterQueue.take
                
                logger.debug("EventWaiter: poll")
                val res = sync.poll(Context.getPollTimeout, TimeUnit.MILLISECONDS)
                waiting = false
            
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
    waiterThread.start
    
    // RightDown or LeftDown
    def start(down: MouseEvent) {
        if (!down.isDown)
            throw new IllegalArgumentException
        
        waiting = true
        waiterQueue.put(down)
    }
}