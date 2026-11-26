package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

// The keyboard trigger (KEventHandler), as W10Wheel.NET tests it (W10Wheel.Tests, KeyboardTests.fs),
// ported from the Rust version (w10wheel.rs, trigger.rs). The code as it is.

import Harness._
import Harness.Button._
import Harness.Decision._

class KeyboardSuite extends munit.FunSuite {
    private def scrolling(): Unit = assert(isScrolling, "scrolling")
    private def notScrolling(): Unit = assert(!isScrolling, "not scrolling")

    // The keyboard trigger on VK_LMENU, the mouse trigger on Middle (scrollLocktime 200 ms)
    private def kb(): Int = {
        reset(DataID.Middle, DataID.VK_LMENU)
        VK_LMENU
    }

    test("other keys pass their down, repeats and up") {
        kb()
        assertEquals(keyDown(VK_A, 10), Pass)
        assertEquals(keyDown(VK_A, 40), Pass)
        assertEquals(keyUp(VK_A, 60), Pass)
        // An up with no down seen passes too
        assertEquals(keyUp(VK_A, 70), Pass)
        // A code out of the table passes, untouched
        assertEquals(keyDown(0x1FF, 80), Pass)
        assertEquals(keyUp(0x1FF, 80), Pass)
        notScrolling()
    }

    test("the trigger key's up ends or releases the scroll by the locktime") {
        val vk = kb()

        // Held longer than the locktime: its repeats are suppressed, and its up ends the scroll
        assertEquals(keyDown(vk, 100), Suppress)
        assertEquals(keyDown(vk, 130), Suppress)
        scrolling()
        assertEquals(keyUp(vk, 500), Suppress)
        notScrolling()

        // Released within the locktime: the scroll goes on, released
        assertEquals(keyDown(vk, 600), Suppress)
        assertEquals(keyUp(vk, 700), Suppress)
        scrolling()

        // Another key ends the released scroll: its down, its repeats and its up are suppressed
        assertEquals(keyDown(VK_A, 800), Suppress)
        notScrolling()
        assertEquals(keyDown(VK_A, 830), Suppress)
        assertEquals(keyUp(VK_A, 850), Suppress)
        // Its next press passes
        assertEquals(keyDown(VK_A, 900), Pass)
        assertEquals(keyUp(VK_A, 950), Pass)

        // The trigger key ends a released scroll of its own: suppressed until its up
        keyDown(vk, 1000)
        keyUp(vk, 1050)
        scrolling()
        assertEquals(keyDown(vk, 1100), Suppress)
        notScrolling()
        assertEquals(keyDown(vk, 1130), Suppress)
        notScrolling()
        assertEquals(keyUp(vk, 1500), Suppress)
        notScrolling()
    }

    test("the trigger key ends a mouse scroll and is suppressed until its up") {
        val vk = kb()
        down(Middle, 0, 0, 100)
        scrolling()

        assertEquals(keyDown(vk, 200), Suppress)
        notScrolling()
        // Its repeats do not start a scroll of their own
        assertEquals(keyDown(vk, 230), Suppress)
        notScrolling()
        assertEquals(keyUp(vk, 300), Suppress)
        notScrolling()

        // Its next press is the trigger
        assertEquals(keyDown(vk, 400), Suppress)
        scrolling()
    }

    test("a mouse trigger ends the keyboard scroll, the key's up is suppressed") {
        val vk = kb()
        assertEquals(keyDown(vk, 100), Suppress)
        scrolling()
        assertEquals(down(Middle, 0, 0, 200), Suppress)
        notScrolling()
        assertEquals(up(Middle, 0, 0, 250), Suppress)
        assertEquals(keyUp(vk, 300), Suppress)
        notScrolling()
    }

