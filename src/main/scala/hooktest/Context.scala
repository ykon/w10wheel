package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

// SWT
import org.eclipse.swt.SWT
//import org.eclipse.swt.widgets._
import org.eclipse.swt.widgets.Menu
import org.eclipse.swt.widgets.MenuItem
import org.eclipse.swt.widgets.Event
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.TrayItem
import org.eclipse.swt.widgets.MessageBox
import org.eclipse.swt.events.{MouseEvent as _, *}
import org.eclipse.swt.graphics.Image

import java.nio.file.NoSuchFileException
import java.io.{ File, FileInputStream, FileOutputStream }
import java.io.IOException

import com.sun.jna.platform.win32.WinUser.{ MSLLHOOKSTRUCT => HookInfo }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

import scala.collection.mutable.HashMap
import java.util.NoSuchElementException
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

object Context {
    val PROGRAM_NAME = "W10Wheel"
    // Single source of truth is `version` in build.sbt (written to the jar manifest as Implementation-Version)
    val PROGRAM_VERSION: String =
        Option(getClass.getPackage).flatMap(p => Option(p.getImplementationVersion)).getOrElse("dev")
    val ICON_RUN_NAME = "TrayIcon-Run.png"
    val ICON_STOP_NAME = "TrayIcon-Stop.png"
    private val logger = Logger.getLogger()
    lazy val systemShell = W10Wheel.shell

    @volatile private var selectedProperties = Properties.DEFAULT_DEF // default

    @volatile private var firstTrigger: Trigger = LRTrigger() // default
    @volatile private var pollTimeout = 200 // default
    @volatile private var processPriority: Windows.Priority = Windows.AboveNormal() //default
    @volatile private var sendMiddleClick = false // default

    @volatile private var keyboardHook = false // default
    @volatile private var targetVKCode = Keyboard.getVKCode(DataID.VK_NONCONVERT) // default
    @volatile private var uiLanguage = Locale.getLanguage() // default

    private def getImage(name: String) = {
        val stream = getClass.getClassLoader.getResourceAsStream(name)
        try {
            new Image(Display.getDefault, stream)
        }
        finally {
            stream.close()
        }
    }

    // Made once: a new image for every switch of Pass Mode would not be released
    private lazy val trayIconRun = getImage(ICON_RUN_NAME)
    private lazy val trayIconStop = getImage(ICON_STOP_NAME)

    private def getTrayIcon(b: Boolean): Image = {
        if (b) trayIconStop else trayIconRun
    }

    private def getTrayText(b: Boolean) = {
        s"$PROGRAM_NAME - ${convLang(if (b) "Stopped" else "Runnable")}"
    }

    private def changeTrayMode(b: Boolean): Unit = {
        trayItem.setToolTipText(getTrayText(b))
        trayItem.setImage(getTrayIcon(b))
    }

    // Incremented whenever Pass Mode is turned on and the state is initialized. A wheel event queued
    // before is not sent (InputSender), and an LR trigger wait started before neither resends nor
    // starts scroll mode (EventWaiter), even if Pass Mode has been turned off again by then: the
    // physical up may have passed while Pass Mode was on. (The resent downs and ups are checked by
    // the hook: Windows.dropResent.)
    private val passModeGeneration = new AtomicInteger(0)
    def getPassModeGeneration: Int = passModeGeneration.get

    // Pass Mode is switched under this lock, and the LR trigger checks Pass Mode and queues its
    // resent down under it (see EventWaiter.fromTimeout). Held only briefly: SendInput must not
    // be called under it (it waits for the low-level hooks, on the thread switching Pass Mode).
    val passModeLock = new Object

    private def newPassModeGeneration(): Unit = passModeLock.synchronized {
        passModeGeneration.incrementAndGet()
    }

    private object Pass {
        @volatile private var __mode = false
        def mode = __mode
        def mode_= (b: Boolean): Unit = {
            passModeLock.synchronized {
                if (b) passModeGeneration.incrementAndGet()
                __mode = b
            }

            // Pass Mode bypasses the event handlers, so nothing they wait for completes while it
            // is on (e.g. the LR trigger wait would resend a down after its up has passed), and
            // scroll mode could not be left: leave it, cancel the wait and reset their state
            Scroll.setPassMode(b)
            if (b) {
                EventWaiter.offer(Cancel(null))
                EventHandler.initState
                KEventHandler.initState()
                LastFlags.init
            }

            changeTrayMode(b)
            passModeMenuItem.setSelection(b)
        }

        def toggleMode: Unit = {
            mode = !mode
        }
    }

    def isPassMode = Pass.mode
    def setPassMode(b: Boolean) = Pass.mode = b

    def setSelectedProperties(name: String) =
        selectedProperties = name

    def isKeyboardHook = keyboardHook
    def getTargetVKCode = targetVKCode
    def isTriggerKey(ke: KeyboardEvent) = ke.vkCode == targetVKCode
    def isNoneTriggerKey = targetVKCode == 0

    def isSendMiddleClick = sendMiddleClick
    
    def getUILanguage = uiLanguage
    
    private def isCreatedMenuItems(): Boolean = {
        rootMenu != null
    }
    
    private def setUILanguage(lang: String): Unit = {
        logger.debug(s"setUILanguage: $lang")
        val reset = uiLanguage != lang
        uiLanguage = lang
        
        if (reset && isCreatedMenuItems()) {
            resetMenuText()
            resetNumberMenuItems()
        }
    }
    
    def convLang(msg: String): String = {
        logger.debug(s"convLang: msg = $msg")
        Locale.convLang(getUILanguage, msg)
    }

    sealed trait VHAdjusterMethod {
        def name = getClass.getSimpleName
    }
    case class Fixed() extends VHAdjusterMethod
    case class Switching() extends VHAdjusterMethod

    private object VHAdjuster {
        @volatile var mode = false // default
        @volatile var method: VHAdjusterMethod = Switching() // default
        @volatile var firstPreferVertical = true // default
        @volatile var firstMinThreshold = 5 // default
        @volatile var switchingThreshold = 50 // default
    }

    def isVhAdjusterMode = {
        isHorizontalScroll && VHAdjuster.mode
    }
    
    def isVhAdjusterSwitching = VHAdjuster.method == Switching()
    def isFirstPreferVertical = VHAdjuster.firstPreferVertical
    def getFirstMinThreshold = VHAdjuster.firstMinThreshold
    def getSwitchingThreshold = VHAdjuster.switchingThreshold

    private object Accel {
        // MouseWorks by Kensington (TD, M5, M6, M7, M8, M9)
        // http://www.nanayojapan.co.jp/support/help/tmh00017.htm

        val TD = Array(1, 2, 3, 5, 7, 10, 14, 20, 30, 43, 63, 91)

        abstract class Multiplier(val name: String, val dArray: Array[Double])

        case class M5() extends Multiplier(DataID.M5, Array(1.0, 1.3, 1.7, 2.0, 2.4, 2.7, 3.1, 3.4, 3.8, 4.1, 4.5, 4.8))
        case class M6() extends Multiplier(DataID.M6, Array(1.2, 1.6, 2.0, 2.4, 2.8, 3.3, 3.7, 4.1, 4.5, 4.9, 5.4, 5.8))
        case class M7() extends Multiplier(DataID.M7, Array(1.4, 1.8, 2.3, 2.8, 3.3, 3.8, 4.3, 4.8, 5.3, 5.8, 6.3, 6.7))
        case class M8() extends Multiplier(DataID.M8, Array(1.6, 2.1, 2.7, 3.2, 3.8, 4.4, 4.9, 5.5, 6.0, 6.6, 7.2, 7.7))
        case class M9() extends Multiplier(DataID.M9, Array(1.8, 2.4, 3.0, 3.6, 4.3, 4.9, 5.5, 6.2, 6.8, 7.4, 8.1, 8.7))

        @volatile var customDisabled = true
        @volatile var customTable = false
        @volatile var customThreshold: Array[Int] = null
        @volatile var customMultiplier: Array[Double] = null

        @volatile var table = true // default
        @volatile var threshold: Array[Int] = TD // default
        @volatile var multiplier: Multiplier = M5() // default

        def getMultiplier(name: String) = name match {
            case DataID.M5 => M5()
            case DataID.M6 => M6()
            case DataID.M7 => M7()
            case DataID.M8 => M8()
            case DataID.M9 => M9()
        }
    }

    def isAccelTable: Boolean =
        Accel.table

    def getAccelThreshold: Array[Int] = {
        if (!Accel.customDisabled && Accel.customTable)
            Accel.customThreshold
        else
            Accel.threshold
    }

