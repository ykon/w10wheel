package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.util.concurrent.{ CompletableFuture, ConcurrentLinkedQueue, ExecutionException }

import com.sun.jna.platform.win32.{ Kernel32, User32 }
import com.sun.jna.platform.win32.WinDef.{ LPARAM, WPARAM }
import com.sun.jna.platform.win32.WinUser.MSG

// The thread of the low-level hooks: the main thread (as in W10Wheel 0.6 and before, with AWT and
// Swing). It installs the hooks and runs the Win32 message loop in which Windows calls them.
//
// The settings and the state of the event handlers are changed on this thread only (post, call):
// the hook procedures read and change them without locks, as they did when the menu ran on the
// same thread (SWT). The UI (the tray icon and the settings dialog) runs on the Swing thread: it
// reads the settings (volatile), and changes them through call. Nothing on this thread waits for
// the Swing thread while the loop runs (the hooks would wait, and time out): it uses invokeLater.
object HookThread {
    private val logger = Logger.getLogger()
    private val u32 = User32.INSTANCE

    // WM_APP + 1: the tasks posted (post)
    private val WM_TASK = 0x8000 + 1
    private val PM_NOREMOVE = 0

    private val tasks = new ConcurrentLinkedQueue[Runnable]()

    // running is set and the tasks are queued under it: a task queued while running is run, before
    // the loop or after it (the last ones at its end, see run)
    private val lock = new Object

    @volatile private var thread: Thread = null
    @volatile private var threadId = 0
    // Until the end of the loop: the tasks are queued for this thread (also before run: they are run
    // after init)
    @volatile private var running = true
    // In the loop (after init): the hooks may be called, nothing must wait for the Swing thread
    @volatile private var looping = false

    def isCurrent: Boolean = Thread.currentThread eq thread
    def isLooping: Boolean = looping

    // Queued if this thread runs the tasks (false after the loop)
    private def enqueue(task: Runnable): Boolean = {
        val queued = lock.synchronized {
            if (running) tasks.add(task)
            running
        }
        val id = threadId
        if (queued && id != 0)
            u32.PostThreadMessage(id, WM_TASK, new WPARAM(0), new LPARAM(0))
        queued
    }

    // Runs task on this thread, after the hook procedure or the message being handled (in the order
    // posted). Dropped after the loop (W10Wheel is exiting).
    def post(task: () => Unit): Unit = {
        if (!enqueue(() => task()))
            logger.debug("HookThread.post: dropped (the loop has ended)")
    }

    // Runs f on this thread and returns its result (its exception thrown again). At once on this
    // thread, and after the loop (e.g. at exit, while the main thread waits for the Swing thread to
    // store a number being typed).
    def call[T](f: => T): T = {
        if (isCurrent)
            f
        else {
            val res = new CompletableFuture[T]()
            val queued = enqueue(() => {
                try {
                    res.complete(f)
                }
                catch {
                    case e: Throwable => res.completeExceptionally(e)
                }
            })

            if (!queued)
                f
            else {
                try {
                    res.get()
                }
                catch {
                    case e: ExecutionException => throw e.getCause
                }
            }
        }
    }

    private def runTasks(): Unit = {
        var t = tasks.poll()
        while (t != null) {
            try {
                t.run()
            }
            catch {
                case e: Exception => logger.warn(s"HookThread task: $e")
            }
            t = tasks.poll()
        }
    }

    // Runs init (e.g. installs the hooks), then the message loop until quit. On the main thread.
    def run(init: () => Unit): Unit = {
        thread = Thread.currentThread
        val msg = new MSG
        // The message queue of the thread is made before its ID is published: PostThreadMessage fails
        // for a thread without one
        u32.PeekMessage(msg, null, 0, 0, PM_NOREMOVE)
        threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        try {
            init()
            looping = true
            runTasks()

            while (u32.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.hWnd == null && msg.message == WM_TASK)
                    runTasks()
                else {
                    u32.TranslateMessage(msg)
                    u32.DispatchMessage(msg)
                }
            }
        }
        finally {
            looping = false
            lock.synchronized {
                running = false
            }
            threadId = 0
            // Those queued meanwhile (none can be queued from now on: see call)
            runTasks()
        }
    }

    // Ends the message loop (run returns)
    def quit(): Unit =
        post(() => u32.PostQuitMessage(0))
}
