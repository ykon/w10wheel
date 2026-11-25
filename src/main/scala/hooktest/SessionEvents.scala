package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.util.concurrent.{ Executors, TimeUnit }

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.{ WinUser, Wtsapi32 }

import win32ex.User32Ex

// The suspend and resume (WM_POWERBROADCAST) and the lock and unlock (WM_WTSSESSION_CHANGE), sent
// to the message window (Windows.createMessageWindow: a top-level window, they are not sent to a
// message-only one). What they do is posted (HookThread.post), and done from the message loop, not
// in the middle of an event being handled.
// The hooks see none of the input while the session is locked (it goes to the secure desktop) or
// the system asleep: the state is reset at each of them (Context.inputLost). After an unlock or a
// resume, the hooks are installed again (scheduleReinstall). All on the hook thread.
object SessionEvents {
    private val logger = Logger.getLogger()
    private val u32ex = User32Ex.INSTANCE
    private val wts = Wtsapi32.INSTANCE

    // WM_POWERBROADCAST (wParam)
    private val PBT_APMSUSPEND = 0x0004
    private val PBT_APMRESUMESUSPEND = 0x0007
    private val PBT_APMRESUMEAUTOMATIC = 0x0012
    // RegisterSuspendResumeNotification (flags)
    private val DEVICE_NOTIFY_WINDOW_HANDLE = 0

    // The delays of the tasks: a thread of its own, which only posts them to the hook thread
    private val timer = Executors.newSingleThreadScheduledExecutor(r => {
        val t = new Thread(r, "W10Wheel session events")
        t.setDaemon(true)
        t
    })

    // Dropped after stop (the timer is shut down)
    private def later(ms: Long)(task: () => Unit): Unit = {
        try timer.schedule((() => HookThread.post(task)): Runnable, ms, TimeUnit.MILLISECONDS)
        catch { case _: java.util.concurrent.RejectedExecutionException => logger.debug("SessionEvents: stopped") }
    }

    // After a resume or an unlock, the hooks are installed again (Context.reinstallHooks): some time
    // after the event, after the other software's, and once more later in case that software was
    // late (2 s and 10 s after it)
    private val REINSTALL_DELAYS_MS = Seq(2000L, 10000L)
    // Of the last event: the re-hooks of an earlier one are not done (they start again from it)
    private var reinstallGeneration = 0

    private def scheduleReinstall(): Unit = {
        reinstallGeneration += 1
        val generation = reinstallGeneration
        REINSTALL_DELAYS_MS.foreach(ms => later(ms)(() =>
            if (generation == reinstallGeneration) Context.reinstallHooks()))
    }

    private def locked(): Unit =
        Context.inputLost()

    private def resumed(): Unit = {
        Context.inputLost()
        scheduleReinstall()
    }

    // From the window procedure of the message window (Windows), on the hook thread: maybe while
    // the hook thread is in a call that takes the messages sent to it
    def procMessage(msg: Int, wParam: Int): Unit = {
        if ((msg == WinUser.WM_POWERBROADCAST && wParam == PBT_APMSUSPEND) ||
            (msg == User32Ex.WM_WTSSESSION_CHANGE && wParam == Wtsapi32.WTS_SESSION_LOCK)) {
            logger.debug(f"SessionEvents: locked (0x$msg%X, $wParam)")
            HookThread.post(() => locked())
        }
        else if ((msg == WinUser.WM_POWERBROADCAST &&
                  (wParam == PBT_APMRESUMEAUTOMATIC || wParam == PBT_APMRESUMESUSPEND)) ||
                 (msg == User32Ex.WM_WTSSESSION_CHANGE && wParam == Wtsapi32.WTS_SESSION_UNLOCK)) {
            logger.debug(f"SessionEvents: resumed (0x$msg%X, $wParam)")
            HookThread.post(() => resumed())
        }
    }

    // The registration for the suspend and resume: also with modern standby (S0 Low Power Idle),
    // where WM_POWERBROADCAST is sent only to the windows registered for it (null if it failed: the
    // unlock still installs the hooks again)
    private var suspendResumeNotification: Pointer = null

    // The registration for the lock and unlock. Started at the start of the session (e.g. at the
    // sign-in), WTSRegisterSessionNotification can fail until the services it needs have started:
    // retried 3 times, 10 s apart. Started later as usual, they have started already.
    private var sessionNotification = false
    private val SESSION_RETRY_MS = 10000L
    private val SESSION_RETRIES = 3
    private var sessionRetries = 0
    private var stopped = false

    private def registerSession(): Unit = {
        val hwnd = Windows.getMessageWindow
        if (!stopped && hwnd != null) {
            if (wts.WTSRegisterSessionNotification(hwnd, Wtsapi32.NOTIFY_FOR_THIS_SESSION))
                sessionNotification = true
            else {
                logger.warn(s"WTSRegisterSessionNotification: ${Windows.getLastErrorMessage}")
                if (sessionRetries < SESSION_RETRIES) {
                    sessionRetries += 1
                    later(SESSION_RETRY_MS)(() => registerSession())
                }
            }
        }
    }

    // Started once the mouse hook is installed (W10Wheel.init)
    def start(): Unit = {
        val hwnd = Windows.getMessageWindow
        if (hwnd == null)
            logger.warn("SessionEvents: no message window")
        else {
            suspendResumeNotification = u32ex.RegisterSuspendResumeNotification(hwnd, DEVICE_NOTIFY_WINDOW_HANDLE)
            if (suspendResumeNotification == null)
                logger.warn(s"RegisterSuspendResumeNotification: ${Windows.getLastErrorMessage}")
            registerSession()
        }
    }

    // When the message loop has ended (W10Wheel.main): the tasks posted after it are dropped
    def stop(): Unit = {
        stopped = true
        timer.shutdownNow()
        if (suspendResumeNotification != null) {
            u32ex.UnregisterSuspendResumeNotification(suspendResumeNotification)
            suspendResumeNotification = null
        }
        if (sessionNotification) {
            wts.WTSUnRegisterSessionNotification(Windows.getMessageWindow)
            sessionNotification = false
        }
    }
}