    def getAccelMultiplier: Array[Double] = {
        if (!Accel.customDisabled && Accel.customTable)
            Accel.customMultiplier
        else
            Accel.multiplier.dArray
    }

    private object Threshold {
        @volatile var vertical = 0 // default
        @volatile var horizontal = 75 // default
    }

    def getVerticalThreshold = Threshold.vertical
    def getHorizontalThreshold = Threshold.horizontal

    private object Scroll {
        @volatile private var starting = false
        @volatile private var mode = false
        @volatile private var stime = 0
        @volatile private var sx = 0
        @volatile private var sy = 0
        @volatile var locktime = 200 // default
        @volatile var draggedLock = false // default

        @volatile var cursorChange = true // default
        @volatile var horizontal = true // default
        @volatile var reverse = false // default

        // Vertical <-> Horizontall
        @volatile var swap = false // default
        @volatile var releasedMode = false // not pressed

        @volatile private var closed = false // set at exit: no more scroll mode
        @volatile private var passMode = false // Pass Mode is on: no scroll mode
        @volatile private var byKeyboard = false // started by the keyboard trigger
        // A wheel event has been sent since scroll mode started (Windows.sendInput): the press of
        // the drag trigger is a drag then, even if the cursor would not have moved
        @volatile var scrolled = false

        // The LR trigger start was canceled before start ran on the EventWaiter thread: exit was
        // called while starting (e.g. both buttons released), so start must not start afterwards
        @volatile private var startCanceled = false

        private def setStartPoint(x: Int, y: Int): Unit = {
            sx = x;
            sy = y;
        }

        // Also called when not in scroll mode (e.g. initState, or the drag trigger clicked): only
        // what was done is undone (RawInput is registered in scroll mode only, the cursor is
        // restored only if it was changed, whatever the setting is now: see Windows.restoreCursor)
        private def exitMode(): Unit = {
            if (mode) Windows.unregisterRawInput

            mode = false
            releasedMode = false

            Windows.restoreCursor
        }

        // generation: for the LR trigger, started on the EventWaiter thread some time after the
        // physical downs: also refused if Pass Mode has been turned on (or the state initialized)
        // since then, even if it has been turned off again
        private def canStart(generation: Option[Int]): Boolean =
            !closed && !passMode && generation.forall(_ == passModeGeneration.get)

        def start(info: HookInfo, generation: Option[Int]) = synchronized {
            // generation is given for the LR trigger only
            val canceled = generation.isDefined && startCanceled
            if (generation.isDefined) startCanceled = false

            // Not while in scroll mode: the handlers end it before, another trigger's included (for
            // the LR trigger, EventWaiter.waiterThread). Without RawInput, the moves would be
            // suppressed with no scroll: refused.
            if (canStart(generation) && !canceled && !mode && Windows.registerRawInput) {
                stime = info.time
                setStartPoint(info.pt.x, info.pt.y)
                Windows.initScroll

                if (cursorChange && !firstTrigger.isDrag)
                    Windows.changeCursorV

                byKeyboard = false
                scrolled = false
                mode = true
            }
            // Also when refused (exiting, Pass Mode or canceled): do not leave the LR trigger
            // starting
            starting = false
        }

        def start(kinfo: KHookInfo) = synchronized {
            // A pending LR trigger start is superseded: cancel it, or it would start scroll mode
            // again after this one has ended
            if (starting) startCanceled = true

            if (canStart(None) && !mode && Windows.registerRawInput) {
                stime = kinfo.time
                val pt = Windows.getCursorPos
                setStartPoint(pt.x, pt.y)
                Windows.initScroll

                if (cursorChange)
                    Windows.changeCursorV

                byKeyboard = true
                scrolled = false
                mode = true
            }
            starting = false
        }

        def exit = synchronized {
            // Exiting while the LR trigger start is pending (e.g. both buttons released before
            // start ran): cancel it, or scroll mode would start afterwards with no button pressed
            if (starting) {
                startCanceled = true
                starting = false
            }

            exitMode()
        }

        // At exit: waits for a start in progress (on the EventWaiter thread for the LR trigger),
        // leaves scroll mode and refuses later starts, so that the cursor is restored and RawInput
        // is not registered again afterwards
        def close = synchronized {
            closed = true
            exitMode()
        }

        // Pass Mode bypasses the event handlers, so scroll mode could not be left while it is on.
        // Turning it on leaves scroll mode and refuses starts until it is turned off. Under the
        // lock, so that a start in progress on the EventWaiter thread is not missed.
        def setPassMode(b: Boolean) = synchronized {
            passMode = b
            // A pending LR trigger start is refused (Pass Mode generation): do not leave it
            // starting, the ups after Pass Mode is turned off would be taken for it
            if (b) {
                starting = false
                exitMode()
            }
        }

        def checkExit(time: Int) = {
            val dt = time - stime
            logger.debug(s"scroll time: $dt ms")
            dt > locktime
        }

        def isMode = mode
        def isByKeyboard = byKeyboard
        def getStartTime = stime
        def getStartPoint = (sx, sy)

        // For the LR trigger, on the EventWaiter thread before the offer of the second down returns
        // (so before the hook handles the ups) and before start, which resets it: set after start,
        // it would remain. start refuses while closed, in Pass Mode or in another generation (see
        // start), so do not wait for it then.
        def setStarting(generation: Int) = synchronized {
            starting = !mode && !closed && !passMode && generation == passModeGeneration.get
            if (starting) startCanceled = false
        }

        def isStarting = starting
    }

    def startScrollMode(info: HookInfo) = Scroll.start(info, None)
    def startScrollModeFor(generation: Int, info: HookInfo) = Scroll.start(info, Some(generation))
    def startScrollMode(kinfo: KHookInfo) = Scroll.start(kinfo)

    def exitScrollMode = Scroll.exit
    def closeScrollMode = Scroll.close
    def isScrollMode = Scroll.isMode
    def getScrollStartPoint = Scroll.getStartPoint
    def checkExitScroll(time: Int) = Scroll.checkExit(time)
    def isReverseScroll = Scroll.reverse
    def isHorizontalScroll = Scroll.horizontal
    def isDraggedLock = Scroll.draggedLock
    def isSwapScroll = Scroll.swap

    def isReleasedScrollMode = Scroll.releasedMode
    def isPressedScrollMode = Scroll.isMode && !Scroll.releasedMode

    // Pressed scroll mode started by a mouse trigger: the ups of mouse triggers end only this one.
    // The keyboard trigger can start scroll mode while their downs are handled (e.g. while the LR
    // trigger start is pending); their ups are then suppressed like their downs.
    def isPressedMouseScrollMode = isPressedScrollMode && !Scroll.isByKeyboard
    def isKeyboardScrollMode = Scroll.isMode && Scroll.isByKeyboard
    def isPressedKeyboardScrollMode = isPressedScrollMode && Scroll.isByKeyboard

    def setReleasedScrollMode = Scroll.releasedMode = true

    def setStartingScrollModeFor(generation: Int) = Scroll.setStarting(generation)
    def isStartingScrollMode = Scroll.isStarting

    def isScrolled = Scroll.scrolled
    def setScrolled(): Unit = Scroll.scrolled = true

    private object RealWheel {
        @volatile var mode = false // default
        @volatile var wheelDelta = 120 // default
        @volatile var vWheelMove = 60 // default
        @volatile var hWheelMove = 60 // default
        @volatile var quickFirst = false // default
        @volatile var quickTurn = false // default
    }

    def isRealWheelMode = RealWheel.mode
    def getWheelDelta = RealWheel.wheelDelta
    def getVWheelMove = RealWheel.vWheelMove
    def getHWheelMove = RealWheel.hWheelMove
    def isQuickFirst = RealWheel.quickFirst
    def isQuickTurn = RealWheel.quickTurn

    def getPollTimeout = pollTimeout
    def isCursorChange = Scroll.cursorChange

    object LastFlags {
        // R = Resent
        private val ldR = new AtomicBoolean(false)
        private val rdR = new AtomicBoolean(false)

        // P = Passed
        private val ldP = new AtomicBoolean(false)
        private val rdP = new AtomicBoolean(false)

        // S = Suppressed
        private val ldS = new AtomicBoolean(false)
        private val rdS = new AtomicBoolean(false)
        private val mdS = new AtomicBoolean(false)
        private val x1S = new AtomicBoolean(false)
        private val x2S = new AtomicBoolean(false)

        private val kdS = (0 until 256).map(_ => new AtomicBoolean(false));
        // Keys whose down passed W10Wheel (to the application) and whose up has not come yet
        private val kdP = (0 until 256).map(_ => new AtomicBoolean(false));
        // Keys whose down was suppressed (kdS) when the state was initialized (init) and whose up
        // has not come yet: their up alone is suppressed (the application did not get the down).
        // Their next down resets them (a repeat cannot be told from a new press, it is taken for one).
        private val kdL = (0 until 256).map(_ => new AtomicBoolean(false));

