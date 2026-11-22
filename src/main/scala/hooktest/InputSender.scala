package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit }
import java.util.concurrent.locks.ReentrantLock

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinUser.INPUT

// Sends the inputs made by W10Wheel (wheel events, resent clicks, downs and ups) on its own
// thread, one SendInput call per input, in the order they are queued.
// Ported from Windows.fs of W10Wheel.NET (2.8.1 - 2.8.4).
object InputSender {
    private val logger = Logger.getLogger()
    private val u32 = User32.INSTANCE

    // What a queued input is for: decides whether and how it is sent (see sendNow)
    sealed trait Kind
    // A wheel event: not sent in Pass Mode, nor if Pass Mode has been turned on (or the state
    // initialized) since it was queued: W10Wheel does not scroll then
    case object Wheel extends Kind
    // A click, or a down and up pair of the same button: the down first, the rest after
    // BATCH_INPUT_INTERVAL_MS. Sent even if Pass Mode has been turned on since it was queued: it
    // cannot leave the button pressed, and the user clicked before.
    case object Click extends Kind
    // A down resent alone (its up comes later), for a press of the left (0) or right (1) button.
    // Sent even if Pass Mode has been turned on (or the state initialized) since it was queued:
    // the hook drops it if its physical up has passed, and lets it pass if its up has been resent
    // (Windows.dropResent), so that a press whose up is queued already is not lost
    case class Down(button: Int, press: Int) extends Kind
    // The up for that down: sent only if that down was sent, or it would be an up without a down
    // (e.g. a right up opens a context menu)
    case class Release(button: Int, press: Int) extends Kind
    // An up whose down reached the application by itself (e.g. the other button's up)
    case object Up extends Kind

    private case class Batch(generation: Int, kind: Kind, inputs: Array[INPUT])

    // Interval between the inputs of one batch (the down and up of a click). If they are sent in
    // a single SendInput call, they arrive at the same moment, and tools that remap injected
    // input (e.g. X-Mouse Button Control) may miss the up, leaving the remapped button pressed.
    private val BATCH_INPUT_INTERVAL_MS = 10L

    // While ups are unsent, they are tried again at this interval even if nothing is queued
    // (e.g. SendInput fails while the secure desktop is shown)
    private val UNSENT_RETRY_INTERVAL_MS = 100L

    private val queue = new LinkedBlockingQueue[Batch]()

    // Held while a batch is being sent, so that close can wait for it at exit
    private val sendLock = new ReentrantLock()

    // Set at exit (beginClose): nothing is sent any more but the ups, so that no button is left
    // pressed by a down sent before
    @volatile private var closing = false

    // The ups that could not be sent even with retries, in order. They are tried again before
    // the next batch, periodically while nothing is queued and at exit, so that no button is
    // left pressed. Written under sendLock.
    @volatile private var unsent: Vector[INPUT] = Vector.empty

    // The physical presses of the left and right buttons (the IDs given by the hook, see
    // Windows.pressIdOf): a down resent alone and its up are matched by them. Under sendLock:
    private val sentPress = Array(0, 0)      // the down resent alone that was sent and not released (0: none)
    private val handledPress = Array(0, 0)   // the last down resent alone that was sent or dropped
    private val earlyRelease: Array[Option[Batch]] = Array(None, None) // an up queued before its down was handled

    def enqueue(kind: Kind, inputs: Array[INPUT]): Unit =
        enqueue(Context.getPassModeGeneration, kind, inputs)

    // generation: the Pass Mode generation to check a wheel event or a down against
    def enqueue(generation: Int, kind: Kind, inputs: Array[INPUT]): Unit =
        queue.add(Batch(generation, kind, inputs))

    private def sendOne(input: INPUT): Boolean =
        u32.SendInput(new DWORD(1), Array(input), input.size()).intValue() == 1

    private def sendWithRetry(input: INPUT): Boolean = {
        def loop(n: Int): Boolean = {
            if (sendOne(input))
                true
            else if (n > 0) {
                Thread.sleep(BATCH_INPUT_INTERVAL_MS)
                loop(n - 1)
            }
            else {
                logger.warn("SendInput failed")
                false
            }
        }
        loop(3)
    }

    // Sends the inputs in order (with retries if withRetry); returns the ones that could not be sent
    private def sendInOrder(withRetry: Boolean, inputs: Seq[INPUT]): Vector[INPUT] = {
        val send: INPUT => Boolean = if (withRetry) sendWithRetry else sendOne

        def loop(i: Int): Vector[INPUT] = {
            if (i >= inputs.length)
                Vector.empty
            else {
                if (i > 0) Thread.sleep(BATCH_INPUT_INTERVAL_MS)
                if (send(inputs(i))) loop(i + 1) else inputs.drop(i).toVector
            }
        }
        loop(0)
    }

    private def retryUnsent(withRetry: Boolean): Unit = {
        if (unsent.nonEmpty)
            unsent = sendInOrder(withRetry, unsent)
    }

    // Sends ups after the unsent ones (in order, with retries); the rest stays unsent
    private def sendUps(ups: Seq[INPUT]): Unit = {
        unsent = unsent ++ ups
        retryUnsent(withRetry = true)
    }

    private def isPassModeSince(generation: Int): Boolean =
        Context.isPassMode || Context.getPassModeGeneration != generation

