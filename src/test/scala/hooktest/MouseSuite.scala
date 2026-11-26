package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

// The mouse triggers (EventHandler, EventWaiter, the checks of Windows), as W10Wheel.NET tests them
// (W10Wheel.Tests, MouseTests.fs), ported from the Rust version (w10wheel.rs, trigger.rs). The
// code as it is. The LR trigger's wait runs on its own thread (EventWaiter): its scroll starts, and
// its timeout resends, some time after the hook returns.

import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.WinUser.INPUT

import Harness._
import Harness.Button._
import Harness.Decision._

class MouseSuite extends munit.FunSuite {
    private def notScrolling(): Unit = assert(!isScrolling, "not scrolling")

    // Waits up to 1 s for cond
    private def waitUntil(cond: => Boolean): Boolean = {
        val deadline = System.nanoTime + 1000000000L
        while (!cond && System.nanoTime < deadline)
            Thread.sleep(5)
        cond
    }

    // The LR trigger's scroll starts on the waiter thread
    private def scrolling(): Unit = assert(waitUntil(isScrolling), "scrolling")

    // The sent inputs within 1 s, count of them
    private def sentInputs(count: Int): List[INPUT] = takeSent(count, 1000)

    private def buttonsOf(inputs: List[INPUT]): List[(Button, Boolean)] = inputs.flatMap(buttonOfInput)

    private def isClick(inputs: List[INPUT], b: Button): Boolean =
        buttonsOf(inputs) == List((b, true), (b, false))

    test("a single or drag trigger pressed again with its up missed ends the scroll") {
        // The second down ends the scroll, suppressed with its up; the next press is the trigger
        // again
        for ((trigger, b) <- List((DataID.Middle, Middle), (DataID.RightDrag, Right))) {
            reset(trigger, DataID.None)
            assertEquals(down(b, 0, 0, 100), Suppress)
            scrolling()
            assertEquals(down(b, 0, 0, 300), Suppress)
            notScrolling()
            assertEquals(up(b, 0, 0, 400), Suppress)
            assert(noneSent(50))
            assertEquals(down(b, 0, 0, 500), Suppress)
            scrolling()
        }
    }

    test("a single or drag trigger whose scroll cannot start passes its down and up") {
        // RawInput refused: the button works as if it were not the trigger, its click not lost
        for ((trigger, b) <- List((DataID.Middle, Middle), (DataID.RightDrag, Right))) {
            reset(trigger, DataID.None)
            rawInputFails = true
            try {
                assertEquals(down(b, 10, 10, 100), Pass)
                notScrolling()
                assertEquals(up(b, 10, 10, 150), Pass)
                assert(noneSent(50))
            }
            finally {
                rawInputFails = false
            }
            // RawInput registered again: the trigger
            assertEquals(down(b, 10, 10, 200), Suppress)
            scrolling()
        }
    }

    test("a button of the LR pair pressed again with its up missed ends the scroll") {
        reset(DataID.LR, DataID.None)
        assertEquals(down(Left, 0, 0, 100), Suppress)
        assertEquals(down(Right, 0, 0, 150), Suppress)
        scrolling()
        assertEquals(down(Left, 0, 0, 300), Suppress)
        notScrolling()
        // The other button's up is still suppressed
        assertEquals(up(Left, 0, 0, 350), Suppress)
        assertEquals(up(Right, 0, 0, 400), Suppress)
        assert(noneSent(50))
        // The next press waits for its pair again
        assertEquals(down(Left, 0, 0, 500), Suppress)
        assertEquals(down(Right, 0, 0, 520), Suppress)
        scrolling()
    }