        private def isKey(vk: Int) = vk >= 0 && vk < kdS.length

        // Not the keys passed (kdP), whose ups pass (also in Pass Mode, see Hook) and reset them;
        // reset, a key still pressed would lose it (e.g. pressed in Pass Mode: its repeats would
        // start scroll mode if it is the trigger, and its up would be suppressed). Whether a key
        // is still pressed cannot be told: GetAsyncKeyState may fail, and a key whose down was
        // suppressed is not pressed for it. A key whose up was missed passes its next down once.
        // The keys suppressed (kdS) are reset too, so that a key whose up was missed is not
        // suppressed again: only their up alone is (kdL), as the hook does for the buttons
        // (Windows.dropLoneUp).
        def init: Unit = {
            Array(ldR, rdR, ldP, rdP, ldS, rdS, mdS, x1S, x2S).foreach(f => f.set(false))
            suppressedKeysToLoneUp
        }

        // The keys suppressed (kdS) are suppressed for their up alone (kdL): a key whose up was
        // missed is not suppressed again (see init, and KEventHandler.hookInstalled)
        def suppressedKeysToLoneUp: Unit = {
            for (vk <- kdS.indices) {
                if (kdS(vk).getAndSet(false))
                    kdL(vk).set(true)
            }
        }

        // A down of a key (kdL): its up is not alone any more
        def resetLoneUpKey(vk: Int): Unit =
            if (isKey(vk)) kdL(vk).set(false)

        // The keyboard hook is installed again after Windows had removed the old one: a key whose
        // up was missed meanwhile may have been pressed again, its down passing (no hook): neither
        // its repeats nor its up are suppressed (the passed keys, kdP, are kept: they pass)
        def forgetSuppressedKeys: Unit = {
            kdS.foreach(f => f.set(false))
            kdL.foreach(f => f.set(false))
        }

        // The up of a key whose down was suppressed before init (kdL), with no down since; resets it
        def getAndReset_LoneUpKey(vk: Int): Boolean =
            isKey(vk) && kdL(vk).getAndSet(false)


        def setResent(down: MouseEvent): Unit = down match {
            case LeftDown(_) => ldR.set(true)
            case RightDown(_) => rdR.set(true)
            case _ => throw new IllegalArgumentException()
        }

        def getAndReset_ResentDown(up: MouseEvent): Boolean = up match {
            case LeftUp(_) => ldR.getAndSet(false)
            case RightUp(_) => rdR.getAndSet(false)
            case _ => throw new IllegalArgumentException()
        }

        def setPassed(down: MouseEvent): Unit = down match {
            case LeftDown(_) => ldP.set(true)
            case RightDown(_) => rdP.set(true)
            case _ => throw new IllegalArgumentException()
        }

        def getAndReset_PassedDown(up: MouseEvent): Boolean = up match {
            case LeftUp(_) => ldP.getAndSet(false)
            case RightUp(_) => rdP.getAndSet(false)
            case _ => throw new IllegalArgumentException()
        }

        def setSuppressed(down: MouseEvent): Unit = down match {
            case LeftDown(_) => ldS.set(true)
            case RightDown(_) => rdS.set(true)
            case MiddleDown(_) => mdS.set(true)
            case X1Down(_) => x1S.set(true)
            case X2Down(_) => x2S.set(true)
            case _ => throw new IllegalArgumentException()
        }

        def setSuppressed(down: KeyboardEvent): Unit = down match {
            case KeyDown(_) => if (isKey(down.vkCode)) kdS(down.vkCode).set(true)
            case _ => throw new IllegalArgumentException()
        }

        // The key's down was suppressed and its up has not come yet: its repeats are suppressed too
        def isSuppressedKey(vk: Int): Boolean =
            isKey(vk) && kdS(vk).get

        // The keyboard trigger changed while the old key was pressed (its down was suppressed)
        def setSuppressedKey(vk: Int): Unit =
            if (isKey(vk)) kdS(vk).set(true)

        def setPassedKey(vk: Int): Unit =
            if (isKey(vk)) kdP(vk).set(true)

        // The key's down passed: its repeats and its up pass too, even if it is the trigger now
        def isPassedKey(vk: Int): Boolean =
            isKey(vk) && kdP(vk).get

        def getAndReset_PassedKey(vk: Int): Boolean =
            isKey(vk) && kdP(vk).getAndSet(false)

        // A key that does not repeat (Pause): its down is always a new press, its flags are reset
        // (it may send only a down, the flags of the previous press would remain). The flags of the
        // other keys are kept until their up (a repeat cannot be told from a new press for sure).
        def newKeyDown(vk: Int): Unit = {
            if (Keyboard.isNoRepeatKey(vk) && isKey(vk)) {
                kdS(vk).set(false)
                kdP(vk).set(false)
            }
        }

        // The keyboard hook is removed: the ups will not be seen
        def resetKeys: Unit = {
            kdS.foreach(f => f.set(false))
            kdP.foreach(f => f.set(false))
            kdL.foreach(f => f.set(false))
        }

        // The downs suppressed whose up has not come yet (left, right, middle, X1, X2); resets them
        def takeSuppressedDowns: Array[Boolean] =
            Array(ldS, rdS, mdS, x1S, x2S).map(f => f.getAndSet(false))

        // The downs resent alone whose up has not come yet (left, right); resets them
        def takeResentDowns: Array[Boolean] =
            Array(ldR, rdR).map(f => f.getAndSet(false))

        def getAndReset_SuppressedDown(up: MouseEvent): Boolean = up match {
            case LeftUp(_) => ldS.getAndSet(false)
            case RightUp(_) => rdS.getAndSet(false)
            case MiddleUp(_) => mdS.getAndSet(false)
            case X1Up(_) => x1S.getAndSet(false)
            case X2Up(_) => x2S.getAndSet(false)
            case _ => throw new IllegalArgumentException()
        }

        def getAndReset_SuppressedDown(up: KeyboardEvent): Boolean = up match {
            case KeyUp(_) => isKey(up.vkCode) && kdS(up.vkCode).getAndSet(false)
            case _ => throw new IllegalArgumentException()
        }

        // The down of the button of an event was suppressed and its up has not come yet
        def isSuppressedDown(me: MouseEvent): Boolean = me match {
            case LeftEvent(_) => ldS.get
            case RightEvent(_) => rdS.get
            case MiddleEvent(_) => mdS.get
            case X1Event(_) => x1S.get
            case X2Event(_) => x2S.get
            case _ => false
        }

        // The flags of the press of the button of an event (resent, suppressed, passed)
        def resetFor(me: MouseEvent): Unit = me match {
            case LeftEvent(_) => ldR.set(false); ldS.set(false); ldP.set(false)
            case RightEvent(_) => rdR.set(false); rdS.set(false); rdP.set(false)
            case MiddleEvent(_) => mdS.set(false)
            case X1Event(_) => x1S.set(false)
            case X2Event(_) => x2S.set(false)
            case _ => ()
        }

        /*
        def reset(down: KeyboardEvent) = down match {
            case KeyDown(_) => kdS(down.vkCode) = false
            case _ => {}
        }
        */
    }

    def isTrigger(e: Trigger) = firstTrigger == e
    def isLRTrigger = isTrigger(LRTrigger())

    def isTriggerEvent(e: MouseEvent): Boolean = isTrigger(Mouse.getTrigger(e))

    def isDragTriggerEvent(e: MouseEvent): Boolean = e match {
        case LeftEvent(_) => isTrigger(LeftDragTrigger())
        case RightEvent(_) => isTrigger(RightDragTrigger())
        case MiddleEvent(_) => isTrigger(MiddleDragTrigger())
        case X1Event(_) => isTrigger(X1DragTrigger())
        case X2Event(_) => isTrigger(X2DragTrigger())
        case _ => throw new IllegalArgumentException(s"Not a button event: ${e.name}")
    }

    def isSingleTrigger = firstTrigger.isSingle
    def isDoubleTrigger = firstTrigger.isDouble
    def isDragTrigger = firstTrigger.isDrag
    def isNoneTrigger = firstTrigger.isNone
    
    type MenuMap = HashMap[String, MenuItem]

    private def resetMapMenuItems(map: MenuMap, pred: String => Boolean): Unit = {
        logger.debug("resetMapMenuItems")
        map.foreach { case (id, item) =>
            item.setSelection(pred(id))
        }
    }