    private def sendRelease(button: Int, b: Batch): Unit = {
        sentPress(button) = 0
        sendUps(b.inputs.toSeq)
    }

    private def sendDown(b: Batch, button: Int, press: Int): Unit = {
        handledPress(button) = press

        val sent =
            if (unsent.nonEmpty) {
                // Not before unsent ups: they would be sent after it, releasing it
                logger.debug("InputSender: dropped a down (unsent ups)")
                false
            }
            // (not checked against Pass Mode: see Down)
            else if (sendOne(b.inputs(0)))
                true
            else {
                logger.warn("InputSender: SendInput failed (down)")
                false
            }

        sentPress(button) = if (sent) press else 0

        // The up of this press was queued before this down was (see sendNow): send it now, or
        // drop it with its down. An earlier press's up is dropped: its down was handled before.
        val early = earlyRelease(button)
        earlyRelease(button) = None
        early.foreach(r => r.kind match {
            case Release(_, p) if p == press && sent =>
                Thread.sleep(BATCH_INPUT_INTERVAL_MS)
                sendRelease(button, r)
            case _ =>
                logger.debug("InputSender: dropped an up (its down was not sent)")
        })
    }

    private def sendNow(b: Batch): Unit = {
        // Without retries here: while SendInput keeps failing, retrying before every batch
        // (wheel events while scrolling) would make the queue fall behind
        retryUnsent(withRetry = false)

        b.kind match {
            case Wheel =>
                if (isPassModeSince(b.generation))
                    logger.debug("InputSender: dropped a wheel event (Pass Mode)")
                else
                    sendOne(b.inputs(0))
            case Up =>
                sendUps(b.inputs.toSeq)
            case Down(button, press) =>
                sendDown(b, button, press)
            case Release(button, press) =>
                if (press != 0 && sentPress(button) == press)
                    sendRelease(button, b)
                else if (press != 0 && press != handledPress(button))
                    // Its down is still queued (the up came first): wait for it. (If it never
                    // comes, the up is dropped when the next down of the button is handled.)
                    earlyRelease(button) = Some(b)
                else
                    logger.debug("InputSender: dropped an up (its down was not sent)")
            case Click =>
                if (unsent.nonEmpty) {
                    // Ups are still not sent: do not press another button, it could not be
                    // released either, and the old ups would be sent after the new click
                    logger.debug("InputSender: dropped a click (unsent ups)")
                }
                else if (sendOne(b.inputs(0))) {
                    // The down was sent: the rest (the up) must be sent, or the button stays pressed
                    Thread.sleep(BATCH_INPUT_INTERVAL_MS)
                    unsent = unsent ++ sendInOrder(withRetry = true, b.inputs.toSeq.drop(1))
                }
                else
                    logger.warn("InputSender: SendInput failed (click)")
        }
    }

    // At exit only the ups for downs sent before are sent: the ups to send (empty if b is not one).
    // The hooks are removed (or about to be), and nothing drops the up of a down resent alone that
    // was not sent: it is sent only if that down was, or it would be an up without a down.
    private def upsAtExit(b: Batch): Seq[INPUT] = b.kind match {
        case Up => b.inputs.toSeq
        case Release(button, press) if press != 0 && sentPress(button) == press =>
            sentPress(button) = 0
            b.inputs.toSeq
        case _ =>
            logger.debug("InputSender: dropped (closing)")
            Seq.empty
    }

    private def send(b: Batch): Unit = {
        sendLock.lock()
        try {
            if (closing) {
                val ups = upsAtExit(b)
                if (ups.nonEmpty) sendUps(ups)
            }
            else
                sendNow(b)
        }
        finally {
            sendLock.unlock()
        }
    }

    private def retryUnsentIdle(): Unit = {
        sendLock.lock()
        try {
            if (!closing) retryUnsent(withRetry = false)
        }
        finally {
            sendLock.unlock()
        }
    }

    // Called at exit before the hooks are removed: nothing is sent any more but the ups. A down
    // resent after the hooks are removed would not be released if its physical up has come.
    def beginClose(): Unit = {
        closing = true
    }

    // Called at exit after the hooks are removed: waits for a batch being sent (its up
    // included), sends the ups still queued (for downs sent before) and tries again the ups that
    // could not be sent. The other inputs queued are dropped.
    def close(): Unit = {
        closing = true
        if (sendLock.tryLock(1, TimeUnit.SECONDS)) {
            try {
                // All the ups queued after the unsent ones, then tried once (in order, with retries)
                var b = queue.poll()
                while (b != null) {
                    unsent = unsent ++ upsAtExit(b)
                    b = queue.poll()
                }
                retryUnsent(withRetry = true)
            }
            finally {
                sendLock.unlock()
            }
        }
        else
            logger.warn("InputSender.close: timeout")
    }

    private val senderThread = new Thread(() =>
        while (true) {
            try {
                val retry = !closing && unsent.nonEmpty
                val b = if (retry) queue.poll(UNSENT_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS) else queue.take()

                if (b != null) send(b) else retryUnsentIdle()
            }
            catch {
                case e: Exception => logger.warn(s"InputSender: $e")
            }
        }
    )
    senderThread.setDaemon(true)
    senderThread.start()
}
