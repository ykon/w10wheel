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
    // other keys whose down was (LastFlags.suppressedKeysToLoneUp): its up alone is suppressed, or
    // it would pass (e.g. the Alt key would activate the menu bar)
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

    // Scroll mode could not start (RawInput could not be registered, see Context.Scroll.start, or
    // W10Wheel is exiting): the down passes, as if the key were not the trigger (as the mouse
    // triggers do, EventHandler.passNotStartedScroll). Its repeats and its up pass too (a passed
    // key: keyDown, keyUp), and its next down is not taken for a repeat (lastEvent). Suppressed,
    // the key would be lost, and its up taken for an illegal state.
    private def checkTriggerScrollStart(ke: KeyboardEvent): Option[LRESULT] = {
        if (ctx.isTriggerKey(ke)) {
            debug("start scroll mode", ke)
            ctx.startScrollMode(ke.info)
            if (!ctx.isScrollMode) {
                debug("pass (scroll mode not started)", ke)
                lastEvent = KNonEvent(null)
                endPassDown(ke)
            }
            else
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

    // The trigger key pressed with the cursor on the window of an excluded program
    // (Windows.isExcludedWindowAtCursor): its down passes, and its repeats and its up (keyDown,
    // keyUp), as a key that is not the trigger. Not the last event: its next down would be taken for
    // a repeat (checkSameLastEvent), and suppressed.
    private def passExcludedWindow(ke: KeyboardEvent): Option[LRESULT] = {
        if (Windows.isExcludedWindowAtCursor) {
            debug("pass (excluded program)", ke)
            lastEvent = KNonEvent(null)
            endPassDown(ke)
        }
        else
            None
    }

    private def singleDown(ke: KeyboardEvent): LRESULT = {
        val cs: Checkers = List(
                checkSameLastEvent,
                checkExitScrollDown,
                checkExitOtherScroll,
                passExcludedWindow,
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

        // The Pause key is a key as the others: held, it repeats too (a USB keyboard), and its up
        // comes (with its down at once, a PS/2 one)
        val kd = KeyDown(info)
        ctx.LastFlags.resetLoneUpKey(kd.vkCode)

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
        // Already down in the system: in the hook, GetAsyncKeyState still tells the state from
        // before this event, so this is a repeat of a press whose down reached the application with
        // no record of it here. E.g. passed just before a reset posted earlier (at an unlock or a
        // resume, SessionEvents) was run: the hook's events are handled before the tasks posted,
        // and the reset forgot it. It passes as that press did, its up too: taken as a new press,
        // it would start a scroll (or end one) and be suppressed, its up too, the key left pressed
        // in the application.
        else if (Windows.isKeyDown(kd.vkCode)) {
            debug("pass (repeat of a down with no record)", kd)
            // The last event may be the down of an earlier press of the trigger key whose up was
            // missed: not of this press. Kept, a reset (initState) or a change of the trigger while
            // this press is held would take this press for that one, suppressed (its up too: the
            // key left pressed in the application). As passExcludedWindow does.
            if (kd.same(lastEvent)) lastEvent = KNonEvent(null)
            endPassDown(kd).get
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
            // As above: the last event may be the down of an earlier press of the trigger key whose
            // up was missed (this press passed with no record of it, keyDown), and the next press
            // would be taken for its repeat, suppressed with no scroll
            if (ctx.isTriggerKey(ku)) lastEvent = ku
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

        if (!keyChanged) {
            logger.debug("changeTrigger (keyboard): the hook removed")
            ctx.keyboardHookGone()
        }
        else {
            if (pressed)
                ctx.LastFlags.setSuppressedKey(oldVK)

            lastEvent = KNonEvent(null)

            if (ctx.isKeyboardScrollMode) {
                logger.debug("changeTrigger (keyboard): exit scroll mode")
                ctx.exitScrollMode
            }
        }
    }

    // The trigger key, if it is down now (GetAsyncKeyState), is taken as passed: its down reached
    // the application with no hook to see it, so its repeats and its up pass (they would start
    // scroll mode, and the up of a down that reached the application be suppressed, the key left
    // pressed there), and its next press is the trigger. Not down also when the state cannot be
    // read: a key taken as up is a new press. The other keys pass anyway.
    private def passHeldTriggerKey(caller: String): Unit = {
        val vk = ctx.getTargetVKCode
        if (vk != 0 && Windows.isKeyDown(vk)) {
            logger.debug(s"$caller: the trigger key down, taken as passed: $vk")
            ctx.LastFlags.setPassedKey(vk)
        }
    }

    // The keyboard hook has been replaced (on every load, after a resume or an unlock), and Windows
    // had removed the old one (e.g. for a timeout): keys may have been released, and pressed again,
    // with no hook to see it (the new down reached the application). So nothing of before is kept:
    // the scroll mode of the trigger key (which would not end) is ended, and all the keys' records
    // are forgotten, also those of the passed keys (one passed over an excluded program and released
    // meanwhile would pass its next press). (A key held all along whose down was suppressed has its
    // up passed alone: not as bad as a key left pressed in the application.) The trigger key down
    // now passes until its up (passHeldTriggerKey).
    // A hook replaced while the old one was still installed missed nothing: this is not called then
    // (Context.setKeyboardHookOf).
    def hookLost(): Unit = {
        logger.debug("hookLost (keyboard)")
        ctx.keyboardHookGone()
        passHeldTriggerKey("hookLost")
    }

    // The keyboard hook has been installed where there was none (at startup, at the end of a pause,
    // or turned on): the records of the keys were reset when it was removed (changeTrigger), and the
    // keys pressed meanwhile reached the application. The trigger key held now passes until its up
    // (passHeldTriggerKey): its repeats would start scroll mode, and its up be suppressed, the key
    // left pressed in the application.
    def hookInstalled(): Unit =
        passHeldTriggerKey("hookInstalled")
}