    private def resetTriggerMenuItems(): Unit = {
        resetMapMenuItems(triggerMenuMap, (id => Mouse.getTrigger(id) == firstTrigger))
    }

    private def resetAccelMenuItems(): Unit = {
        resetMapMenuItems(accelMenuMap, (id => Accel.getMultiplier(id) == Accel.multiplier))
    }

    private def resetPriorityMenuItems(): Unit = {
        resetMapMenuItems(priorityMenuMap, (id => Windows.getPriority(id) == processPriority))
    }
    
    private def resetLanguageMenuItems(): Unit = {
        resetMapMenuItems(languageMenuMap, (id => id == getUILanguage))
    }

    private def resetNumberMenuItems(): Unit = {
        numberMenuMap.foreach { case (id, item) =>
            val n = getNumberOfName(id)
            item.setText(makeNumberText(id, n))
        }
    }

    private def resetBoolMenuItems(): Unit = {
        resetMapMenuItems(boolMenuMap, getBooleanOfName)
    }

    private def resetKeyboardMenuItems(): Unit = {
        resetMapMenuItems(keyboardMenuMap, (id => Keyboard.getVKCode(id) == targetVKCode))
    }

    private def resetVhAdjusterMenuItems(): Unit = {
        boolMenuMap(DataID.vhAdjusterMode).setEnabled(Scroll.horizontal)
        resetMapMenuItems(vhAdjusterMenuMap, (id => getVhAdjusterMethod(id) == VHAdjuster.method))
    }

    private def resetOnOffMenuItems(): Unit = {
        OnOffNames.foreach(name => {
            val item = boolMenuMap(name)
            item.setText(convLang(getOnOffText(getBooleanOfName(name))))
        })
    }

    private def resetAllMenuItems(): Unit = {
        logger.debug("resetAllMenuItems")
        resetTriggerMenuItems()
        resetKeyboardMenuItems()
        resetAccelMenuItems()
        resetPriorityMenuItems()
        resetLanguageMenuItems()
        resetNumberMenuItems()
        resetBoolMenuItems()
        // Reload or switching properties can add or remove the custom table
        setEnabledMenu(boolMenuMap, DataID.customAccelTable, !Accel.customDisabled)
        resetVhAdjusterMenuItems()
        resetOnOffMenuItems()
    }

    private def getFirstWord(s: String): String =
        s.split(" ")(0)
    
    private def unselectAllItems(map: MenuMap): Unit = {
        map.foreach { case (_, item) =>
            item.setSelection(false)
        }
    }

    private def addListener(item: MenuItem, f: Event => Unit): Unit = {
        item.addListener(SWT.Selection, (e: Event) => f(e))
    }

    private def addListenerSelection(item: MenuItem, f: Event => Unit): Unit = {
        addListener(item, e =>
            if (item.getSelection)
                f(e)
        )
    }
    
    private case class MenuData(engText: String, id: Option[String])
    
    /*
    private def dataToID(item: MenuItem): String = item.getData() match {
        case data: MenuData => data.id match {
            case Some(id) => id
            case None => throw new IllegalArgumentException("id is None.")
        }
        case _ => throw new IllegalArgumentException("data is not MenuData.")
    }
    */
    
    /*
    private def dataToEngText(item: MenuItem): String = item.getData() match {
        case data: MenuData => data.engText
        case _ => throw new IllegalArgumentException("data is not MenuData.")
    }
    */
        
    private def dataToEngText(item: MenuItem): String = item.getData() match {
        case data: String => data
        case _ => throw new IllegalArgumentException("data is not String.")
    }
        
    private val triggerMenuMap = new MenuMap()

    private def createMapMenuItem(menu: Menu, data: MenuData, map: MenuMap, setID: String => Unit): MenuItem = {
        val item = createRadioMenuItem(menu, data)
        val id = data.id.get
        
        logger.debug("addClickMapMenuItem: " + id)
        
        map(id) = item

        addListenerSelection(item, _ => {
            logger.debug("Listener(Selection): " + id)
            unselectAllItems(map)
            item.setSelection(true)
            setID(id)
        })
        
        item
    }
   
    private def createBoolMenuItem(menu: Menu, data: MenuData, enabled: Boolean = true) = {
        val item = createCheckMenuItem(menu, data)
        item.setEnabled(enabled)
        addListener(item, makeSetBooleanEvent(data.id.get))
        boolMenuMap(data.id.get) = item
        item
    }

    private def createBoolMenuItem(menu: Menu, name: String): Unit = {
        createBoolMenuItem(menu, MenuData(name, Some(name)))
    }

    private def addSeparator(menu: Menu): Unit = {
        new MenuItem(menu, SWT.SEPARATOR)
    }
    
    private def isSeparator(item: MenuItem): Boolean = {
        item.getStyle == SWT.SEPARATOR
    }

    private def createTriggerMenu(parent: Menu): Unit = {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        
        val add = (text: String) => {
            val data = MenuData(text, Some(getFirstWord(text)))
            createMapMenuItem(menu, data, triggerMenuMap, setTrigger)
        }

        add(DataID.LR + " (Left <<-->> Right)")
        add(DataID.Left + " (Left -->> Right)")
        add(DataID.Right + " (Right -->> Left)")
        addSeparator(menu)

        add(DataID.Middle)
        add(DataID.X1)
        add(DataID.X2)
        addSeparator(menu)

        add(DataID.LeftDrag)
        add(DataID.RightDrag)
        add(DataID.MiddleDrag)
        add(DataID.X1Drag)
        add(DataID.X2Drag)
        addSeparator(menu)

        add(DataID.None)
        addSeparator(menu)

        createBoolMenuItem(menu, MenuData("Send MiddleClick", Some(DataID.sendMiddleClick)), isSingleTrigger)
        createBoolMenuItem(menu, MenuData("Dragged Lock", Some(DataID.draggedLock)), isDragTrigger)

        createCascadeMenuItem(parent, menu, "Trigger")
    }

    private def setAccelMultiplier(name: String): Unit = {
        logger.debug(s"setAccelMultiplier: $name")
        Accel.multiplier = Accel.getMultiplier(name)
    }

    private val accelMenuMap = new MenuMap()

    private def createAccelTableMenu(parent: Menu): Unit = {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        
        val add = (text: String) => {
            val data = MenuData(text, Some(getFirstWord(text)))
            createMapMenuItem(menu, data, accelMenuMap, setAccelMultiplier)
        }

        createOnOffMenuItem(menu, DataID.accelTable)
        addSeparator(menu)

        add(DataID.M5 + " (1.0 ... 4.8)")
        add(DataID.M6 + " (1.2 ... 5.8)")
        add(DataID.M7 + " (1.4 ... 6.7)")
        add(DataID.M8 + " (1.6 ... 7.7)")
        add(DataID.M9 + " (1.8 ... 8.7)")
        addSeparator(menu)
        createBoolMenuItem(menu, MenuData("Custom Table", Some(DataID.customAccelTable)), !Accel.customDisabled)

        createCascadeMenuItem(parent, menu, "Accel Table")
    }

    private def openNumberInputDialog(name: String, low: Int, up: Int) = {
        val cur = getNumberOfName(name)
        val dialog = new Dialog.NumberInputDialog(systemShell, convLang(name), low, up, cur)
        dialog.open
    }

    private def makeNumberText(name: String, n: Int) =
        s"${convLang(name)} = $n"

    private def setPriority(name: String) = {
        val p = Windows.getPriority(name)
        logger.debug(s"setPriority: ${p.name}")
        processPriority = p
        Windows.setPriority(p)
    }

    private val priorityMenuMap = new MenuMap()

    private def createPriorityMenu(parent: Menu): Unit = {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        
        val add = (text: String, id: String) => {
            val data = MenuData(text, Some(id))
            createMapMenuItem(menu, data, priorityMenuMap, setPriority)
        }

        add("High", DataID.High)
        add("Above Normal", DataID.AboveNormal)
        add("Normal", DataID.Normal)

        createCascadeMenuItem(parent, menu, "Priority")
    }

    private val numberMenuMap = new MenuMap()

    private def createMenuItem(parent: Menu, style: Int, data: MenuData): MenuItem = {
        val item = new MenuItem(parent, style)
        item.setText(convLang(data.engText))
        //item.setData(data)
        item.setData(data.engText)
        item
    }

    private def createPushMenuItem(parent: Menu, data: MenuData): MenuItem = {
        createMenuItem(parent, SWT.PUSH, data)
    }

    private def createRadioMenuItem(parent: Menu, data: MenuData): MenuItem = {
        createMenuItem(parent, SWT.RADIO, data)
    }

    private def createCheckMenuItem(parent: Menu, data: MenuData): MenuItem = {
        createMenuItem(parent, SWT.CHECK, data)
    }

