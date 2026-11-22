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
    def getCallNextHook: () => LRESULT = __callNextHook

    def initState(): Unit = {
        lastEvent = KNonEvent(null)
    }

    private def callNextHook: Option[LRESULT] = Some(__callNextHook())
    private def suppress: Option[LRESULT] = Some(new LRESULT(1))

    private def debug(msg: String, ke: KeyboardEvent): Unit = {
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

    private def isKeyDown(ke: KeyboardEvent) = ke match {
        case KeyDown(_) => true
        case _ => false
    }

    private def checkSameLastEvent(ke: KeyboardEvent): Option[LRESULT] = {
        // A key repeat of the trigger key is always suppressed, like its first down: otherwise
        // holding the key after leaving scroll mode with it would start scroll mode again
        if (ke.same(lastEvent) && (ctx.isScrollMode || isKeyDown(ke))) {
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
        // Only the scroll mode it started: a mouse trigger can start scroll mode while it is pressed
        if (ctx.isPressedKeyboardScrollMode) {
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
        // (the ups of suppressed downs are handled in keyUp)
        val cs: Checkers = List(
                skipFirstUp,
                checkSameLastEvent,
                checkExitScrollUp,
                endIllegalState
        )

        getResult(cs, ke)
    }

    // The down of a key passed: its repeats and up pass too (see keyDown)
    private def endPassDown(ke: KeyboardEvent): Option[LRESULT] = {
        ctx.LastFlags.setPassedKey(ke.vkCode)
        endPass(ke)
    }

    private def noneDown(ke: KeyboardEvent): LRESULT = {
        val cs: Checkers = List(
            checkExitScrollDown,
            endPassDown
        )

        getResult(cs, ke)
    }

    private def noneUp(ke: KeyboardEvent): LRESULT = {
        // (the ups of suppressed downs are handled in keyUp)
        val cs: Checkers = List(
            endPass
        )

        getResult(cs, ke)
    }

    def keyDown(info: KHookInfo): LRESULT = {
        //logger.debug(s"keyDown: ${info.vkCode}")

        val kd = KeyDown(info)
        ctx.LastFlags.newKeyDown(kd.vkCode)

        // The trigger key that does not repeat (Pause) may send no up: a down after its down was
        // released, quickly. As its up would, lock the scroll it started (this down then ends it),
        // and do not take the down for a repeat.
        if (Keyboard.isNoRepeatKey(kd.vkCode) && ctx.isTriggerKey(kd) && kd.same(lastEvent)) {
            debug("no up before (no repeat key)", kd)
            if (ctx.isPressedKeyboardScrollMode)
                ctx.setReleasedScrollMode

            lastEvent = KeyUp(info)
        }

        // A key whose down was suppressed (e.g. to leave scroll mode, or the old trigger key when
        // the trigger changed): its repeats are suppressed too, whatever the trigger is now
        if (ctx.LastFlags.isSuppressedKey(kd.vkCode)) {
            debug("suppress (repeat of a suppressed down)", kd)
            new LRESULT(1)
        }
        // A key whose down passed (to the application): its repeats pass too, even if it has
        // become the trigger since (they would start scroll mode, and its up would be suppressed)
        else if (ctx.LastFlags.isPassedKey(kd.vkCode)) {
            debug("pass (repeat of a passed down)", kd)
            __callNextHook()
        }
        else if (ctx.isTriggerKey(kd)) singleDown(kd) else noneDown(kd)
    }

    def keyUp(info: KHookInfo): LRESULT = {
        //logger.debug(s"keyUp: ${info.vkCode}")

        val ku = KeyUp(info)
        // The up of a suppressed down, whatever the trigger is now (it may have changed since)
        if (ctx.LastFlags.getAndReset_SuppressedDown(ku)) {
            debug("suppress (up of a suppressed down)", ku)
            // As the trigger's handlers do, or its next down would be taken for a repeat
            if (ctx.isTriggerKey(ku)) lastEvent = ku
            new LRESULT(1)
        }
        else if (ctx.LastFlags.getAndReset_PassedKey(ku.vkCode)) {
            debug("pass (up of a passed down)", ku)
            __callNextHook()
        }
        else if (ctx.isTriggerKey(ku)) singleUp(ku) else noneUp(ku)
    }

    // The trigger key has changed (keyChanged), or the keyboard hook is removed: the scroll mode
    // the old key started would not end (its up is not the trigger's any more, or not seen). End
    // it. If the old key is pressed after a change (its down suppressed), suppress it until its up
    // (keyUp). Without the hook, the ups will not be seen: forget the suppressed downs.
    def changeTrigger(oldVK: Int, keyChanged: Boolean): Unit = {
        val pressed = lastEvent match {
            case KeyDown(_) => lastEvent.vkCode == oldVK
            case _ => false
        }

        if (!keyChanged)
            ctx.LastFlags.resetKeys
        else if (pressed)
            ctx.LastFlags.setSuppressedKey(oldVK)

        lastEvent = KNonEvent(null)

        if (ctx.isKeyboardScrollMode) {
            logger.debug("changeTrigger (keyboard): exit scroll mode")
            ctx.exitScrollMode
        }
    }

    // The keyboard hook is installed (again, on every load): ups may have been missed while it was
    // removed (e.g. by Windows for a timeout). The scroll mode the trigger key started would not
    // end, and its next down would be taken for a repeat: end it and forget the last event. The
    // suppressed and passed keys are kept until their up: whether a key is still pressed cannot
    // be told for sure.
    def hookInstalled(): Unit = {
        // The trigger key pressed (its down suppressed, as the scroll it started): suppress its
        // repeats and its up until the up (keyUp), or the up alone would pass, or the repeats
        // start scroll mode again. If its up was missed, its next press is suppressed once.
        lastEvent match {
            case KeyDown(_) => ctx.LastFlags.setSuppressedKey(lastEvent.vkCode)
            case _ => ()
        }

        lastEvent = KNonEvent(null)

        if (ctx.isKeyboardScrollMode) {
            logger.debug("hookInstalled: exit keyboard scroll mode")
            ctx.exitScrollMode
        }
    }
}