    test("the LR pair ends or releases at its second up by the locktime") {
        reset(DataID.LR, DataID.None)

        // The first up keeps the scroll; the second, later than the locktime after the start,
        // ends it
        down(Left, 0, 0, 100)
        down(Right, 0, 0, 150)
        scrolling()
        assertEquals(up(Left, 0, 0, 200), Suppress)
        scrolling()
        assertEquals(up(Right, 0, 0, 500), Suppress)
        notScrolling()

        // Within the locktime: released, the next down ends it
        down(Left, 0, 0, 1000)
        down(Right, 0, 0, 1050)
        scrolling()
        up(Right, 0, 0, 1100)
        up(Left, 0, 0, 1200)
        scrolling()
        assertEquals(down(Middle, 0, 0, 1500), Suppress)
        notScrolling()
        assertEquals(up(Middle, 0, 0, 1550), Suppress)

        // A button other than the pair's up does not count as the pair's: the ups of the pair,
        // later than the locktime, are the first (the scroll goes on) and the second (it ends)
        down(Left, 0, 0, 2000)
        down(Right, 0, 0, 2050)
        scrolling()
        assertEquals(down(X1, 0, 0, 2060), Pass)
        assertEquals(up(X1, 0, 0, 2070), Pass)
        up(Left, 0, 0, 2400)
        scrolling()
        up(Right, 0, 0, 2450)
        notScrolling()
    }

    test("the LR wait timed out resends its down, and the up for it") {
        reset(DataID.LR, DataID.None)
        assertEquals(down(Left, 50, 60, 100), Suppress)
        val downs = sentInputs(1)
        assertEquals(buttonsOf(downs), List((Left, true)))

        // The physical up before the resent down reaches the hook: the up is resent (and the
        // physical one suppressed), both pass the hook after
        assertEquals(up(Left, 50, 60, 310), Suppress)
        val ups = sentInputs(1)
        assertEquals(buttonsOf(ups), List((Left, false)))
        assertEquals(receive(downs.head), Pass)
        assertEquals(receive(ups.head), Pass)
        notScrolling()
    }

    test("a waiting down resent by a move passes, and the up for it") {
        reset(DataID.LR, DataID.None)
        down(Left, 10, 10, 100)
        assertEquals(move(12, 10, 120), Suppress)
        val downs = sentInputs(1)
        assertEquals(buttonsOf(downs), List((Left, true)))
        assertEquals(receive(downs.head), Pass)
        // Its up is resent for it
        assertEquals(up(Left, 12, 10, 200), Suppress)
        val ups = sentInputs(1)
        assertEquals(buttonsOf(ups), List((Left, false)))
        assertEquals(receive(ups.head), Pass)
        notScrolling()
    }

    test("a move at the point of the waiting down does not end the wait") {
        reset(DataID.LR, DataID.None)
        down(Left, 10, 10, 100)
        assertEquals(move(10, 10, 110), Pass)
        assertEquals(down(Right, 10, 10, 150), Suppress)
        scrolling()
    }

    test("the waiting down released at its point: a click resent") {
        reset(DataID.LR, DataID.None)
        down(Left, 10, 10, 100)
        assertEquals(up(Left, 10, 10, 150), Suppress)
        assert(isClick(sentInputs(2), Left))
        notScrolling()
    }

    test("a reset ends the LR wait, and the lost input forgets its press") {
        // initState: the wait is ended (its down not resent), and the up of that press, whose
        // down reached no application, is suppressed alone
        reset(DataID.LR, DataID.None)
        down(Left, 10, 10, 100)
        Context.initState
        assert(noneSent(400))
        assertEquals(up(Left, 10, 10, 200), Suppress)

        // inputLost: the press is forgotten too (its up may have been missed, and the up of a
        // press made meanwhile reached the application with its down): the up passes
        reset(DataID.LR, DataID.None)
        down(Left, 10, 10, 100)
        Context.inputLost()
        assert(noneSent(400))
        assertEquals(up(Left, 10, 10, 200), Pass)
    }

    test("the keyboard hook's changes keep the mouse triggers") {
        reset(DataID.LR, DataID.VK_LMENU)

        // The wait of the LR trigger goes on: the other button makes the pair
        down(Left, 10, 10, 100)
        Context.ForTests.keyboardHookLost()
        Context.ForTests.keyboardHookRemoved()
        assertEquals(down(Right, 10, 10, 150), Suppress)
        scrolling()

        // The mouse scroll goes on
        Context.ForTests.keyboardHookLost()
        Context.ForTests.keyboardHookRemoved()
        assert(isScrolling)
        assert(noneSent(50))
    }

