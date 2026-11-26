package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

// The handlers called as the hooks call them (Hook.procMouse, procKeyboard), with the calls to
// Windows replaced (Host): no input is sent, no cursor changed. The state of the handlers is in
// objects, one of each: the tests run one at a time (build.sbt), each from a reset (Harness.reset).
// As W10Wheel.NET's W10Wheel.Tests.

import java.nio.file.{ Files, Paths }
import java.util.concurrent.{ ConcurrentHashMap, ConcurrentLinkedQueue, CountDownLatch }

import scala.collection.mutable.ListBuffer

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.WinDef.{ HWND, LRESULT, WPARAM }
import com.sun.jna.platform.win32.WinUser.{ INPUT, WM_KEYDOWN, WM_KEYUP }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }
import com.sun.jna.platform.win32.WinUser.{ MSLLHOOKSTRUCT => HookInfo }

import win32ex.User32Ex._

object Harness {
    enum Decision {
        case Pass, Suppress
    }
    import Decision._

    // What the fake Host returns for a passed event (CallNextHookEx): the handlers suppress with 1
    private val PASSED = 0x5A5AL
    private def decisionOf(res: LRESULT): Decision = res.longValue match {
        case PASSED => Pass
        case 1L => Suppress
        case n => throw new IllegalStateException(s"unexpected hook result: $n")
    }

    // The fake state of the desktop
    private val keysDown = ConcurrentHashMap.newKeySet[Int]()
    @volatile private var cursor = (500, 400)
    private val excludedPoints = ConcurrentHashMap.newKeySet[(Int, Int)]()
    @volatile var rawInputFails = false
    private val sent = new ConcurrentLinkedQueue[INPUT]()

    // The sender held (holdSender): SendInput waits, as when it is slow or busy
    @volatile private var senderGate = new CountDownLatch(0)

    // What the application gets, in its order: the physical events passed (their message and
    // mouseData) and the inputs sent (they pass the hook after, injected)
    private val appLog = new ConcurrentLinkedQueue[Either[(Int, Int), INPUT]]()

    val EXCLUDED_PROGRAM = """C:\Games\game.exe"""
    private val OTHER_PROGRAM = """C:\Apps\app.exe"""
    private val EXCLUDED_WINDOW = 2
    private val OTHER_WINDOW = 1

    private def installHost(): Unit = {
        Host.sendInput = input => {
            senderGate.await()
            sent.add(input)
            appLog.add(scala.util.Right(input))
            true
        }
        Host.getAsyncKeyState = vk => if (keysDown.contains(vk)) 0x8000.toShort else 0.toShort
        Host.setSystemCursor = (_, _) => ()
        Host.restoreSystemCursors = () => true
        // RIDEV_REMOVE (unregister) always succeeds
        Host.registerRawInputDevices = rid => !rawInputFails || rid(0).dwFlags == RIDEV_REMOVE
        Host.callNextMouseHook = (_, _, wParam, info) => {
            if ((info.flags & 1) == 0) appLog.add(scala.util.Left((wParam.intValue, info.mouseData)))
            new LRESULT(PASSED)
        }
        Host.callNextKeyboardHook = (_, _, _, _) => new LRESULT(PASSED)
        Host.getCursorPos = pt => {
            pt.x = cursor._1
            pt.y = cursor._2
            true
        }
        Host.windowFromPoint = pt =>
            new HWND(new Pointer(if (excludedPoints.contains((pt.x, pt.y))) EXCLUDED_WINDOW else OTHER_WINDOW))
        Host.rootWindow = hWnd => hWnd
        Host.processIdOfWindow = hWnd => Pointer.nativeValue(hWnd.getPointer).toInt
        Host.fullPathOfProcess = pid => Some(if (pid == EXCLUDED_WINDOW) EXCLUDED_PROGRAM else OTHER_PROGRAM)
        // No box waiting for a click (e.g. a load of a file a test locks): the message is logged
        Dialog.showErrorMessage = (_, _) => ()
    }

    // The folder of the properties files: one of the tests, not the user's profile
    val USER_DIR: String = Paths.get(System.getProperty("java.io.tmpdir"), "W10Wheel.Tests").toString