    test("a reset leaves the up of a suppressed key to be suppressed alone") {
        val vk = kb()

        // The trigger key in its scroll, reset (--sendInitState): its up is suppressed alone, as
        // its down was, and only that one up
        keyDown(vk, 100)
        Context.initState
        notScrolling()
        assertEquals(keyUp(vk, 200), Suppress)
        notScrolling()
        assertEquals(keyUp(vk, 250), Pass)
        assertEquals(keyDown(vk, 300), Suppress)
        scrolling()
        keyUp(vk, 350)

        // Pressed again before that up (its up missed): the new down is a press of its own, the
        // trigger, and the lone up is forgotten
        Context.initState
        keyDown(vk, 400)
        Context.initState
        assertEquals(keyDown(vk, 500), Suppress)
        scrolling()
        assertEquals(keyUp(vk, 1000), Suppress)
        notScrolling()
        assertEquals(keyUp(vk, 1050), Pass)

        // Another key suppressed as it ended a released scroll: its up too
        keyDown(vk, 1100)
        keyUp(vk, 1150)
        assertEquals(keyDown(VK_A, 1200), Suppress)
        Context.initState
        assertEquals(keyUp(VK_A, 1300), Suppress)

        // A key passed keeps passing: its up passes after the reset
        assertEquals(keyDown(VK_A, 1400), Pass)
        Context.initState
        assertEquals(keyUp(VK_A, 1500), Pass)

        // The input lost (a lock, a suspend...): nothing is kept, the up passes
        keyDown(vk, 1600)
        Context.inputLost()
        assertEquals(keyUp(vk, 1700), Pass)
    }

    test("a repeat passed with no record passes its up too") {
        val vk = kb()

        // Suppressed in its scroll, reset (its up left to be suppressed alone), then down in the
        // system (its up missed, pressed again with the down reaching the application): its
        // repeat and its up pass
        assertEquals(keyDown(vk, 100), Suppress)
        Context.initState
        setKeyDown(vk, true)
        assertEquals(keyDown(vk, 500), Pass)
        assertEquals(keyUp(vk, 600), Pass)
    }

    test("the trigger key passes over an excluded window, its repeats and its up too") {
        val vk = kb()
        exclude()
        addExcludedPoint(100, 100)

        setCursor(100, 100)
        assertEquals(keyDown(vk, 10), Pass)
        // Moved out: its repeats still pass, no scroll starts
        setCursor(20, 20)
        assertEquals(keyDown(vk, 40), Pass)
        notScrolling()
        assertEquals(keyUp(vk, 100), Pass)

        // The next press, out of it, is the trigger
        assertEquals(keyDown(vk, 200), Suppress)
        scrolling()
        assertEquals(keyUp(vk, 600), Suppress)
        notScrolling()
    }

    test("a lost keyboard hook forgets the passed keys") {
        val vk = kb()
        exclude()
        addExcludedPoint(100, 100)

        // The trigger key passed over an excluded window, released with no hook: after the
        // recovery, its first press starts the scroll
        setCursor(100, 100)
        assertEquals(keyDown(vk, 10), Pass)
        Context.ForTests.keyboardHookLost()
        setCursor(20, 20)
        assertEquals(keyDown(vk, 500), Suppress)
        scrolling()

        // Passed over an excluded window and still held at the recovery (moved out meanwhile):
        // its repeats and its up pass, no scroll starts
        Context.initState
        keyUp(vk, 600)
        setCursor(100, 100)
        assertEquals(keyDown(vk, 700), Pass)
        setCursor(20, 20)
        setKeyDown(vk, true)
        Context.ForTests.keyboardHookLost()
        assertEquals(keyDown(vk, 730), Pass)
        notScrolling()
        assertEquals(keyUp(vk, 800), Pass)
        setKeyDown(vk, false)

        // The trigger key in its scroll, released and pressed again with no hook (the new down
        // reached the application), down at the recovery: the scroll is ended, and the repeats
        // and the up of that new press pass (not suppressed as the old one's). The next press is
        // the trigger.
        assertEquals(keyDown(vk, 900), Suppress)
        scrolling()
        setKeyDown(vk, true)
        Context.ForTests.keyboardHookLost()
        notScrolling()
        assertEquals(keyDown(vk, 930), Pass)
        notScrolling()
        assertEquals(keyUp(vk, 2000), Pass)
        setKeyDown(vk, false)
        assertEquals(keyDown(vk, 2100), Suppress)
        scrolling()
        keyUp(vk, 2200)

        // First pressed with no hook (no record of it), down at the recovery: its repeats and its
        // up pass, no scroll starts
        Context.initState
        setKeyDown(vk, true)
        Context.ForTests.keyboardHookLost()
        assertEquals(keyDown(vk, 3000), Pass)
        notScrolling()
        assertEquals(keyUp(vk, 3100), Pass)
    }

    test("the lost input forgets the keys") {
        val vk = kb()
        exclude()
        addExcludedPoint(100, 100)

        // The trigger key passed over an excluded window, released while the session was locked:
        // after the unlock, its first press starts the scroll
        setCursor(100, 100)
        assertEquals(keyDown(vk, 10), Pass)
        Context.inputLost()
        setCursor(20, 20)
        assertEquals(keyDown(vk, 500), Suppress)
        scrolling()
    }