    test("the lost mouse hook ends the mouse scroll, the trigger's up passes") {
        reset(DataID.RightDrag, DataID.None)
        down(Right, 10, 10, 100)
        scrolling()
        Context.ForTests.mouseHookLost()
        notScrolling()
        assertEquals(up(Right, 10, 10, 800), Pass)
        assert(noneSent(50))
    }

    test("the lost input ends a drag scroll, its up passes") {
        // A drag scroll whose trigger was released and pressed again while locked: ended, and the
        // up after the unlock passes
        reset(DataID.RightDrag, DataID.None)
        down(Right, 10, 10, 100)
        scrolling()
        Context.inputLost()
        notScrolling()
        assertEquals(up(Right, 10, 10, 800), Pass)
    }

    test("a physical up ends what the triggers kept of its press") {
        // LR: a released scroll ended by Right's down (its up the trigger's), Left waits, then
        // Right's up comes: dropped as a lone up, the wait goes on, and nothing of Right's press
        // is kept after its up
        reset(DataID.LR, DataID.None)
        down(Left, 0, 0, 100)
        down(Right, 0, 0, 150)
        scrolling()
        up(Left, 0, 0, 200)
        up(Right, 0, 0, 250)
        scrolling()
        assertEquals(down(Right, 0, 0, 1000), Suppress)
        notScrolling()
        down(Left, 0, 0, 1050)
        assertEquals(up(Right, 0, 0, 1100), Suppress)
        assert(takeSent(0, 0).isEmpty)
        assert(!Context.LastFlags.isSuppressedDown(RightUp(mouseInfo(0, 0, 0, 0, 0, 0))))
        // The wait goes on: Right again makes the pair
        assertEquals(down(Right, 0, 0, 1150), Suppress)
        scrolling()
    }

    test("a new press forgets a resent down whose up was missed") {
        // LR: the wait of Left timed out (resent), its up missed, another button between; Left
        // pressed again over an excluded window (passed), then Right waits: Left's up passes as
        // that of the passed press, not resent as the up of the old resent down
        reset(DataID.LR, DataID.None)
        exclude()
        addExcludedPoint(100, 100)
        down(Left, 10, 10, 100)
        val downs = sentInputs(1)
        assertEquals(receive(downs.head), Pass)
        assertEquals(down(Middle, 10, 10, 400), Pass)
        assertEquals(up(Middle, 10, 10, 450), Pass)
        assertEquals(down(Left, 100, 100, 500), Pass)
        assertEquals(down(Right, 20, 20, 600), Suppress)
        assertEquals(up(Left, 20, 20, 650), Pass)
        assert(takeSent(0, 0).isEmpty)
    }

    test("the drag trigger: a click is resent unless it was dragged") {
        // Released at the point of its down: the scroll ends and the click is resent
        reset(DataID.RightDrag, DataID.None)
        Context.ForTests.setDraggedLock(true)
        assertEquals(down(Right, 10, 10, 100), Suppress)
        scrolling()
        assertEquals(up(Right, 10, 10, 150), Suppress)
        notScrolling()
        assert(isClick(sentInputs(2), Right))

        // Moved: dragged, released (draggedLock), no click
        assertEquals(down(Right, 10, 10, 200), Suppress)
        assertEquals(move(11, 10, 210), Suppress)
        assertEquals(up(Right, 11, 10, 250), Suppress)
        scrolling()
        assert(noneSent(50))

        // Released, the moves are suppressed and the next down ends it, with no click
        assertEquals(move(40, 40, 300), Suppress)
        assertEquals(down(Right, 40, 40, 400), Suppress)
        notScrolling()
        assertEquals(up(Right, 40, 40, 450), Suppress)
        assert(noneSent(50))
    }

