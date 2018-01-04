package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import scala.annotation.tailrec
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

object KEventHandler {
    private val ctx = Context
    private val logger = Logger.getLogger()
    private var lastEvent: KeyboardEvent = KNonEvent(null)

    private var __callNextHook: () => LRESULT = null
    def setCallNextHook(f: () => LRESULT) =
        __callNextHook = f

    def initState() {
        lastEvent = KNonEvent(null)
    }

    private def callNextHook: Option[LRESULT] = Some(__callNextHook())
    private def suppress: Option[LRESULT] = Some(new LRESULT(1))

    private def debug(msg: String, ke: KeyboardEvent) {
        logger.debug(msg + ": " + ke.name)
    }

    private def skipFirstUp(ke: KeyboardEvent): Option[LRESULT] = {
        if (lastEvent == KNonEvent(null)) {
            debug("skip first Up", ke)
            callNextHook
        }
        else
            None
    }

    /*
    private def resetLastFlags(ke: KeyboardEvent): Option[LRESULT] = {
        debug("reset last flag", ke)
        ctx.LastFlags.reset(ke)
        None
    }
    */

    private def checkSameLastEvent(ke: KeyboardEvent): Option[LRESULT] = {
        if (ke.same(lastEvent) && ctx.isScrollMode) {
            debug("same last event", ke)
            suppress
        }
        else {
            lastEvent = ke
            None
        }
    }

    private def passNotTrigger(ke: KeyboardEvent): Option[LRESULT] = {
        if (!ctx.isTriggerKey(ke)) {
            debug("pass not trigger", ke)
            callNextHook
        }
        else
            None
    }

    private def checkTriggerScrollStart(ke: KeyboardEvent): Option[LRESULT] = {
        if (ctx.isTriggerKey(ke)) {
            debug("start scroll mode", ke)
            ctx.startScrollMode(ke.info)
            suppress
        }
        else
            None
    }

    private def checkExitScrollDown(ke: KeyboardEvent): Option[LRESULT] = {
        if (ctx.isReleasedScrollMode) {
            debug("exit scroll mode (Released)", ke)
            ctx.exitScrollMode
            ctx.LastFlags.setSuppressed(ke)
            suppress
        }
        else
            None
    }

    private def checkExitScrollUp(ke: KeyboardEvent): Option[LRESULT] = {
        if (ctx.isPressedScrollMode) {
            if (ctx.checkExitScroll(ke.info.time)) {
                debug("exit scroll mode (Pressed)", ke)
                ctx.exitScrollMode
            }
            else {
                debug("continue scroll mode (Released)", ke)
                ctx.setReleasedScrollMode
            }

            suppress
        }
        else
            None
    }

    private def checkSuppressedDown(up: KeyboardEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_SuppressedDown(up)) {
            debug("suppress (checkSuppressedDown)", up)
            suppress
        }
        else
            None
    }

    private def endCallNextHook(ke: KeyboardEvent, msg: String): Option[LRESULT] = {
        logger.debug(msg)
        callNextHook
    }

    private def endPass(ke: KeyboardEvent): Option[LRESULT] = {
        endCallNextHook(ke, s"endPass: ${ke.name}")
    }

    private def endIllegalState(ke: KeyboardEvent): Option[LRESULT] = {
        logger.warn(s"illegal state: ${ke.name}")
        suppress
    }

    type Checkers = List[KeyboardEvent => Option[LRESULT]]

    @tailrec
    private def getResult(cs: Checkers, ke: KeyboardEvent): LRESULT = cs match {
        case f :: fs => {
            f(ke) match {
                case Some(res) => res
                case None => getResult(fs, ke)
            }
        }
        case _ => throw new IllegalArgumentException()
    }

    private def singleDown(ke: KeyboardEvent): LRESULT = {
        val cs: Checkers = List(
                checkSameLastEvent,
                checkExitScrollDown,
                checkTriggerScrollStart,
                endIllegalState
        )

        getResult(cs, ke)
    }

    private def singleUp(ke: KeyboardEvent): LRESULT = {
        val cs: Checkers = List(
                skipFirstUp,
                checkSameLastEvent,
                checkSuppressedDown,
                checkExitScrollUp,
                endIllegalState
        )

        getResult(cs, ke)
    }

    private def noneDown(ke: KeyboardEvent): LRESULT = {
        val cs: Checkers = List(
            checkExitScrollDown,
            endPass
        )

        getResult(cs, ke)
    }

    private def noneUp(ke: KeyboardEvent): LRESULT = {
        val cs: Checkers = List(
            checkSuppressedDown,
            endPass
        )

        getResult(cs, ke)
    }

    def keyDown(info: KHookInfo): LRESULT = {
        //logger.debug(s"keyDown: ${info.vkCode}")

        val kd = KeyDown(info)
        if (ctx.isTriggerKey(kd)) singleDown(kd) else noneDown(kd)
    }

    def keyUp(info: KHookInfo): LRESULT = {
        //logger.debug(s"keyUp: ${info.vkCode}")

        val ku = KeyUp(info)
        if (ctx.isTriggerKey(ku)) singleUp(ku) else noneUp(ku)
    }
}