    private def createCascadeMenuItem(parent: Menu, pulldown: Menu, text: String): MenuItem = {
        val item = createMenuItem(parent, SWT.CASCADE, MenuData(text, None))
        item.setMenu(pulldown)
        item
    }

    private def createNumberMenuItem(menu: Menu, name: String, low: Int, up: Int): Unit = {
        val n = getNumberOfName(name)
        val item = createPushMenuItem(menu, MenuData(makeNumberText(name, n), Some(name)))
        numberMenuMap(name) = item

        addListener(item, _ => {
            val num = openNumberInputDialog(name, low, up)
            num.foreach(n => {
                setNumberOfName(name, n)
                item.setText(makeNumberText(name, n))
            })
        })
    }

    private def createNumberMenu(parent: Menu): Unit = {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        val add = (name: String, low: Int, up: Int) => createNumberMenuItem(menu, name, low, up)

        add(DataID.pollTimeout, 50, 500) // as in the properties file
        add(DataID.scrollLocktime, 150, 500)
        addSeparator(menu)

        add(DataID.verticalThreshold, 0, 500)
        add(DataID.horizontalThreshold, 0, 500)

        createCascadeMenuItem(parent, menu, "Set Number")
    }

    private def getOnOffText(b: Boolean) =
        //if (b) "ON (-->> OFF)" else "OFF (-->> ON)"
        "ON / OFF"

    private def createOnOffMenuItem(menu: Menu, id: String, action: Boolean => Unit = _ => {}): Unit = {
        val item = createCheckMenuItem(menu, MenuData(getOnOffText(getBooleanOfName(id)), Some(id)))
        boolMenuMap(id) = item

        addListener(item, _ => {
            val b = item.getSelection
            item.setText(convLang(getOnOffText(b)))
            setBooleanOfName(id, b)
            action(b)
        })
    }

    private def createRealWheelModeMenu(parent: Menu): Unit = {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        val addNum = (name: String, low: Int, up: Int) => createNumberMenuItem(menu, name, low, up)
        val addBool = (name: String) => createBoolMenuItem(menu, name)

        createOnOffMenuItem(menu, DataID.realWheelMode)
        addSeparator(menu)

        addNum(DataID.wheelDelta, 10, 500)
        addNum(DataID.vWheelMove, 10, 500)
        addNum(DataID.hWheelMove, 10, 500)
        addSeparator(menu)

        addBool(DataID.quickFirst)
        addBool(DataID.quickTurn)

        createCascadeMenuItem(parent, menu, "Real Wheel Mode")
    }

    private val vhAdjusterMenuMap = new MenuMap()

    private def getVhAdjusterMethod(name: String) = name match {
        case DataID.Fixed => Fixed()
        case DataID.Switching => Switching()
    }

    private def setVhAdjusterMethod(name: String) = {
        logger.debug(s"setVhAdjusterMethod: $name")
        VHAdjuster.method = getVhAdjusterMethod(name)
    }

    private def createVhAdjusterMenu(parent: Menu): Unit = {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        
        val add = (text: String) => {
            val data = MenuData(text, Some(text))
            createMapMenuItem(menu, data, vhAdjusterMenuMap, setVhAdjusterMethod)
        }
        
        val addNum = (name: String, low: Int, up: Int) => createNumberMenuItem(menu, name, low, up)
        val addBool = (name: String) => createBoolMenuItem(menu, name)

        createOnOffMenuItem(menu, DataID.vhAdjusterMode)
        boolMenuMap(DataID.vhAdjusterMode).setEnabled(Scroll.horizontal)
        addSeparator(menu)

        add(DataID.Fixed)
        add(DataID.Switching)
        addSeparator(menu)

        addBool(DataID.firstPreferVertical)
        addNum(DataID.firstMinThreshold, 1, 10)
        addNum(DataID.switchingThreshold, 10, 500)

        createCascadeMenuItem(parent, menu, "VH Adjuster")
    }

    private def toString2F(d: Double) =
        ("%.2f".format(d))

    private val keyboardMenuMap = new MenuMap()

    private def setTargetVKCode(name: String): Unit = {
        logger.debug(s"setTargetVKCode: $name")
        val old = targetVKCode
        val code = Keyboard.getVKCode(name)
        targetVKCode = code

        if (code != old)
            KEventHandler.changeTrigger(old, true)
    }

    private def setKeyboardHookOf(b: Boolean): Unit = {
        Hook.setOrUnsetKeyboardHook(b) match {
            case Some(err) =>
                // Not installed (the previous hook removed): off, and the menu shows it (its check
                // too: the on/off items are check items, resetOnOffMenuItems sets only the text)
                keyboardHook = false
                if (isCreatedMenuItems()) {
                    boolMenuMap.get(DataID.keyboardHook).foreach(_.setSelection(false))
                    resetOnOffMenuItems()
                }

                KEventHandler.changeTrigger(targetVKCode, false)
                Dialog.errorMessage(s"${convLang("Failed keyboard hook install")}: $err", "Error")
            case None =>
                if (b)
                    // Installed again on every load (e.g. Reload after Windows removed it): ups may
                    // have been missed if the old hook had been removed by Windows
                    KEventHandler.hookInstalled(Hook.isOldKeyboardHookLost)
                else
                    KEventHandler.changeTrigger(targetVKCode, false)
        }
    }

    private def createKeyboardMenu(parent: Menu): Unit = {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        
        val add = (text: String) => {
            val data = MenuData(text, Some(getFirstWord(text)))
            createMapMenuItem(menu, data, keyboardMenuMap, setTargetVKCode)
        }

        createOnOffMenuItem(menu, DataID.keyboardHook, setKeyboardHookOf)
        addSeparator(menu)
        
        add(DataID.VK_TAB + " (Tab)")
        add(DataID.VK_PAUSE + " (Pause)")
        add(DataID.VK_CAPITAL + " (Caps Lock)")
        add(DataID.VK_CONVERT + " (Henkan)")
        add(DataID.VK_NONCONVERT + " (Muhenkan)")
        add(DataID.VK_PRIOR + " (Page Up)")
        add(DataID.VK_NEXT + " (Page Down)")
        add(DataID.VK_END + " (End)")
        add(DataID.VK_HOME + " (Home)")
        add(DataID.VK_SNAPSHOT + " (Print Screen)")
        add(DataID.VK_INSERT + " (Insert)")
        add(DataID.VK_DELETE + " (Delete)")
        add(DataID.VK_LWIN + " (Left Windows)")
        add(DataID.VK_RWIN + " (Right Windows)")
        add(DataID.VK_APPS + " (Application)")
        add(DataID.VK_NUMLOCK + " (Number Lock)")
        add(DataID.VK_SCROLL + " (Scroll Lock)")
        add(DataID.VK_LSHIFT + " (Left Shift)")
        add(DataID.VK_RSHIFT + " (Right Shift)")
        add(DataID.VK_LCONTROL + " (Left Ctrl)")
        add(DataID.VK_RCONTROL + " (Right Ctrl)")
        add(DataID.VK_LMENU + " (Left Alt)")
        add(DataID.VK_RMENU + " (Right Alt)")
        addSeparator(menu)
        add(DataID.None)

        createCascadeMenuItem(parent, menu, "Keyboard")
    }

    private val DEFAULT_DEF = Properties.DEFAULT_DEF

    // Selects the properties name if its file can be read (see loadPropertiesOf)
    private def setProperties(name: String): Unit = {
        if (selectedProperties != name) {
            logger.debug(s"setProperties: $name")

            if (loadPropertiesOf(name))
                resetAllMenuItems()
        }
    }

    private def addPropertiesMenu(menu: Menu, name: String): Unit = {
        val item = createRadioMenuItem(menu, MenuData(name, None))
        item.setSelection(name == selectedProperties)

        addListenerSelection(item, _ => {
            storeProperties
            setProperties(name)
        })
    }

    private def addDefaultPropertiesMenu(menu: Menu): Unit = {
        addPropertiesMenu(menu, DEFAULT_DEF)
    }

    private def addPropertiesMenu(menu: Menu, file: File): Unit = {
        addPropertiesMenu(menu, Properties.getUserDefName(file))
    }

    private def openDir(path: String): Unit = {
        val desk = java.awt.Desktop.getDesktop
        desk.browse(new File(path).toURI())
    }

    private def createOpenDirMenuItem(menu: Menu, dir: String): Unit = {
        val item = createPushMenuItem(menu, MenuData("Open Dir", None))
        addListener(item, _ => openDir(dir))
    }