    private lazy val initialized: Unit = {
        // Context first, as the program does: Properties first would initialize Context while it is
        // initialized itself (PROGRAM_NAME), and Context would read its DEFAULT_DEF as null
        Context.PROGRAM_NAME
        Files.createDirectories(Paths.get(USER_DIR))
        Properties.USER_DIR = USER_DIR
        // Before any test changes a setting: the defaults (Reset to defaults) are those of a start
        Context.ForTests.saveDefaults()
        installHost()
    }

    def setKeyDown(vk: Int, down: Boolean): Unit =
        if (down) keysDown.add(vk) else keysDown.remove(vk)

    def setCursor(x: Int, y: Int): Unit = cursor = (x, y)

    // A point in the window of an excluded program (EXCLUDED_PROGRAM, excluded by exclude)
    def addExcludedPoint(x: Int, y: Int): Unit = excludedPoints.add((x, y))
    def exclude(): Unit = Context.ForTests.setExcludedPrograms(Seq(EXCLUDED_PROGRAM))

    // The inputs sent since the last call, waiting up to timeoutMs for count of them (the sender
    // thread sends them)
    def takeSent(count: Int, timeoutMs: Int): List[INPUT] = {
        val deadline = System.nanoTime + timeoutMs * 1000000L
        while (sent.size < count && System.nanoTime < deadline)
            Thread.sleep(5)
        val res = ListBuffer.empty[INPUT]
        var input = sent.poll()
        while (input != null) {
            res += input
            input = sent.poll()
        }
        res.toList
    }

    // The sender held, then let go: the inputs queued meanwhile are sent after the events handled
    def holdSender(): Unit = senderGate = new CountDownLatch(1)
    def releaseSender(): Unit = senderGate.countDown()

    // None sent within timeoutMs
    def noneSent(timeoutMs: Int): Boolean = takeSent(Int.MaxValue, timeoutMs).isEmpty

    def isScrolling: Boolean = Context.isScrollMode

    // The settings and the state as at a start (the given mouse trigger, keyboard trigger and
    // locktime), the desktop as the fake one is at first. Waits for the sender to send what an
    // earlier test queued.
    def reset(trigger: String, targetVK: String): Unit = {
        initialized
        releaseSender()
        Context.inputLost()
        takeSent(Int.MaxValue, 50)
        appLog.clear()
        keysDown.clear()
        excludedPoints.clear()
        cursor = (500, 400)
        rawInputFails = false
        Context.ForTests.setExcludedPrograms(Nil)
        Context.ForTests.setSendMiddleClick(false)
        Context.ForTests.setDraggedLock(false)
        Context.ForTests.setNumber(DataID.scrollLocktime, 200)
        Context.ForTests.setNumber(DataID.pollTimeout, 200)
        Context.ForTests.setTrigger(trigger)
        Context.ForTests.setTargetVKCode(targetVK)
        // Nothing kept of the trigger before (changeTrigger), nor of the presses
        Context.inputLost()
    }

    def mouseInfo(x: Int, y: Int, mouseData: Int, flags: Int, time: Int, extra: Long): HookInfo = {
        val info = new HookInfo()
        info.pt.x = x
        info.pt.y = y
        info.mouseData = mouseData
        info.flags = flags
        info.time = time
        info.dwExtraInfo = new ULONG_PTR(extra)
        info
    }

    private def keyInfo(vk: Int, time: Int): KHookInfo = {
        val info = new KHookInfo()
        info.vkCode = vk
        info.time = time
        info
    }

    private def mouse(msg: Int, info: HookInfo): Decision =
        decisionOf(Hook.procMouse(0, new WPARAM(msg), info))

    enum Button {
        case Left, Right, Middle, X1, X2
    }
    import Button._

    private def downMsg(b: Button) = b match {
        case Left => WM_LBUTTONDOWN
        case Right => WM_RBUTTONDOWN
        case Middle => WM_MBUTTONDOWN
        case X1 | X2 => WM_XBUTTONDOWN
    }

    private def upMsg(b: Button) = b match {
        case Left => WM_LBUTTONUP
        case Right => WM_RBUTTONUP
        case Middle => WM_MBUTTONUP
        case X1 | X2 => WM_XBUTTONUP
    }