    test("the lost input after a passed press keeps passing it") {
        val vk = kb()
        exclude()
        addExcludedPoint(100, 100)

        // After the unlock, the trigger key passed over an excluded window before the reset posted
        // at the unlock was handled (the hook's events come first): its repeats and its up pass,
        // no scroll starts. The next press is the trigger.
        setCursor(100, 100)
        assertEquals(keyDown(vk, 10), Pass)
        setKeyDown(vk, true)
        Context.inputLost()
        setCursor(20, 20)
        assertEquals(keyDown(vk, 500), Pass)
        notScrolling()
        assertEquals(keyDown(vk, 530), Pass)
        notScrolling()
        assertEquals(keyUp(vk, 600), Pass)
        setKeyDown(vk, false)
        assertEquals(keyDown(vk, 700), Suppress)
        scrolling()
        keyUp(vk, 800)

        // Another key passed before the reset was handled, then a released scroll: the key's
        // repeat does not end the scroll, and is not suppressed (its up would be too)
        Context.initState
        assertEquals(keyDown(VK_A, 900), Pass)
        setKeyDown(VK_A, true)
        Context.inputLost()
        keyDown(vk, 1000)
        keyUp(vk, 1050)
        scrolling()
        assertEquals(keyDown(VK_A, 1100), Pass)
        scrolling()
        assertEquals(keyUp(VK_A, 1200), Pass)
    }

    test("the installed keyboard hook passes the trigger key held") {
        val vk = kb()

        // Pressed while paused (no hook: its down reached the application), held at the end of
        // the pause: its repeats and its up pass, no scroll starts. The next press is the trigger.
        Context.ForTests.keyboardHookRemoved()
        setKeyDown(vk, true)
        Context.ForTests.keyboardHookInstalled()
        assertEquals(keyDown(vk, 30), Pass)
        notScrolling()
        assertEquals(keyUp(vk, 100), Pass)
        setKeyDown(vk, false)
        assertEquals(keyDown(vk, 200), Suppress)
        scrolling()
        keyUp(vk, 300)

        // Not held at the end of the pause: its first press is the trigger
        Context.initState
        Context.ForTests.keyboardHookRemoved()
        Context.ForTests.keyboardHookInstalled()
        assertEquals(keyDown(vk, 400), Suppress)
        scrolling()
    }

    test("the lost keyboard hook ends the key's scroll, its up passes") {
        val vk = kb()

        // Windows had removed the old hook: the key may have been released and pressed again
        // meanwhile, so its records are forgotten and its up passes
        keyDown(vk, 10)
        Context.ForTests.keyboardHookLost()
        notScrolling()
        assertEquals(keyUp(vk, 50), Pass)
        assertEquals(keyDown(vk, 90), Suppress)
        scrolling()
    }

    test("the lost mouse hook keeps the keys") {
        val vk = kb()
        assertEquals(keyDown(vk, 100), Suppress)
        Context.ForTests.mouseHookLost()
        scrolling()
        // Its up is still the trigger's: suppressed, it ends the scroll (past the locktime)
        assertEquals(keyUp(vk, 500), Suppress)
        notScrolling()
    }

    test("the trigger key changed while held: its repeats and its up are suppressed") {
        val vk = kb()
        assertEquals(keyDown(vk, 100), Suppress)
        scrolling()
        Context.ForTests.setTargetVKCode(DataID.VK_PAUSE)
        notScrolling()
        assertEquals(keyDown(vk, 130), Suppress)
        notScrolling()
        assertEquals(keyUp(vk, 500), Suppress)
        // A key as the others now
        assertEquals(keyDown(vk, 600), Pass)
        assertEquals(keyUp(vk, 650), Pass)
    }

    test("the Pause key sending its down and up at once: a released scroll, ended by the next press") {
        reset(DataID.Middle, DataID.VK_PAUSE)
        // A press of VK_PAUSE (its down and up at once, as a PS/2 keyboard sends them) starts a
        // released keyboard scroll; the next press ends it
        assertEquals(keyDown(VK_PAUSE, 100), Suppress)
        assertEquals(keyUp(VK_PAUSE, 100), Suppress)
        scrolling()
        assertEquals(keyDown(VK_PAUSE, 400), Suppress)
        notScrolling()
        assertEquals(keyUp(VK_PAUSE, 400), Suppress)
        notScrolling()

        // Passed over an excluded window: its up too
        exclude()
        addExcludedPoint(100, 100)
        setCursor(100, 100)
        assertEquals(keyDown(VK_PAUSE, 500), Pass)
        notScrolling()
        assertEquals(keyUp(VK_PAUSE, 550), Pass)
        setCursor(20, 20)
        assertEquals(keyDown(VK_PAUSE, 600), Suppress)
        scrolling()
    }

