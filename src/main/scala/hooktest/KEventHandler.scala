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

    // The trigger key pressed (its down suppressed, as the scroll it started) is suppressed like the
    // other keys whose down was (LastFlags.init): its up alone is suppressed, or it would pass
    // (e.g. the Alt key would activate the menu bar)
    def initState(): Unit = {
        lastEvent match {
            case KeyDown(_) => ctx.LastFlags.setSuppressedKey(lastEvent.vkCode)
            case _ => ()
        }

        lastEvent = KNonEvent(null)
    }

    private def callNextHook: Option[LRESULT] = Some(__callNextHook())
    private def suppress: Option[LRESULT] = Some(new LRESULT(1))

    private def debug(msg: String, ke: KeyboardEvent): Unit = {
        logger.debug(msg + ": " + ke.name)
    }

    // An up of the trigger key whose down W10Wheel did not handle (the last event is not its down):
    // the first one, or one whose down was not seen (e.g. pressed over a window of a higher
    // integrity level). The down reached the application (or Windows): the up passes, or the key
    // would be left pressed. (The ups of the downs suppressed otherwise are handled before, see
    // keyUp.)
    private def skipFirstUp(ke: KeyboardEvent): Option[LRESULT] = {
        if (!isKeyDown(lastEvent)) {
            debug("skip Up (its down not handled)", ke)
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

    // The scroll mode of a mouse trigger is held (or its start pending): pressing the keyboard
    // trigger ends it. Its down is suppressed, and so are its repeats and its up (keyUp).
    private def checkExitOtherScroll(ke: KeyboardEvent): Option[LRESULT] = {
        if (ctx.isPressedMouseScrollMode || ctx.isStartingScrollMode) {
            debug("exit scroll mode (other trigger)", ke)
            ctx.exitScrollMode
            ctx.LastFlags.setSuppressed(ke)
            suppress
        }
        else
            None
    }

    private def singleDown(ke: KeyboardEvent): LRESULT = {
        val cs: Checkers = List(
                checkSameLastEvent,
                checkExitScrollDown,
                checkExitOtherScroll,
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
        ctx.LastFlags.resetLoneUpKey(kd.vkCode)

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
        // The up alone of a key whose down was suppressed before the state was initialized
        else if (ctx.LastFlags.getAndReset_LoneUpKey(ku.vkCode)) {
            debug("suppress (up alone of a suppressed down)", ku)
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

    // The keyboard hook is installed (again, on every load). The scroll mode the trigger key started
    // would not end (its up may have been missed), and its next down would be taken for a repeat:
    // end it and forget the last event. The passed keys are kept until their up (passing it is
    // safe).
    // - The old hook was still installed (removed just before, no key missed): the keys whose down
    //   was suppressed, the trigger key pressed included, are suppressed for their up alone (as
    //   after initState), and those already are kept: the up of a key still pressed would pass
    //   alone after a second Reload. The next press of a key whose up was missed works.
    // - Windows had removed it (oldHookLost, e.g. for a timeout): what was recorded of the presses
    //   before (the last event, the suppressed keys, those suppressed for their up alone) is
    //   forgotten. A key may have been released and pressed again meanwhile, its down reaching
    //   the application: its up must pass, or the key is left pressed there. (A key held all
    //   along has its up passed alone: not as bad.)
    // (They are all forgotten too when the hook is turned off: changeTrigger, LastFlags.resetKeys.)
    def hookInstalled(oldHookLost: Boolean): Unit = {
        if (oldHookLost)
            ctx.LastFlags.forgetSuppressedKeys
        else {
            // The trigger key pressed (its down suppressed, as the scroll it started): suppress its
            // up alone, or it would pass. Its next down is taken for a new press (a repeat cannot
            // be told from it), the repeats of a key still pressed start scroll mode again.
            lastEvent match {
                case KeyDown(_) => ctx.LastFlags.setSuppressedKey(lastEvent.vkCode)
                case _ => ()
            }

            ctx.LastFlags.suppressedKeysToLoneUp
        }

        lastEvent = KNonEvent(null)

        if (ctx.isKeyboardScrollMode) {
            logger.debug("hookInstalled: exit keyboard scroll mode")
            ctx.exitScrollMode
        }
    }
}