    // The characters not allowed in a Windows file name (as .NET's Path.GetInvalidFileNameChars)
    private val InvalidFileNameChars: Set[Char] = "\"<>|:*?\\/".toSet ++ (0 until 32).map(_.toChar)

    // A part of a file name (see Properties.getPath), not Default whatever its case (a name only
    // differing in case would be confused with it), nor a command (--)
    private def isValidPropertiesName(name: String) =
        !name.isBlank && name.strip == name &&
        !name.equalsIgnoreCase(DEFAULT_DEF) &&
        !name.startsWith("--") &&
        !name.exists(InvalidFileNameChars.contains)

    private def createAddPropertiesMenuItem(menu: Menu): Unit = {
        val item = createPushMenuItem(menu, MenuData("Add", None))
        addListener(item, _ => {
            val dialog = new Dialog.TextInputDialog(systemShell, convLang("Properties Name"), convLang("Add Properties"))
            val res = dialog.open

            try {
                res.foreach(name => {
                    if (isValidPropertiesName(name)) {
                        storeProperties
                        Properties.copy(selectedProperties, name)
                        selectedProperties = name
                    }
                    else
                        Dialog.errorMessage(s"${convLang("Invalid Name")}: $name", convLang("Name Error"))
                })
            }
            catch {
                case e: Exception =>
                    Dialog.errorMessageE(e)
            }
        })
    }

    private def createDeletePropertiesMenuItem(menu: Menu): Unit = {
        val item = createPushMenuItem(menu, MenuData("Delete", None))
        val name = selectedProperties
        item.setEnabled(name != DEFAULT_DEF)
        addListener(item, _ => {
            try {
                if (Dialog.openYesNoMessage(s"${convLang("Delete properties")}: $name")) {
                    // Default is read first, then the file is deleted, then Default is selected
                    // (as W10Wheel.NET): if either fails, nothing changes (an error is shown) and
                    // the settings of these properties are kept, those not stored too. Selected
                    // before the delete, a failed delete would leave Default selected (the changes
                    // lost); deleted before Default is read, the deleted properties could stay
                    // selected (and their file written again at exit).
                    readPropertiesOrShowError(DEFAULT_DEF).foreach(p => {
                        Properties.delete(name)
                        selectProperties(DEFAULT_DEF, p)
                        resetAllMenuItems()
                    })
                }
            }
            catch {
                case e: Exception =>
                    Dialog.errorMessageE(e)
            }
        })
    }

    private def createPropertiesMenu(parent: Menu): Unit = {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        val addDefault = () => addDefaultPropertiesMenu(menu)
        val add = (f: File) => addPropertiesMenu(menu, f)

        menu.addListener(SWT.Show, (_: Event) => {
            logger.debug("Listener - Show: PropertiesMenu")
            menu.getItems.foreach(_.dispose)

            createReloadPropertiesMenu(menu)
            createSavePropertiesMenu(menu)
            addSeparator(menu)

            createOpenDirMenuItem(menu, Properties.USER_DIR)
            createAddPropertiesMenuItem(menu)
            createDeletePropertiesMenuItem(menu)
            addSeparator(menu)

            addDefault()
            addSeparator(menu)

            Properties.getPropFiles.foreach(add)
        })

        createCascadeMenuItem(parent, menu, "Properties")
    }

    def reloadProperties: Unit = {
        if (loadProperties)
            resetAllMenuItems()
    }

    private def createReloadPropertiesMenu(menu: Menu): Unit = {
        val item = createPushMenuItem(menu, MenuData("Reload", None))
        addListener(item, _ => reloadProperties)
    }

    private def createSavePropertiesMenu(menu: Menu): Unit = {
        val item = createPushMenuItem(menu, MenuData("Save", None))
        addListener(item, _ => storeProperties)
    }

    private def makeSetBooleanEvent(id: String) = {
        (e: Event) => {
            val item = e.widget.asInstanceOf[MenuItem]
            val b = item.getSelection
            setBooleanOfName(id, b)
        }
    }

    private val boolMenuMap = new MenuMap()

    private def createCursorChangeMenu(menu: Menu) = {
        createBoolMenuItem(menu, MenuData("Cursor Change", Some(DataID.cursorChange)))
    }

    private def createHorizontalScrollMenu(menu: Menu) = {
        val item = createBoolMenuItem(menu, MenuData("Horizontal Scroll", Some(DataID.horizontalScroll)))
        addListener(item, _ =>
            boolMenuMap("vhAdjusterMode").setEnabled(item.getSelection)
        )
    }

    private def createReverseScrollMenu(menu: Menu) = {
        createBoolMenuItem(menu, MenuData("Reverse Scroll (Flip)", Some(DataID.reverseScroll)))
    }

    private def createSwapScrollMenu(menu: Menu) = {
        createBoolMenuItem(menu, MenuData("Swap Scroll (V.H)", Some(DataID.swapScroll)))
    }

    private var passModeMenuItem: MenuItem = null

    private def createPassModeMenu(menu: Menu): Unit = {
        val id = DataID.passMode
        val item = createCheckMenuItem(menu, MenuData("Pass Mode", Some(id)))
        addListener(item, makeSetBooleanEvent(id))
        passModeMenuItem = item
    }
    
    private val languageMenuMap = new MenuMap()

    private def createLanguageMenuItem(menu: Menu, text: String, id: String): Unit = {
        val data = MenuData(text, Some(id))
        createMapMenuItem(menu, data, languageMenuMap, setUILanguage)
    }

    private def createLanguageMenu(parent: Menu): Unit = {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        val add = (text: String, id: String) =>
            createLanguageMenuItem(menu, text, id)

        add("English", DataID.English)
        add("Japanese", DataID.Japanese)

        createCascadeMenuItem(parent, menu, "Language")
    }

    private def createInfoMenu(menu: Menu): Unit = {
        val item = createPushMenuItem(menu, MenuData("Info", None))
        addListener(item, _ => {
            val nameVer = s"${convLang("Name")}: $PROGRAM_NAME / ${convLang("Version")}: $PROGRAM_VERSION\n\n"
            val jVer = s"java.version: ${System.getProperty("java.version")}\n"
            val osArch = s"os.arch: ${System.getProperty("os.arch")}\n"
            val dataModel = s"sun.arch.data.model: ${System.getProperty("sun.arch.data.model")}"

            val mb = new MessageBox(systemShell, SWT.OK | SWT.ICON_INFORMATION)
            mb.setText(convLang("Info"))
            mb.setMessage(nameVer + jVer + osArch + dataModel)
            mb.open()
        })
    }

    def exitAction(e: Event): Unit = {
        trayItem.setVisible(false)
        W10Wheel.exitMessageLoop
    }

    // Before exiting without the message loop (System.exit): the icon would remain in the tray
    def hideSystemTray(): Unit = {
        if (trayItem != null) {
            trayItem.setVisible(false)
            trayItem.dispose()
        }
    }

    private def createExitMenu(menu: Menu): Unit = {
        val item = createPushMenuItem(menu, MenuData("Exit", None))
        addListener(item, exitAction)
    }

    private def createTrayItem(menu: Menu) = {
        val tray = new TrayItem(Display.getDefault.getSystemTray, SWT.NONE)
        tray.setToolTipText(getTrayText(false))
        tray.setImage(getTrayIcon(false))

        tray.addListener(SWT.MenuDetect, (e: Event) => menu.setVisible(true))
        tray.addListener(SWT.DefaultSelection, (e: Event) => Pass.toggleMode) // Double Click

        tray
    }

    private var rootMenu: Menu = null 
    private var trayItem: TrayItem = null
    
    private def isNumberMenuItem(item: MenuItem): Boolean = item.getData() match {
        case engText: String => numberMenuMap.contains(getFirstWord(engText))
        case _ => false
    }
    
    private def resetMenuText(): Unit = {
        val toList = (menu: Menu) =>
            menu.getItems.toList.filter(!isSeparator(_)).filter(!isNumberMenuItem(_))
            
        def loop(items: List[MenuItem]): Unit = items match {
            case Nil => ()
            case item :: rest => {
                item.setText(convLang(dataToEngText(item)))
                
                Option(item.getMenu()) match  {
                    case Some(cascadeMenu) => loop(toList(cascadeMenu))
                    case None => ()
                }
                
                loop(rest)
            }
        }
        
        loop(toList(rootMenu))
    }