    test("a key passed, made the trigger while held: its repeats and its up pass") {
        kb()
        assertEquals(keyDown(VK_TAB, 100), Pass)
        Context.ForTests.setTargetVKCode(DataID.VK_TAB)
        assertEquals(keyDown(VK_TAB, 130), Pass)
        notScrolling()
        assertEquals(keyUp(VK_TAB, 500), Pass)
        // Its next press is the trigger
        assertEquals(keyDown(VK_TAB, 600), Suppress)
        scrolling()
    }

    test("the Pause key repeats as the other keys") {
        reset(DataID.Middle, DataID.VK_PAUSE)
        exclude()
        addExcludedPoint(100, 100)

        // Held (a USB keyboard repeats it): its repeats are suppressed and the scroll goes on (not
        // ended and started again at each repeat, the cursor flickering); its up ends it
        assertEquals(keyDown(VK_PAUSE, 100), Suppress)
        for (time <- List(600, 633, 666, 700)) {
            assertEquals(keyDown(VK_PAUSE, time), Suppress)
            scrolling()
        }
        assertEquals(keyUp(VK_PAUSE, 1000), Suppress)
        notScrolling()

        // Passed over an excluded window: its repeats pass after moving out, no scroll starts
        setCursor(100, 100)
        assertEquals(keyDown(VK_PAUSE, 2000), Pass)
        setCursor(20, 20)
        assertEquals(keyDown(VK_PAUSE, 2500), Pass)
        notScrolling()
        assertEquals(keyUp(VK_PAUSE, 2600), Pass)

        // Held when the keyboard hook is installed: its repeats and its up pass
        setKeyDown(VK_PAUSE, true)
        Context.ForTests.keyboardHookRemoved()
        Context.ForTests.keyboardHookInstalled()
        assertEquals(keyDown(VK_PAUSE, 3000), Pass)
        notScrolling()
        assertEquals(keyUp(VK_PAUSE, 3100), Pass)
        setKeyDown(VK_PAUSE, false)
        assertEquals(keyDown(VK_PAUSE, 3200), Suppress)
        scrolling()
    }

    test("the trigger key whose scroll cannot start passes its down, repeats and up") {
        val vk = kb()
        rawInputFails = true
        try {
            assertEquals(keyDown(vk, 100), Pass)
            notScrolling()
            assertEquals(keyDown(vk, 130), Pass)
            assertEquals(keyUp(vk, 500), Pass)
        }
        finally {
            rawInputFails = false
        }
        // Its next down is a press of its own: the trigger
        assertEquals(keyDown(vk, 600), Suppress)
        scrolling()
    }

    // The trigger key in its scroll, ended by a mouse trigger, its up missed, and pressed again
    // with its down missed too (e.g. over a window of a higher integrity level): down in the
    // system, its repeat passes with no record of the down
    private def passedWithNoRecord(vk: Int): Unit = {
        assertEquals(keyDown(vk, 100), Suppress)
        down(Middle, 0, 0, 200)
        up(Middle, 0, 0, 250)
        notScrolling()
        setKeyDown(vk, true)
        assertEquals(keyDown(vk, 600), Pass)
    }

    test("the trigger key's next press after a passed press with no record is the trigger") {
        val vk = kb()
        passedWithNoRecord(vk)
        assertEquals(keyUp(vk, 700), Pass)
        setKeyDown(vk, false)
        // Its next press is the trigger, not taken for a repeat of the old one
        assertEquals(keyDown(vk, 800), Suppress)
        scrolling()
    }

    test("a reset while a press passed with no record is held: its up passes") {
        val vk = kb()
        passedWithNoRecord(vk)
        Context.initState
        // Released before its next repeat: its up as its down
        assertEquals(keyUp(vk, 700), Pass)
        setKeyDown(vk, false)
        assertEquals(keyDown(vk, 800), Suppress)
        scrolling()
    }

    test("the trigger changed while a press passed with no record is held: its repeats and up pass") {
        val vk = kb()
        passedWithNoRecord(vk)
        Context.ForTests.setTargetVKCode(DataID.VK_TAB)
        assertEquals(keyDown(vk, 630), Pass)
        assertEquals(keyUp(vk, 700), Pass)
        setKeyDown(vk, false)
    }
}
