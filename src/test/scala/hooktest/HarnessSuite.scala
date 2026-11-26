package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

// The harness itself (Harness): the fake desktop that the handlers see through Host, the inputs
// they send and what the application gets. The tests of the triggers are in suites of their own.

import Harness._
import Harness.Button._
import Harness.Decision._

class HarnessSuite extends munit.FunSuite {
    // Waits up to 1 s for cond (the LR trigger's scroll starts on the waiter thread)
    private def waitUntil(cond: => Boolean): Boolean = {
        val deadline = System.nanoTime + 1000000000L
        while (!cond && System.nanoTime < deadline)
            Thread.sleep(5)
        cond
    }

    private def sentButtons(count: Int): List[(Button, Boolean)] =
        takeSent(count, 1000).map(i => buttonOfInput(i).get)

    test("other keys pass, the trigger key starts and ends the scroll") {
        reset(DataID.Middle, DataID.VK_LMENU)
        assertEquals(keyDown(VK_A, 10), Pass)
        assertEquals(keyUp(VK_A, 20), Pass)
        assert(!isScrolling)

        assertEquals(keyDown(VK_LMENU, 100), Suppress)
        assert(isScrolling)
        // Held longer than the locktime: its up ends the scroll
        assertEquals(keyUp(VK_LMENU, 500), Suppress)
        assert(!isScrolling)
    }

    test("the drag trigger's click is resent, and passes the hook back") {
        reset(DataID.RightDrag, DataID.None)
        assertEquals(down(Right, 10, 10, 100), Suppress)
        assert(isScrolling)
        assertEquals(up(Right, 10, 10, 150), Suppress)
        assert(!isScrolling)

        val inputs = takeSent(2, 1000)
        assertEquals(inputs.map(i => buttonOfInput(i).get), List((Right, true), (Right, false)))
        inputs.foreach(i => assertEquals(receive(i), Pass))
        // The physical events were suppressed: the application got the click resent only
        assertEquals(takeAppLog(), List((Right, true), (Right, false)))
    }

    test("the LR trigger's wait resends its down at the timeout, its up at the physical up") {
        reset(DataID.LR, DataID.None)
        assertEquals(down(Left, 10, 10, 100), Suppress)
        val downs = takeSent(1, 1000)
        assertEquals(downs.map(i => buttonOfInput(i).get), List((Left, true)))
        assertEquals(receive(downs.head), Pass)

        assertEquals(up(Left, 10, 10, 400), Suppress)
        val ups = takeSent(1, 1000)
        assertEquals(ups.map(i => buttonOfInput(i).get), List((Left, false)))
        assertEquals(receive(ups.head), Pass)
        assert(!isScrolling)
    }

    test("the LR pair starts the scroll on the waiter thread") {
        reset(DataID.LR, DataID.None)
        assertEquals(down(Left, 0, 0, 100), Suppress)
        assertEquals(down(Right, 0, 0, 150), Suppress)
        assert(waitUntil(isScrolling))
        assertEquals(up(Left, 0, 0, 200), Suppress)
        assertEquals(up(Right, 0, 0, 400), Suppress)
        assert(!isScrolling)
        assert(noneSent(50))
    }

    test("a trigger over the window of an excluded program passes") {
        reset(DataID.Middle, DataID.VK_LMENU)
        exclude()
        addExcludedPoint(100, 100)
        assertEquals(down(Middle, 100, 100, 100), Pass)
        assert(!isScrolling)
        assertEquals(up(Middle, 100, 100, 150), Pass)
        assertEquals(takeAppLog(), List((Middle, true), (Middle, false)))

        // The keyboard trigger looks at the window under the cursor
        setCursor(100, 100)
        assertEquals(keyDown(VK_LMENU, 200), Pass)
        assertEquals(keyUp(VK_LMENU, 250), Pass)
        assert(!isScrolling)
    }

    test("a trigger whose scroll cannot start (RawInput) passes") {
        reset(DataID.Middle, DataID.None)
        rawInputFails = true
        assertEquals(down(Middle, 10, 10, 100), Pass)
        assert(!isScrolling)
        assertEquals(up(Middle, 10, 10, 150), Pass)
        rawInputFails = false
        assertEquals(down(Middle, 10, 10, 200), Suppress)
        assert(isScrolling)
    }

    test("the sender held sends nothing until it is let go") {
        reset(DataID.RightDrag, DataID.None)
        holdSender()
        down(Right, 10, 10, 100)
        up(Right, 10, 10, 150)
        assert(noneSent(100))
        releaseSender()
        assertEquals(sentButtons(2), List((Right, true), (Right, false)))
    }

    test("a key down in the system is told by GetAsyncKeyState") {
        reset(DataID.Middle, DataID.VK_LMENU)
        // The trigger key already down with no record of its down: a repeat of a press that passed
        setKeyDown(VK_LMENU, true)
        assertEquals(keyDown(VK_LMENU, 100), Pass)
        assert(!isScrolling)
        setKeyDown(VK_LMENU, false)
        assertEquals(keyUp(VK_LMENU, 150), Pass)
    }
}