    def setSystemTray(): Unit = {
        val menu = new Menu(systemShell, SWT.POP_UP)
        trayItem = createTrayItem(menu)

        createTriggerMenu(menu)
        createKeyboardMenu(menu)
        addSeparator(menu)

        createAccelTableMenu(menu)
        createPriorityMenu(menu)
        createNumberMenu(menu)
        createRealWheelModeMenu(menu)
        createVhAdjusterMenu(menu)
        addSeparator(menu)

        createPropertiesMenu(menu)
        addSeparator(menu)

        createCursorChangeMenu(menu)
        createHorizontalScrollMenu(menu)
        createReverseScrollMenu(menu)
        createSwapScrollMenu(menu)
        createPassModeMenu(menu)
        addSeparator(menu)

        createLanguageMenu(menu)
        createInfoMenu(menu)
        createExitMenu(menu)

        resetAllMenuItems()
        rootMenu = menu
    }

    def resetSystemTray(): Unit = {
        Display.getDefault.asyncExec(() => {
            trayItem.dispose()
            setSystemTray()
        })
    }

    private def isInvalidValue(e: Throwable) = e match {
        // NumberFormatException is an IllegalArgumentException (as toBoolean throws)
        case _: NoSuchElementException | _: IllegalArgumentException | _: scala.MatchError => true
        case _ => false
    }

    // Applies the value of name in prop. If it is missing or invalid, the default value is
    // applied instead: otherwise the value of the previous file would be kept (Reload,
    // switching properties).
    private def setOrDefault(name: String)(apply: Properties.SProperties => Unit): Unit = {
        try {
            apply(prop)
        }
        catch {
            case e if isInvalidValue(e) =>
                logger.warn(s"Invalid value: $name ($e): use default")
                apply(defaultProp)
        }
    }

    private def setNumberOfProperty(name: String, low: Int, up: Int): Unit = {
        setOrDefault(name)(p => {
            val n = p.getInt(name)
            if (n < low || n > up)
                throw new IllegalArgumentException(s"Number out of bounds: $name")

            setNumberOfName(name, n)
        })
    }

    private def setBooleanOfProperty(name: String): Unit = {
        setOrDefault(name)(p => setBooleanOfName(name, p.getBoolean(name)))
    }

    private def setEnabledMenu(menuMap: MenuMap, key: String, enabled: Boolean): Unit = {
         if (menuMap.contains(key))
            menuMap(key).setEnabled(enabled)
    }

    private def setTrigger(s: String): Unit = {
        val res = Mouse.getTrigger(s)
        logger.debug("setTrigger: " + res.name);
        val changed = res != firstTrigger
        firstTrigger = res

        setEnabledMenu(boolMenuMap, DataID.sendMiddleClick, res.isSingle)
        setEnabledMenu(boolMenuMap, DataID.draggedLock, res.isDrag)

        // With whether the trigger has changed (not on every properties load)
        EventHandler.changeTrigger(changed)
    }

    private def setStringOfProperty(name: String, setFunc: String => Unit): Unit = {
        setOrDefault(name)(p => setFunc(p.getString(name)))
    }

    private def setTriggerOfProperty: Unit = {
        setStringOfProperty(DataID.firstTrigger, setTrigger)
    }

    private def setCustomAccelOfProperty: Unit = {
        // Without a valid custom table in this file, do not keep the table of the previous file
        Accel.customDisabled = true

        try {
            val cat = prop.getIntArray(DataID.customAccelThreshold)
            val cam = prop.getDoubleArray(DataID.customAccelMultiplier)

            // The thresholds are looked up in ascending order (Windows.getNearestIndex), and a
            // multiplier that is not a number (NaN, Infinity: parsed without error) would make the
            // delta invalid
            val isAscending = cat.sliding(2).forall(p => p.length < 2 || p(0) < p(1))
            val isValidThreshold = cat.forall(_ >= 0) && isAscending
            val isValidMultiplier = cam.forall(d => java.lang.Double.isFinite(d) && d >= 0.0)

            if (cat.length != 0 && cat.length == cam.length && isValidThreshold && isValidMultiplier) {
                logger.debug(s"customAccelThreshold: ${cat.toList}")
                logger.debug(s"customAccelMultiplier: ${cam.toList}")

                Accel.customThreshold = cat
                Accel.customMultiplier = cam
                Accel.customDisabled = false
            }
        }
        catch {
            case e: NoSuchElementException => logger.debug(s"Not found: ${e.getMessage}")
            case e: NumberFormatException => logger.warn(s"Parse error: ${e.getMessage}")
        }
    }

    private def setAccelOfProperty: Unit = {
        setStringOfProperty(DataID.accelMultiplier, setAccelMultiplier)
    }

    private def setPriorityOfProperty: Unit = {
        setStringOfProperty(DataID.processPriority, setPriority)
    }

    private def setVKCodeOfProperty: Unit = {
        setStringOfProperty(DataID.targetVKCode, setTargetVKCode)
    }
    
    private def setVhAdjusterMethodOfProperty: Unit = {
        setStringOfProperty(DataID.vhAdjusterMethod, setVhAdjusterMethod)
    }
    
    private def setUILanguageOfProperty: Unit = {
        setStringOfProperty(DataID.uiLanguage, lang => {
            // Only the languages of the Language menu
            if (lang != DataID.English && lang != DataID.Japanese)
                throw new IllegalArgumentException(s"Unknown language: $lang")

            setUILanguage(lang)
        })
    }

    private val prop = new Properties.SProperties

    // The settings before the first load (the default values), written as in a properties file
    private val defaultProp = new Properties.SProperties

    // Write the settings to p (the custom accel table is not written: it is only read)
    private def setSettings(p: Properties.SProperties): Unit = {
        val set = (n: String, v: String) => p.setProperty(n, v)

        set(DataID.firstTrigger, firstTrigger.name)
        set(DataID.accelMultiplier, Accel.multiplier.name)
        set(DataID.processPriority, processPriority.name)
        set(DataID.targetVKCode, Keyboard.getName(targetVKCode))
        set(DataID.vhAdjusterMethod, VHAdjuster.method.name)
        set(DataID.uiLanguage, this.uiLanguage)

        BooleanNames.foreach(n => p.setBoolean(n, getBooleanOfName(n)))
        NumberNames.foreach(n => p.setInt(n, getNumberOfName(n)))
    }

    private def getSelectedPropertiesPath =
        Properties.getPath(selectedProperties)

    private var loaded = false

    // The default values are kept (defaultProp) once, before the first read of a properties file is
    // tried (readPropertiesOrShowError, at startup): the settings are the default values only then.
    // Not after the first read that succeeds: if the one at startup fails, the settings changed
    // before the next would be taken for the default values.
    private var defaultSaved = false

    private def saveDefaultsOnce(): Unit = {
        if (!defaultSaved) {
            setSettings(defaultProp)
            defaultSaved = true
        }
    }

    // Reads the file of the properties name: None if it cannot be read (e.g. locked by another
    // process, malformed, or deleted since the menu was shown). Default may have no file yet (it
    // is written at the first exit): its settings are the default values then.
    private def readProperties(name: String): Option[Properties.SProperties] = {
        val p = new Properties.SProperties
        try {
            p.load(Properties.getPath(name))
            Some(p)
        }
        catch {
            case _: NoSuchFileException if name == DEFAULT_DEF =>
                logger.debug("Properties file not found (Default)")
                Some(p)
            case e: Exception =>
                logger.warn(s"Cannot read the properties $name: $e")
                None
        }
    }

    // Loads the selected properties (Reload, and at startup)
    def loadProperties: Boolean =
        loadPropertiesOf(selectedProperties)

    // Loads the properties name and selects them. If their file cannot be read, nothing changes
    // (the settings, the properties selected, and whether they can be stored, see storeProperties)
    // and an error is shown: false. Otherwise a failed Reload would keep the settings but stop
    // storing them, and switching to properties deleted since would apply the default values and
    // write the file again at exit.
    private def loadPropertiesOf(name: String): Boolean = {
        readPropertiesOrShowError(name) match {
            case None => false
            case Some(p) =>
                selectProperties(name, p)
                true
        }
    }

    // readProperties, with an error shown if the file cannot be read
    private def readPropertiesOrShowError(name: String): Option[Properties.SProperties] = {
        saveDefaultsOnce()
        val res = readProperties(name)
        if (res.isEmpty)
            Dialog.errorMessage(s"${convLang("Cannot read properties")}: $name")
        res
    }

    // Selects the properties name, read in p (readProperties), and applies them
    private def selectProperties(name: String, p: Properties.SProperties): Unit = {
        // (the default values have been kept before p was read: see saveDefaultsOnce)
        selectedProperties = name

        // The keys of the previous file (Reload, switching properties) are not kept: they would be
        // applied. A missing or invalid value gets its default (setOrDefault).
        prop.clear()
        prop.putAll(p)
        loaded = true

        try {
            applyProperties()
        }
        catch {
            case e: Exception => logger.warn(s"load: ${e.toString}")
        }
    }