    private def xData(b: Button) = b match {
        case X1 => XBUTTON1 << 16
        case X2 => XBUTTON2 << 16
        case _ => 0
    }

    // Physical events (not injected) at a point and a time
    def down(b: Button, x: Int, y: Int, time: Int): Decision = mouse(downMsg(b), mouseInfo(x, y, xData(b), 0, time, 0))
    def up(b: Button, x: Int, y: Int, time: Int): Decision = mouse(upMsg(b), mouseInfo(x, y, xData(b), 0, time, 0))
    def move(x: Int, y: Int, time: Int): Decision = mouse(WM_MOUSEMOVE, mouseInfo(x, y, 0, 0, time, 0))
    // A move of other software (injected)
    def moveInjected(x: Int, y: Int, time: Int): Decision = mouse(WM_MOUSEMOVE, mouseInfo(x, y, 0, 1, time, 0))

    // What a sent input is (its button and whether a down), and the input as the hook gets it back
    // (injected, with its dwExtraInfo)
    private val inputEvents = List(
        (MOUSEEVENTF_LEFTDOWN, (Left, true)), (MOUSEEVENTF_LEFTUP, (Left, false)),
        (MOUSEEVENTF_RIGHTDOWN, (Right, true)), (MOUSEEVENTF_RIGHTUP, (Right, false)),
        (MOUSEEVENTF_MIDDLEDOWN, (Middle, true)), (MOUSEEVENTF_MIDDLEUP, (Middle, false)))

    def buttonOfInput(input: INPUT): Option[(Button, Boolean)] = {
        val mi = input.input.mi
        val flags = mi.dwFlags.intValue
        def x = if (mi.mouseData.intValue == XBUTTON1) X1 else X2
        if ((flags & MOUSEEVENTF_XDOWN) != 0) Some((x, true))
        else if ((flags & MOUSEEVENTF_XUP) != 0) Some((x, false))
        else inputEvents.collectFirst { case (flag, e) if (flags & flag) != 0 => e }
    }

    // The sent input coming back to the hook: its decision (the hook may drop it)
    def receive(input: INPUT): Decision = buttonOfInput(input) match {
        case Some((b, isDown)) =>
            val mi = input.input.mi
            // The X button in the high word of mouseData, as the hook gets it; LLMHF_INJECTED
            val info = mouseInfo(mi.dx.intValue, mi.dy.intValue, xData(b), 1, 0, mi.dwExtraInfo.longValue)
            mouse(if (isDown) downMsg(b) else upMsg(b), info)
        case None => throw new IllegalArgumentException("not a button input")
    }

    // The buttons' downs and ups the application has got since the last call, in its order
    def takeAppLog(): List[(Button, Boolean)] = {
        def ofMsg(msg: Int, mouseData: Int): Option[(Button, Boolean)] = {
            def x = if (Mouse.isXButton1(mouseData)) X1 else X2
            msg match {
                case WM_LBUTTONDOWN => Some((Left, true))
                case WM_LBUTTONUP => Some((Left, false))
                case WM_RBUTTONDOWN => Some((Right, true))
                case WM_RBUTTONUP => Some((Right, false))
                case WM_MBUTTONDOWN => Some((Middle, true))
                case WM_MBUTTONUP => Some((Middle, false))
                case WM_XBUTTONDOWN => Some((x, true))
                case WM_XBUTTONUP => Some((x, false))
                case _ => None
            }
        }
        val res = ListBuffer.empty[(Button, Boolean)]
        var e = appLog.poll()
        while (e != null) {
            e match {
                case scala.util.Left((msg, data)) => ofMsg(msg, data).foreach(res += _)
                case scala.util.Right(input) => buttonOfInput(input).foreach(res += _)
            }
            e = appLog.poll()
        }
        res.toList
    }

    def keyDown(vk: Int, time: Int): Decision = decisionOf(Hook.procKeyboard(0, new WPARAM(WM_KEYDOWN), keyInfo(vk, time)))
    def keyUp(vk: Int, time: Int): Decision = decisionOf(Hook.procKeyboard(0, new WPARAM(WM_KEYUP), keyInfo(vk, time)))

    val VK_PAUSE = 0x13
    val VK_LMENU = 0xA4
    val VK_A = 0x41
    val VK_TAB = 0x09
}