    test("the drag trigger takes a scroll or a real move as dragged") {
        // Scrolled with no move seen by the hook: dragged, released (draggedLock)
        reset(DataID.RightDrag, DataID.None)
        Context.ForTests.setDraggedLock(true)
        down(Right, 10, 10, 100)
        scrolling()
        Context.setScrolled()
        assertEquals(up(Right, 10, 10, 200), Suppress)
        scrolling()
        assert(noneSent(50))

        // Dragged with draggedLock off: its up ends the scroll, with no click
        reset(DataID.RightDrag, DataID.None)
        down(Right, 10, 10, 100)
        assertEquals(move(12, 10, 120), Suppress)
        assertEquals(up(Right, 12, 10, 200), Suppress)
        notScrolling()
        assert(noneSent(50))

        // A move to the same point and an injected move are not a drag: the up ends the scroll
        // and the click is resent
        reset(DataID.RightDrag, DataID.None)
        Context.ForTests.setDraggedLock(true)
        down(Right, 10, 10, 1000)
        assertEquals(move(10, 10, 1010), Suppress)
        assertEquals(moveInjected(30, 30, 1020), Suppress)
        assertEquals(up(Right, 10, 10, 1100), Suppress)
        notScrolling()
        assert(isClick(sentInputs(2), Right))
    }

    test("the Left trigger passes the right button alone") {
        reset(DataID.Left, DataID.None)
        assertEquals(down(Right, 10, 10, 100), Pass)
        assertEquals(up(Right, 10, 10, 110), Pass)
        notScrolling()
        assertEquals(down(Left, 10, 10, 200), Suppress)
        assertEquals(down(Right, 10, 10, 220), Suppress)
        scrolling()
    }

    test("the trigger changed while the LR pair holds its scroll: the pair's ups are suppressed") {
        reset(DataID.LR, DataID.None)
        down(Left, 0, 0, 100)
        down(Right, 0, 0, 150)
        scrolling()
        Context.ForTests.setTrigger(DataID.Middle)
        notScrolling()
        assertEquals(up(Left, 0, 0, 200), Suppress)
        assertEquals(up(Right, 0, 0, 250), Suppress)
        assert(noneSent(50))
        // The buttons as the new trigger takes them
        assertEquals(down(Left, 0, 0, 300), Pass)
        assertEquals(up(Left, 0, 0, 350), Pass)
    }

    test("the trigger changed after the LR wait resent its down: its up is resent") {
        reset(DataID.LR, DataID.None)
        down(Left, 10, 10, 100)
        val downs = sentInputs(1)
        assertEquals(receive(downs.head), Pass)
        Context.ForTests.setTrigger(DataID.Middle)
        assertEquals(up(Left, 10, 10, 400), Suppress)
        val ups = sentInputs(1)
        assertEquals(buttonsOf(ups), List((Left, false)))
        assertEquals(receive(ups.head), Pass)
    }

    test("the trigger changed while the LR wait is pending: the wait is canceled, the up suppressed") {
        reset(DataID.LR, DataID.None)
        down(Left, 10, 10, 100)
        Context.ForTests.setTrigger(DataID.Middle)
        assert(noneSent(400))
        assertEquals(up(Left, 10, 10, 500), Suppress)
    }

    test("a down resent for a press forgotten is dropped, one of an unknown kind passes") {
        reset(DataID.LR, DataID.None)
        down(Left, 10, 10, 100)
        val d = sentInputs(1).head
        // The press forgotten (e.g. the session locked) before the resent down reaches the hook:
        // the down would leave the button pressed
        Context.inputLost()
        assertEquals(receive(d), Suppress)

        // The kind bits of none (3): not W10Wheel's, passed as the input of other software
        val mi = d.input.mi
        mi.dwExtraInfo = new ULONG_PTR(mi.dwExtraInfo.longValue | (3L << 56))
        assertEquals(receive(d), Pass)
    }

    test("Pass Mode turned on ends the LR wait, the key's up is suppressed alone") {
        reset(DataID.LR, DataID.VK_LMENU)
        down(Left, 10, 10, 100)
        assertEquals(keyDown(VK_LMENU, 110), Suppress)
        Context.ForTests.passModeOn()
        // The wait's down is not resent
        assert(noneSent(400))
        // The up of the key suppressed in its scroll is suppressed alone, as its down was
        assertEquals(keyUp(VK_LMENU, 600), Suppress)
        assertEquals(keyUp(VK_LMENU, 650), Pass)
        Context.exitScrollMode
    }
}