    private def applyProperties(): Unit = {
        setTriggerOfProperty
        setAccelOfProperty
        setCustomAccelOfProperty
        setPriorityOfProperty
        setVKCodeOfProperty
        setVhAdjusterMethodOfProperty
        setUILanguageOfProperty

        BooleanNames.foreach(n => setBooleanOfProperty(n))
        setKeyboardHookOf(keyboardHook)

        val setNum = (n: String, l: Int, u: Int) => setNumberOfProperty(n, l, u)
        setNum(DataID.pollTimeout, 50, 500)
        setNum(DataID.scrollLocktime, 150, 500)
        setNum(DataID.verticalThreshold , 0, 500)
        setNum(DataID.horizontalThreshold, 0, 500)

        setNum(DataID.wheelDelta, 10, 500)
        setNum(DataID.vWheelMove, 10, 500)
        setNum(DataID.hWheelMove, 10, 500)

        setNum(DataID.firstMinThreshold, 1, 10)
        setNum(DataID.switchingThreshold, 10, 500)

        // The scroll settings are read when scroll mode starts (Windows.initScroll): a scroll in
        // progress (Reload, switching properties) ends once they are set, so that the new ones
        // apply from the next (also one started meanwhile, e.g. during the error dialog of the
        // keyboard hook). Not if the file could not be read: the settings have not changed. The
        // keyboard trigger's has ended (setKeyboardHookOf), and the mouse triggers' if the
        // trigger changed (changeTrigger). Their trigger's up is still suppressed.
        exitScrollMode
    }

    // Whether the settings differ from the properties of the file (p)
    private def isChangedProperties(p: Properties.SProperties): Boolean = {
        logger.debug("isChangedProperties")

        try {
            def isChangedBoolean =
                BooleanNames.map(n => p.getBoolean(n) != getBooleanOfName(n)).contains(true)

            def isChangedNumber =
                NumberNames.map(n => p.getInt(n) != getNumberOfName(n)).contains(true)

            val check = (n: String, v: String) => p.getString(n) != v

            check(DataID.firstTrigger, firstTrigger.name) ||
            check(DataID.accelMultiplier, Accel.multiplier.name) ||
            check(DataID.processPriority, processPriority.name) ||
            check(DataID.targetVKCode, Keyboard.getName(targetVKCode)) ||
            check(DataID.vhAdjusterMethod, VHAdjuster.method.name) ||
            check(DataID.uiLanguage, this.uiLanguage) ||
            isChangedBoolean || isChangedNumber
        }
        catch {
            case e: NoSuchElementException => logger.warn(s"Not found: ${e.getMessage}"); true
            case e: Exception => logger.warn(s"isChanged: ${e.toString}"); true
        }
    }

    private val NumberNames: Array[String] = {
        Array(DataID.pollTimeout, DataID.scrollLocktime,
              DataID.verticalThreshold, DataID.horizontalThreshold,
              DataID.wheelDelta, DataID.vWheelMove, DataID.hWheelMove,
              DataID.firstMinThreshold, DataID.switchingThreshold)
    }

    private val BooleanNames: Array[String] = {
        Array(DataID.realWheelMode, DataID.cursorChange,
              DataID.horizontalScroll, DataID.reverseScroll,
              DataID.quickFirst, DataID.quickTurn,
              DataID.accelTable, DataID.customAccelTable,
              DataID.draggedLock, DataID.swapScroll,
              DataID.sendMiddleClick, DataID.keyboardHook,
              DataID.vhAdjusterMode, DataID.firstPreferVertical
        )
    }

    private val OnOffNames: Array[String] = {
        Array(DataID.realWheelMode, DataID.accelTable, DataID.keyboardHook, DataID.vhAdjusterMode)
    }

    private def setNumberOfName(name: String, n: Int): Unit = {
        logger.debug(s"setNumber: $name = $n")

        name match {
            case DataID.pollTimeout => pollTimeout = n
            case DataID.scrollLocktime => Scroll.locktime = n
            case DataID.verticalThreshold => Threshold.vertical = n
            case DataID.horizontalThreshold => Threshold.horizontal = n
            case DataID.wheelDelta => RealWheel.wheelDelta = n
            case DataID.vWheelMove => RealWheel.vWheelMove = n
            case DataID.hWheelMove => RealWheel.hWheelMove = n
            case DataID.firstMinThreshold => VHAdjuster.firstMinThreshold = n
            case DataID.switchingThreshold => VHAdjuster.switchingThreshold = n
        }
    }

    private def getNumberOfName(name: String): Int = name match {
        case DataID.pollTimeout => pollTimeout
        case DataID.scrollLocktime => Scroll.locktime
        case DataID.verticalThreshold => Threshold.vertical
        case DataID.horizontalThreshold => Threshold.horizontal
        case DataID.wheelDelta => RealWheel.wheelDelta
        case DataID.vWheelMove => RealWheel.vWheelMove
        case DataID.hWheelMove => RealWheel.hWheelMove
        case DataID.firstMinThreshold => VHAdjuster.firstMinThreshold
        case DataID.switchingThreshold => VHAdjuster.switchingThreshold
    }

    private def setBooleanOfName(name: String, b: Boolean) = {
        logger.debug(s"setBoolean: $name = ${b.toString}")
        name match {
            case DataID.realWheelMode => RealWheel.mode = b
            case DataID.cursorChange => Scroll.cursorChange = b
            case DataID.horizontalScroll => Scroll.horizontal = b
            case DataID.reverseScroll => Scroll.reverse = b
            case DataID.quickFirst => RealWheel.quickFirst = b
            case DataID.quickTurn => RealWheel.quickTurn = b
            case DataID.accelTable => Accel.table = b
            case DataID.customAccelTable => Accel.customTable = b
            case DataID.draggedLock => Scroll.draggedLock = b
            case DataID.swapScroll => Scroll.swap = b
            case DataID.sendMiddleClick => sendMiddleClick = b
            case DataID.keyboardHook => keyboardHook = b
            case DataID.vhAdjusterMode => VHAdjuster.mode = b
            case DataID.firstPreferVertical => VHAdjuster.firstPreferVertical = b
            case DataID.passMode => Pass.mode = b
        }
    }

    private def getBooleanOfName(name: String): Boolean = name  match {
        case DataID.realWheelMode => RealWheel.mode
        case DataID.cursorChange => Scroll.cursorChange
        case DataID.horizontalScroll => Scroll.horizontal
        case DataID.reverseScroll => Scroll.reverse
        case DataID.quickFirst => RealWheel.quickFirst
        case DataID.quickTurn => RealWheel.quickTurn
        case DataID.accelTable => Accel.table
        case DataID.customAccelTable => Accel.customTable
        case DataID.draggedLock => Scroll.draggedLock
        case DataID.swapScroll => Scroll.swap
        case DataID.sendMiddleClick => sendMiddleClick
        case DataID.keyboardHook => keyboardHook
        case DataID.vhAdjusterMode => VHAdjuster.mode
        case DataID.firstPreferVertical => VHAdjuster.firstPreferVertical
        case DataID.passMode => Pass.mode
    }

    def storeProperties: Unit = {
        logger.debug("storeProperties")

        // The settings are stored in the file as it is now (read anew): its keys that are not
        // settings (e.g. customAccelThreshold) are kept as they are, and those removed from it since
        // it was loaded are not stored again. Not stored if it cannot be read (it could not be
        // written either).
        try {
            if (!loaded)
                logger.debug("Not loaded properties")
            else {
                val path = getSelectedPropertiesPath
                val file = new Properties.SProperties
                val found =
                    try {
                        file.load(path)
                        true
                    }
                    catch {
                        case _: NoSuchFileException => logger.debug("First write properties"); false
                    }

                if (found && !isChangedProperties(file))
                    logger.debug("Not changed properties")
                else {
                    setSettings(file)
                    file.store(path)
                }
            }
        }
        catch {
            case e: Exception => logger.warn(s"store: ${e.toString}")
        }
    }

    def initState: Unit = {
        // The physical ups now pass W10Wheel: the LR trigger neither resends nor starts for a down
        // received before, and the wheel events queued are not sent. A down resent before and
        // sent after its physical up is dropped by the hook (Windows.dropResent).
        // First, as Pass Mode does: a wait timing out while it is canceled below sees the new
        // generation and does not resend its down (its flag would be reset by init, and its up
        // not resent).
        newPassModeGeneration()
        // Cancel the LR trigger wait before init: its timeout could set the flags again after it
        EventWaiter.offer(Cancel(null))
        EventHandler.initState
        KEventHandler.initState()
        LastFlags.init
        exitScrollMode
    }
}
