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
    private val logger = ctx.logger
    private var lastEvent: KeyboardEvent = null

    private var __callNextHook: () => LRESULT = null
    def setCallNextHook(f: () => LRESULT) =
        __callNextHook = f

    def initState() {
        lastEvent = null
    }

    private def callNextHook: Option[LRESULT] = Some(__callNextHook())
    private def suppress: Option[LRESULT] = Some(new LRESULT(1))

    private def skipFirstUp(ke: KeyboardEvent): Option[LRESULT] = {
        if (lastEvent == null) {
            logger.debug(s"skip first Up: ${ke.name}")
            callNextHook
        }
        else
            None
    }

    /*
    private def resetLastFlags(ke: KeyboardEvent): Option[LRESULT] = {
        logger.debug(s"reset last flag: ${ke.name}")
        ctx.LastFlags.reset(ke)
        None
    }
    */

    private def checkSameLastEvent(ke: KeyboardEvent): Option[LRESULT] = {
        if (ke.same(lastEvent) && ctx.isScrollMode) {
            logger.debug(s"same last event: ${ke.name}")
            suppress
        }
        else {
            lastEvent = ke
            None
        }
    }

    private def passNotTrigger(ke: KeyboardEvent): Option[LRESULT] = {
        if (!ctx.isTriggerKey(ke)) {
            logger.debug(s"pass not trigger: ${ke.name}")
            callNextHook
        }
        else
            None
    }

    private def checkTriggerScrollStart(ke: KeyboardEvent): Option[LRESULT] = {
        if (ctx.isTriggerKey(ke)) {
            logger.debug(s"start scroll mode: ${ke.name}");
            ctx.startScrollMode(ke.info)
            suppress
        }
        else
            None
    }

    private def checkExitScrollDown(ke: KeyboardEvent): Option[LRESULT] = {
        if (ctx.isReleasedScrollMode) {
            logger.debug(s"exit scroll mode (Released): ${ke.name}");
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
                logger.debug(s"exit scroll mode (Pressed): ${ke.name}")
                ctx.exitScrollMode
            }
            else {
                logger.debug(s"continue scroll mode (Released): ${ke.name}")
                ctx.setReleasedScrollMode
            }

            suppress
        }
        else
            None
    }

    private def checkSuppressedDown(up: KeyboardEvent): Option[LRESULT] = {
        if (ctx.LastFlags.getAndReset_SuppressedDown(up)) {
            logger.debug(s"suppress (checkSuppressedDown): ${up.name}")
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
            val res = f(ke)
            if (res.isDefined) res.get else getResult(fs, ke)
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

    /*
    def syskeyDown(info: KHookInfo): LRESULT = {
        logger.debug(s"syskeyDown: ${info.vkCode}")

        callNextHook.get
    }

    def syskeyUp(info: KHookInfo): LRESULT = {
        logger.debug(s"syskeyUp: ${info.vkCode}")

        callNextHook.get
    }
    */
}