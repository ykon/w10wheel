package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.nio.file.NoSuchFileException
import java.io.File

import com.sun.jna.platform.win32.WinUser.{ MSLLHOOKSTRUCT => HookInfo }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

import java.util.NoSuchElementException
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

object Context {
    val PROGRAM_NAME = "W10Wheel"
    // Single source of truth is `version` in build.sbt (written to the jar manifest as Implementation-Version)
    val PROGRAM_VERSION: String =
        Option(getClass.getPackage).flatMap(p => Option(p.getImplementationVersion)).getOrElse("dev")
    val PROJECT_URL = "https://github.com/ykon/w10wheel"
    private val logger = Logger.getLogger()

    @volatile private var selectedProperties = Properties.DEFAULT_DEF // default

    // The default mouse trigger: RightDrag (easy to tell, and the left button is left as it is), as
    // in W10Wheel.NET. LR until 2.8.1: a trigger stored in the properties file is kept.
    @volatile private var firstTrigger: Trigger = RightDragTrigger() // default
    @volatile private var pollTimeout = 200 // default
    @volatile private var processPriority: Windows.Priority = Windows.AboveNormal() //default
    @volatile private var sendMiddleClick = false // default

    @volatile private var keyboardHook = false // default
    @volatile private var targetVKCode = Keyboard.getVKCode(DataID.VK_NONCONVERT) // default
    @volatile private var uiLanguage = Locale.getLanguage() // default
    // Asked at startup to restart as administrator when not running as administrator
    @volatile private var adminCheck = true // default

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

            // The icon and the menu of the tray
            Tray.update()
        }

        def toggleMode: Unit = {
            mode = !mode
        }
    }

    def isPassMode = Pass.mode
    def setPassMode(b: Boolean) = Pass.mode = b

    // The programs in whose windows the triggers do nothing (in a file of their own, see
    // applyExclusions): the full paths of their .exe in the order they were added (as the settings
    // dialog shows them), and the same paths looked up without case (as the file system compares
    // them). Both replaced as a whole, never changed: the hooks read the lookup as it is when they
    // read it.
    private object Exclusion {
        @volatile private var __programs = Vector.empty[String]
        @volatile private var __lookup = Set.empty[String]

        def programs = __programs
        def lookup = __lookup

        // The upper case of the chars as Windows gives it (RtlUpcaseUnicodeChar), asked once for
        // each char not ASCII (0: not asked yet). Read and written by the hook thread and the
        // Swing thread: a char is written whole, and always the same.
        private val upcase = new Array[Char](0x10000)

        private def upcaseOf(c: Char): Char = {
            if (c < 0x80)
                if (c >= 'a' && c <= 'z') (c - 0x20).toChar else c
            else {
                val u = upcase(c)
                if (u != 0) u
                else {
                    val v = win32ex.NtDllEx.INSTANCE.RtlUpcaseUnicodeChar(c)
                    upcase(c) = v
                    v
                }
            }
        }

        // Without case, char by char as Windows compares the names of files (two files can be
        // named "fıx.exe" and "fix.exe"): not Character.toUpperCase, which differs (e.g. "ı",
        // "ſ", "µ"), nor toUpperCase, which can change the length (e.g. "ß" to "SS")
        def keyOf(path: String): String = path.map(upcaseOf)

        // A full path is written as Windows gives the path of a process (e.g. "/" as "\", ".."):
        // a relative one (e.g. written in the file by hand) is kept as it is, it would be taken from
        // the current folder. The quotes around a path are dropped (e.g. pasted from Copy as path
        // of Explorer): no path can have one. The empty ones and those already in are dropped.
        private def normalize(path: String): String = {
            val p = path.strip.dropWhile(_ == '"').reverse.dropWhile(_ == '"').reverse.strip
            try {
                val fp = java.nio.file.Paths.get(p)
                if (fp.isAbsolute) fp.normalize.toString else p
            }
            catch {
                case _: java.nio.file.InvalidPathException => p
            }
        }

        def setPrograms(paths: Seq[String]): Unit = {
            val keys = scala.collection.mutable.LinkedHashMap.empty[String, String]
            paths.map(normalize).filter(_.nonEmpty).foreach(p => keys.getOrElseUpdate(keyOf(p), p))
            __programs = keys.values.toVector
            __lookup = keys.keySet.toSet
        }
    }

    def getExcludedPrograms: Vector[String] = Exclusion.programs

    private def setExcludedPrograms(paths: Seq[String]): Unit = {
        logger.debug(s"setExcludedPrograms: $paths")
        Exclusion.setPrograms(paths)
    }

    // No program excluded (the default): nothing is looked up, the triggers work as before
    def hasExcludedPrograms: Boolean = Exclusion.lookup.nonEmpty

    def isExcludedProgram(path: String): Boolean = Exclusion.lookup.contains(Exclusion.keyOf(path))

    // Two paths of the same program, as the list compares them (the settings dialog)
    private def isSameProgram(a: String, b: String): Boolean = Exclusion.keyOf(a) == Exclusion.keyOf(b)

    def setSelectedProperties(name: String) =
        selectedProperties = name

    def isKeyboardHook = keyboardHook
    def getTargetVKCode = targetVKCode
    def isTriggerKey(ke: KeyboardEvent) = ke.vkCode == targetVKCode
    def isNoneTriggerKey = targetVKCode == 0

    def isSendMiddleClick = sendMiddleClick
    
    def getUILanguage = uiLanguage
    
    private def setUILanguage(lang: String): Unit = {
        logger.debug(s"setUILanguage: $lang")
        val reset = uiLanguage != lang
        uiLanguage = lang

        if (reset) {
            Tray.retext()
            retextSettingsDialog()
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
        // up was missed meanwhile may have been pressed again, its down passing (no hook). The old
        // records that would suppress its repeats or its up are forgotten (the passed keys, kdP,
        // are kept: they pass). Its next event is handled as any: KBDLLHOOKSTRUCT does not tell a
        // repeat, so a repeat of the trigger key is taken for a new press (see
        // KEventHandler.hookInstalled).
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
    
    // The mouse trigger last set other than None: shown in the settings dialog while the trigger is
    // None (Mouse unchecked), and set again when Mouse is checked
    @volatile private var lastMouseTrigger: Trigger = firstTrigger

    private def setAccelMultiplier(name: String): Unit = {
        logger.debug(s"setAccelMultiplier: $name")
        Accel.multiplier = Accel.getMultiplier(name)
    }

    private def setPriority(name: String) = {
        val p = Windows.getPriority(name)
        logger.debug(s"setPriority: ${p.name}")
        processPriority = p
        Windows.setPriority(p)
    }

    private def getVhAdjusterMethod(name: String) = name match {
        case DataID.Fixed => Fixed()
        case DataID.Switching => Switching()
    }

    private def setVhAdjusterMethod(name: String) = {
        logger.debug(s"setVhAdjusterMethod: $name")
        VHAdjuster.method = getVhAdjusterMethod(name)
    }

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
                // Not installed (the previous hook removed): off, and the settings dialog shows it
                keyboardHook = false
                refreshSettingsLater()

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

    private val DEFAULT_DEF = Properties.DEFAULT_DEF

    def getSelectedProperties: String = selectedProperties

    // The characters not allowed in a Windows file name (as .NET's Path.GetInvalidFileNameChars)
    private val InvalidFileNameChars: Set[Char] = "\"<>|:*?\\/".toSet ++ (0 until 32).map(_.toChar)

    // Why a name cannot be a part of a file name (see Properties.getPath), None if it can: one
    // reason, on a line under the box of Dialog.askText. Not Default whatever its case (a name only
    // differing in case would be confused with it), nor a command (--). An empty name too.
    private def whyNotFileName(name: String): Option[String] = {
        if (name.isBlank || name.strip != name)
            Some("Spaces at either end cannot be used")
        else if (name.startsWith("--"))
            Some("Cannot start with --")
        else if (name.equalsIgnoreCase(DEFAULT_DEF))
            Some("Default cannot be used")
        else if (name.exists(InvalidFileNameChars.contains))
            Some("Contains characters not allowed in file names")
        else
            None
    }

    private def isValidPropertiesName(name: String) =
        whyNotFileName(name).isEmpty

    // Why a name cannot be used for new properties, checked as it is typed (and again after OK)
    private def whyInvalidPropertiesName(name: String): Option[String] =
        whyNotFileName(name).orElse(
            if (Properties.exists(name)) Some("Properties of this name already exist") else None)

    // Reload, --sendReloadProp: the file of the selected properties read again (true if it has
    // been). If it cannot be (e.g. locked), nothing changes: the settings stay as they are (still
    // stored, if they were loaded), and why is shown.
    private def tryReloadProperties(): Boolean =
        loadProperties

    def reloadProperties: Unit =
        tryReloadProperties()

    // The settings dialog: made when it is opened first, then kept (closing hides it). On the Swing
    // thread; the settings are changed on the hook thread (HookThread.call), which refreshes it
    // later (its refresh methods can be called from any thread).
    @volatile private var settingsDialog: SettingsDialog = null

    private def refreshSettingsDialog(): Unit =
        Option(settingsDialog).foreach(_.refreshAllLater())

    private def refreshSettingsLater(): Unit =
        Option(settingsDialog).foreach(_.refreshLater())

    private def retextSettingsDialog(): Unit =
        Option(settingsDialog).foreach(_.retextLater())

    // On the hook thread, from the Swing thread (waits for it). Not for reading or writing a file:
    // the hook thread would wait for it (e.g. a slow network drive), and Windows removes the hooks
    // that do not return in time. The settings dialog reads and writes the properties files on the
    // Swing thread (storeProperties, readProperties: they take no setting being changed), then
    // applies what it has read on the hook thread (loadRead).
    private def onHook[T](f: => T): T =
        HookThread.call(f)

    // The properties of name, read in p, selected and applied on the hook thread (their excluded
    // programs read here first, as p)
    private def loadRead(name: String, p: Properties.SProperties): Unit = {
        val exclusions = readExclusions(name)
        onHook(selectProperties(name, p, exclusions))
    }

    // Reload from the settings dialog (the file read on the Swing thread, see onHook): false if it
    // could not be read (nothing changes, why is shown)
    private def reloadFromDialog(): Boolean = {
        val name = selectedProperties
        readProperties(name) match {
            case Right(p) =>
                loadRead(name, p)
                true
            case Left(e) =>
                loadError = Some(e)
                refreshSettingsLater()
                Dialog.errorMessage(s"${convLang("The properties file could not be read")}: $name\n\n${messageOf(e)}")
                false
        }
    }

    // The name of properties as shown: only Default is translated
    private def shownPropertiesName(name: String) =
        if (name == DEFAULT_DEF) convLang(name) else name

    private def openPropertiesDir(): Unit = {
        try {
            java.awt.Desktop.getDesktop.open(new File(Properties.USER_DIR))
        }
        catch {
            case e: Exception => Dialog.errorMessageE(e)
        }
    }

    // The new properties: a copy of the file of the selected ones (their other keys, e.g. the custom
    // table) with the settings running (also those not stored), then selected and loaded as by a
    // switch. From the settings dialog (owner).
    private def addProperties(owner: java.awt.Window): Unit = {
        val res = Dialog.askText(owner, convLang("Add Properties"), convLang("Properties Name"), whyInvalidPropertiesName)

        res.foreach(name => {
            if (!isValidPropertiesName(name))
                Dialog.errorMessage(s"${convLang("Invalid Name")}: $name", "Name Error")
            else {
                val err: Option[Throwable] = {
                    try {
                        storeProperties
                        val path = Properties.getPath(name)
                        // Set once this Add has made the properties file (the copy, or the first
                        // store): not one made by another program meanwhile (the copy fails then,
                        // it is not replaced)
                        var made = false
                        try {
                            // A copy of the file (its comments, its other keys) with the settings
                            // running, those shown: also those not stored (e.g. a read-only file,
                            // properties not loaded), or edited in the file and not reloaded (the
                            // file's would be copied)
                            if (java.nio.file.Files.exists(getSelectedPropertiesPath)) {
                                Properties.copy(selectedProperties, name)
                                made = true
                            }

                            val current = currentSettings
                            if (made || java.nio.file.Files.exists(path))
                                current.update(path, current.keys)
                            else {
                                current.store(path)
                                made = true
                            }
                            // The excluded programs running too (none: the file of earlier
                            // properties of this name, deleted outside W10Wheel, is deleted)
                            Properties.writeExclusions(Properties.getExclusionsPath(name), getExcludedPrograms.toArray)
                        }
                        catch {
                            case e: Exception =>
                                // Not left half made (e.g. their excluded programs could not be
                                // written): the properties file made by this Add is deleted, they can
                                // be added again
                                if (made) {
                                    try java.nio.file.Files.deleteIfExists(path)
                                    catch { case e2: Exception => logger.warn(s"addProperties: $e2") }
                                }
                                throw e
                        }

                        readProperties(name) match {
                            case Right(p) => loadRead(name, p); None
                            case Left(e) => Some(e)
                        }
                    }
                    catch {
                        case e: Exception => Some(e)
                    }
                }
                err.foreach(e => Dialog.showError(owner, convLang("Add Properties"), e))
            }
        })
    }

    // The selected properties (not Default) deleted, then Default selected. Default is read first:
    // if it cannot be (e.g. locked), these properties are not deleted (their settings would run as
    // Default, and be written again as these properties). From the settings dialog (owner).
    private def deleteProperties(owner: java.awt.Window): Unit = {
        val name = selectedProperties
        if (Dialog.confirm(owner, convLang("Delete these properties?"), s"${convLang("Properties")}: $name",
                           convLang("Delete"))) {
            val err: Option[(String, Throwable)] = {
                readProperties(DEFAULT_DEF) match {
                    case Left(e) => Some((convLang("The properties file could not be read"), e))
                    case Right(p) =>
                        try {
                            Properties.delete(name)
                            // Not an error if it cannot be: a list left without its properties is
                            // replaced if properties of this name are added again (addProperties)
                            try java.nio.file.Files.deleteIfExists(Properties.getExclusionsPath(name))
                            catch { case e: Exception => logger.warn(s"delete exclusions: $e") }
                            loadRead(DEFAULT_DEF, p)
                            None
                        }
                        catch {
                            case e: Exception => Some((convLang("Delete"), e))
                        }
                }
            }
            err.foreach { case (heading, e) => Dialog.showError(owner, heading, e) }
        }
    }

    // The settings of the selected properties back to their default values (defaultProp), except the
    // language: written into prop (the keys that are not settings stay, e.g. the custom accel table),
    // applied as a load does, and stored. No program is excluded any more.
    private def resetSettings(owner: java.awt.Window): Unit = {
        val text = s"${convLang("The language is kept.")}\n${convLang("Properties")}: ${shownPropertiesName(selectedProperties)}"
        if (Dialog.confirm(owner, convLang("Reset all the settings to their defaults?"), text,
                           convLang("Reset to defaults"))) {
            onHook {
                // prop has the values of the last load: the current settings first, or the language
                // changed since would be set back
                setSettings(prop)
                val keys = Seq(DataID.firstTrigger, DataID.accelMultiplier, DataID.processPriority,
                               DataID.targetVKCode, DataID.vhAdjusterMethod) ++ BooleanNames ++ NumberNames
                keys.foreach(k => prop.setProperty(k, defaultProp.getProperty(k)))

                try {
                    applyProperties()
                }
                catch {
                    case e: Exception => logger.warn(s"reset: $e")
                }
                // All the settings written (not only those changed in W10Wheel: one edited in the
                // file and not reloaded would stay), the other lines of the file kept
                storedSettings = new Properties.SProperties
                // No program excluded, the default: their file deleted, also if the list was empty
                // (the file may have been made since it was read)
                setExcludedPrograms(Seq.empty)
                exclusionsRewrite = true
            }
            storeProperties
            refreshSettingsDialog()
        }
    }

    // At startup, before the lock and the load (W10Wheel.main): the file of the selected properties
    // read for the language of the dialogs shown before the load and for adminCheck (None if it
    // cannot be read: the load tells why)
    private var startupProps: Option[Properties.SProperties] = None

    def readStartupProperties(): Unit = {
        // The settings are still the default values (see saveDefaultsOnce)
        saveDefaultsOnce()
        startupProps = readProperties(selectedProperties).toOption
        startupProps.flatMap(_.getOption(DataID.uiLanguage)).foreach(lang =>
            if (lang == DataID.English || lang == DataID.Japanese) uiLanguage = lang)
    }

    // Whether to ask at startup to restart as administrator: true if missing or invalid (the default)
    def isAdminCheckAtStartup: Boolean =
        startupProps.flatMap(_.getOption(DataID.adminCheck)).forall(s => !s.equalsIgnoreCase("false"))

    // Do not ask again (at startup, before the load): adminCheck false written in the file of the
    // selected properties at once, its other lines kept. A new file has all the settings, as at a
    // first exit (their default values). Not if it cannot be written: asked again at the next
    // startup.
    def storeNoAdminCheck(): Unit = storeLock.synchronized {
        try {
            val path = getSelectedPropertiesPath
            if (java.nio.file.Files.exists(path)) {
                val file = new Properties.SProperties
                file.setBoolean(DataID.adminCheck, false)
                file.update(path, List(DataID.adminCheck))
            }
            else {
                val file = currentSettings
                file.setBoolean(DataID.adminCheck, false)
                file.store(path)
            }
        }
        catch {
            case e: Exception => logger.warn(s"storeNoAdminCheck: $e")
        }
    }

    // Set while UAC is answered (on the Swing thread): the button is grayed (pressed again, it would
    // start a second instance as administrator, which would wait for the lock and then fail)
    private var restarting = false

    // From the settings dialog (owner): W10Wheel started as administrator (with the dialog opened
    // again), then this one exits as by Exit (the hooks removed, the properties stored, the lock
    // released: the new one waits for it). UAC is waited for on another thread: the dialog is not
    // frozen meanwhile.
    private def restartAsAdmin(owner: java.awt.Window): Unit = {
        if (!restarting) {
            restarting = true
            refreshSettingsLater()
            // Stored first: the new instance may read them before this one stores them again at its
            // exit. If some could not be (e.g. a read-only file), the new one would load the file
            // without them: asked as when switching.
            val unstored = storeAndCheckUnstored()
            val go = !unstored || {
                Dialog.confirm(owner, convLang("The changes could not be saved"),
                               whyUnstored + convLang("Restarting loses the changes that could not be saved."),
                               convLang("Restart as administrator"))
            }

            if (!go) {
                restarting = false
                refreshSettingsLater()
            }
            else {
                val name = selectedProperties
                val t = new Thread(() => {
                    val res = Admin.startAsAdmin(name, openSettings = true)
                    javax.swing.SwingUtilities.invokeLater(() => res match {
                        case Admin.AdminStart.Started => exitAction()
                        case Admin.AdminStart.Canceled =>
                            restarting = false
                            refreshSettingsLater()
                        case Admin.AdminStart.Failed(e) =>
                            restarting = false
                            refreshSettingsLater()
                            Dialog.showError(owner, convLang("Restart as administrator"), e)
                    })
                }, "W10Wheel restart as administrator")
                t.setDaemon(true)
                t.start()
            }
        }
    }

    // From the settings dialog (owner). The settings of the selected properties are stored first: if
    // some could not be (e.g. a read-only file, or properties not loaded), switching loses them,
    // which is asked. The properties switched to are loaded only if they can be read: else nothing
    // changes, and why is shown.
    private def switchProperties(owner: java.awt.Window, name: String): Unit = {
        val unstored = storeAndCheckUnstored()

        val go = !unstored || {
            Dialog.confirm(owner, convLang("The changes could not be saved"),
                           whyUnstored + convLang("Switching loses the changes that could not be saved."), convLang("Switch"))
        }

        if (!go)
            // The current properties selected again in the list
            refreshSettingsDialog()
        else {
            val err = readProperties(name) match {
                case Right(p) => loadRead(name, p); None
                case Left(e) => Some(e)
            }
            err.foreach(e => {
                refreshSettingsDialog()
                Dialog.showError(owner, convLang("The properties file could not be read"), e)
            })
        }
    }

    // Not None: it is Mouse unchecked in the settings dialog
    private val triggerItems = Seq(
        DataID.LR -> s"${DataID.LR} (Left <<-->> Right)",
        DataID.Left -> s"${DataID.Left} (Left -->> Right)",
        DataID.Right -> s"${DataID.Right} (Right -->> Left)",
        DataID.Middle -> DataID.Middle, DataID.X1 -> DataID.X1, DataID.X2 -> DataID.X2,
        DataID.LeftDrag -> DataID.LeftDrag, DataID.RightDrag -> DataID.RightDrag,
        DataID.MiddleDrag -> DataID.MiddleDrag, DataID.X1Drag -> DataID.X1Drag, DataID.X2Drag -> DataID.X2Drag)

    // The mouse trigger shown in the settings dialog: the last one while it is None
    private def getShownMouseTrigger: Trigger =
        if (firstTrigger.isNone) lastMouseTrigger else firstTrigger

    // Not None: Keyboard unchecked turns the keyboard trigger off. (None, the hook without a trigger
    // key, is still read from the properties: the dialog then shows no key.)
    private val keyboardItems = Seq(
        DataID.VK_TAB -> " (Tab)", DataID.VK_PAUSE -> " (Pause)", DataID.VK_CAPITAL -> " (Caps Lock)",
        DataID.VK_CONVERT -> " (Henkan)", DataID.VK_NONCONVERT -> " (Muhenkan)",
        DataID.VK_PRIOR -> " (Page Up)", DataID.VK_NEXT -> " (Page Down)", DataID.VK_END -> " (End)",
        DataID.VK_HOME -> " (Home)", DataID.VK_SNAPSHOT -> " (Print Screen)", DataID.VK_INSERT -> " (Insert)",
        DataID.VK_DELETE -> " (Delete)", DataID.VK_LWIN -> " (Left Windows)", DataID.VK_RWIN -> " (Right Windows)",
        DataID.VK_APPS -> " (Application)", DataID.VK_NUMLOCK -> " (Number Lock)", DataID.VK_SCROLL -> " (Scroll Lock)",
        DataID.VK_LSHIFT -> " (Left Shift)", DataID.VK_RSHIFT -> " (Right Shift)",
        DataID.VK_LCONTROL -> " (Left Ctrl)", DataID.VK_RCONTROL -> " (Right Ctrl)",
        DataID.VK_LMENU -> " (Left Alt)", DataID.VK_RMENU -> " (Right Alt)"
    ).map { case (id, name) => id -> (id + name) }

    private val accelItems = Seq(
        DataID.M5 -> s"${DataID.M5} (1.0 ... 4.8)", DataID.M6 -> s"${DataID.M6} (1.2 ... 5.8)",
        DataID.M7 -> s"${DataID.M7} (1.4 ... 6.7)", DataID.M8 -> s"${DataID.M8} (1.6 ... 7.7)",
        DataID.M9 -> s"${DataID.M9} (1.8 ... 8.7)")

    // The labels of the numbers (their names in the properties file are in the tooltips)
    private def numberLabel(id: String) = id match {
        case DataID.pollTimeout => "Simultaneous press time"
        case DataID.scrollLocktime => "Scroll mode lock time"
        case DataID.verticalThreshold => "Vertical scroll threshold"
        case DataID.horizontalThreshold => "Horizontal scroll threshold"
        case DataID.wheelDelta => "Wheel delta"
        case DataID.vWheelMove => "Vertical move per notch"
        case DataID.hWheelMove => "Horizontal move per notch"
        case DataID.firstMinThreshold => "First direction threshold"
        case DataID.switchingThreshold => "Switching threshold"
        case _ => id
    }

    // A number of the custom table as in the properties file (no ".0" for a whole number)
    private def numberText(d: Double): String =
        java.math.BigDecimal.valueOf(d).stripTrailingZeros.toPlainString

    // The settings dialog (as in W10Wheel.NET). A change is applied and stored at once. Not for Pass
    // Mode (Pause, in the tray menu): it is not stored.
    private def createSettingsDialog(): SettingsDialog = {
        val dialog = new SettingsDialog(convLang)
        val owner = dialog.frame
        dialog.changed = () => {
            storeProperties
            // The settings depending on it (e.g. Dragged Lock is for a drag trigger only, the keyboard
            // hook is off again if it could not be installed)
            dialog.refreshValues()
        }

        def setBool(id: String): Boolean => Unit = b => onHook(setBooleanOfName(id, b))
        def getBool(id: String): () => Boolean = () => getBooleanOfName(id)

        // tip: the explanation of the setting (from the Readme), converted as the other texts; the
        // name of the setting in the properties file (id) is shown after it
        def addBool(page: SettingsPage, engText: String, id: String, tip: String): Unit =
            page.addCheck(engText, getBool(id), setBool(id), tip = Some(tip), key = Some(id))
        // enabled: whether the setting is used (see Windows.initScroll), else grayed
        def addBoolIf(page: SettingsPage, engText: String, id: String, enabled: () => Boolean, tip: String): Unit =
            page.addCheck(engText, getBool(id), setBool(id), enabled = Some(enabled), tip = Some(tip), key = Some(id))
        // The ranges of loadProperties
        def addNumberIf(page: SettingsPage, id: String, low: Int, up: Int, enabled: () => Boolean, tip: String,
                        unit: Option[String] = None): Unit =
            page.addNumber(numberLabel(id), low, up, () => getNumberOfName(id), n => onHook(setNumberOfName(id, n)),
                           enabled = Some(enabled), unit = unit, tip = Some(tip), key = Some(id))
        def addTime(page: SettingsPage, id: String, low: Int, up: Int, enabled: () => Boolean, tip: String): Unit =
            addNumberIf(page, id, low, up, enabled, tip, Some("ms"))

        dialog.addPropertiesLabel("Properties")
        dialog.addPropertiesComboBox(
            // Only Default is translated: the other names are the user's
            () => (DEFAULT_DEF -> convLang(DEFAULT_DEF)) +:
                  Properties.getPropFiles.toSeq.map(Properties.getUserDefName).sorted.map(n => n -> n),
            name => name == selectedProperties,
            name => switchProperties(owner, name))
        dialog.addPropertiesButton("Add", () => addProperties(owner))
        dialog.addPropertiesButton("Delete", () => deleteProperties(owner),
                                   enabled = Some(() => selectedProperties != DEFAULT_DEF))
        dialog.addPropertiesButton("Open Dir", () => openPropertiesDir())
        // The change waiting to be stored is dropped, and waits again if nothing could be loaded: not
        // stored at once (the file may still be locked)
        dialog.addPropertiesButton("Reload", () => if (!reloadFromDialog()) dialog.restorePending(),
                                   dropPending = true)
        dialog.addBottomButton("Reset to defaults", () => resetSettings(owner))
        // Instead of the hint that the changes are stored: the properties could not be read (Reload
        // again), or written (e.g. a read-only file: tried again at the next change)
        dialog.status = () => {
            if (!loaded)
                Some((NoteKind.Warning, convLang("The properties file could not be read: changes are not saved")))
            // Before a Reload that failed: the changes made after it are not stored
            else if (storeError.isDefined)
                Some((NoteKind.Warning, convLang("The changes could not be saved to the properties file")))
            // The file of the excluded programs: also seen from the other pages (e.g. Reset to
            // defaults). Before a Reload that failed too: their changes are not stored.
            else if (!exclusionsLoaded)
                Some((NoteKind.Warning, convLang("The exclusions file could not be read: changes are not saved")))
            else if (exclusionsStoreError.isDefined)
                Some((NoteKind.Warning, convLang("The changes could not be saved to the exclusions file")))
            else if (loadError.isDefined)
                // Reload: the settings are those before, still stored
                Some((NoteKind.Warning, convLang("The properties file could not be read")))
            else
                None
        }

        val general = dialog.addPage("General")
        general.addComboBox("Language", Seq(DataID.English -> "English", DataID.Japanese -> "Japanese"),
                            id => id == getUILanguage, id => onHook(setUILanguage(id)),
                            tip = Some("The language of the menu and the dialogs"), key = Some(DataID.uiLanguage))
        general.addComboBox("Priority", Seq(DataID.High -> "High", DataID.AboveNormal -> "Above Normal", DataID.Normal -> "Normal"),
                            id => Windows.getPriority(id) == processPriority, id => onHook(setPriority(id)),
                            tip = Some("The priority of the process of W10Wheel"), key = Some(DataID.processPriority))
        general.addGap()
        // Not as administrator: said, and the restart offered (as at startup). Not without the
        // launcher (e.g. sbt run): grayed.
        if (!Admin.isAdmin) {
            general.addNote("W10Wheel is not running as administrator", NoteKind.Warning)
            general.addButton("Restart as administrator", () => restartAsAdmin(owner), shield = true,
                              enabled = Some(() => !restarting && Admin.canRestart),
                              tip = Some("Windows asks for permission (User Account Control)"))
        }
        addBool(general, "Ask to restart as administrator at startup", DataID.adminCheck,
                "Asked when W10Wheel is not running as administrator")

        val trigger = dialog.addPage("Trigger")
        // A key that starts scroll mode: the hook on and a key (not None, from old properties:
        // Keyboard is shown unchecked then, and checking it sets the default key)
        val isKeyboardTrigger = () => keyboardHook && !isNoneTriggerKey
        // Mouse unchecked is the trigger None, as Keyboard unchecked is the keyboard hook off
        trigger.addCheckComboBox("Mouse", () => !isNoneTrigger,
                                 b => onHook(setTrigger(if (b) lastMouseTrigger.name else DataID.None)),
                                 triggerItems, id => Mouse.getTrigger(id) == getShownMouseTrigger, id => onHook(setTrigger(id)),
                                 tip = Some("The mouse buttons that start scroll mode\nUnchecked: no mouse trigger (the keyboard trigger only)"),
                                 key = Some(DataID.firstTrigger))
        trigger.addCheck("Send MiddleClick", getBool(DataID.sendMiddleClick), setBool(DataID.sendMiddleClick),
                         enabled = Some(() => isSingleTrigger), indent = true,
                         tip = Some("The trigger pressed with Shift, Ctrl or Alt sends a middle click (Middle, X1, X2 only)"),
                         key = Some(DataID.sendMiddleClick))
        trigger.addCheck("Dragged Lock", getBool(DataID.draggedLock), setBool(DataID.draggedLock),
                         enabled = Some(() => isDragTrigger), indent = true,
                         tip = Some("After a drag with a Drag trigger, scroll mode stays locked"),
                         key = Some(DataID.draggedLock))
        trigger.addCheckComboBox("Keyboard", isKeyboardTrigger, b => onHook {
                                     // Without a key (None, from old properties: not in the list), the
                                     // default key: Keyboard checked would do nothing
                                     if (b && isNoneTriggerKey) setTargetVKCode(DataID.VK_NONCONVERT)
                                     setBooleanOfName(DataID.keyboardHook, b)
                                     setKeyboardHookOf(b)
                                 },
                                 keyboardItems, id => Keyboard.getVKCode(id) == targetVKCode, id => onHook(setTargetVKCode(id)),
                                 tip = Some("The key that starts scroll mode\nThe keys sent by other software (autofill, macros, key remapping tools) are not triggers"),
                                 key = Some(s"${DataID.keyboardHook}, ${DataID.targetVKCode}"))
        trigger.addGap()
        // The wait for the second button: the LR, Left and Right triggers only (EventWaiter)
        addTime(trigger, DataID.pollTimeout, 50, 500, () => isDoubleTrigger,
                "How long the LR, Left and Right triggers wait for the other button")
        // The lock by a quick release: not for the drag triggers (EventHandler.dragUp), but for the
        // keyboard trigger (KEventHandler.checkExitScrollUp)
        addTime(trigger, DataID.scrollLocktime, 150, 500,
                () => isSingleTrigger || isDoubleTrigger || isKeyboardTrigger(),
                "A trigger released within this time locks scroll mode")
        // Neither trigger: nothing starts scroll mode
        trigger.addStatus(() =>
            if (isNoneTrigger && !isKeyboardTrigger()) Some((NoteKind.Warning, convLang("Scrolling is disabled")))
            else None)

        val scroll = dialog.addPage("Scroll")
        addBool(scroll, "Cursor Change", DataID.cursorChange, "Changes the cursor in scroll mode")
        addBool(scroll, "Horizontal Scroll", DataID.horizontalScroll, "Scrolls horizontally too\nDisable it if you do not use it")
        addBool(scroll, "Reverse Scroll (Flip)", DataID.reverseScroll, "Reverses the direction of the scroll")
        addBool(scroll, "Swap Scroll (V.H)", DataID.swapScroll, "Swaps the vertical and the horizontal scroll")
        scroll.addGap()
        // The thresholds of the standard scroll: not with VH Adjuster (Windows.initStdMode)
        addNumberIf(scroll, DataID.verticalThreshold, 0, 500, () => !isVhAdjusterMode,
                    "A vertical move up to this does not scroll")
        addNumberIf(scroll, DataID.horizontalThreshold, 0, 500, () => isHorizontalScroll && !isVhAdjusterMode,
                    "A horizontal move up to this does not scroll\nToo small, the vertical scroll becomes hard to use")
        scroll.addNote("The thresholds are not used while VH Adjuster is enabled")

        // Not with Real Wheel Mode: its wheel events are not accelerated (Windows.sendRealVWheel)
        val accel = dialog.addPage("Accel Table")
        val accelUsed = () => !isRealWheelMode
        val customUsed = () => !Accel.customDisabled && Accel.customTable
        addBoolIf(accel, "Enabled", DataID.accelTable, accelUsed,
                  "Accelerates the scroll by the speed of the move, with the multipliers of the table")
        // The multipliers are not used with the custom table (getAccelMultiplier)
        accel.addRadioButtons(accelItems, id => id == Accel.multiplier.name, id => onHook(setAccelMultiplier(id)),
                              enabled = Some(() => accelUsed() && Accel.table && !customUsed()),
                              tip = Some("The multipliers of the table"), key = Some(DataID.accelMultiplier))
        accel.addCheck("Custom Table", getBool(DataID.customAccelTable), setBool(DataID.customAccelTable),
                       enabled = Some(() => accelUsed() && Accel.table && !Accel.customDisabled),
                       tip = Some("Uses customAccelThreshold and customAccelMultiplier of the properties file (only when they are valid)"),
                       key = Some(DataID.customAccelTable))
        // The custom table as read from the properties: why it cannot be checked (a warning if it is
        // not valid, else a plain text: the info icon is for the note of the page)
        accel.addStatus(() => customTableState match {
            case CustomTableState.NotWritten => Some((NoteKind.Plain, convLang("Not in the properties file")))
            case CustomTableState.Invalid(why) => Some((NoteKind.Warning, convLang(why)))
            case CustomTableState.Loaded(t, m) =>
                val args = Seq(t.length.toString, t.head.toString, t.last.toString, numberText(m.head), numberText(m.last))
                val text = args.zipWithIndex.foldLeft(convLang("Loaded: {0} steps (thresholds {1} ... {2}, multipliers {3} ... {4})")) {
                    case (s, (a, i)) => s.replace(s"{$i}", a)
                }
                Some((NoteKind.Plain, text))
        }, indent = true)
        accel.addNote("Not used while Real Wheel Mode is enabled")

        val realWheel = dialog.addPage("Real Wheel Mode")
        addBool(realWheel, "Enabled", DataID.realWheelMode,
                "Scrolls as a real wheel does, a notch at a time\nThe accel table is not used")
        realWheel.addGap()
        addNumberIf(realWheel, DataID.wheelDelta, 10, 500, () => isRealWheelMode,
                    "The wheel value of a notch (a usual mouse sends 120)")
        addNumberIf(realWheel, DataID.vWheelMove, 10, 500, () => isRealWheelMode,
                    "The vertical move made into a notch")
        // With Horizontal Scroll only (Windows.sendRealHWheel)
        addNumberIf(realWheel, DataID.hWheelMove, 10, 500, () => isRealWheelMode && isHorizontalScroll,
                    "The horizontal move made into a notch")
        realWheel.addGap()
        addBoolIf(realWheel, "Quick first response", DataID.quickFirst, () => isRealWheelMode,
                  "Sends the first notch whatever the move (else after half of the move)")
        addBoolIf(realWheel, "Quick turn response", DataID.quickTurn, () => isRealWheelMode,
                  "Sends a notch at once when the move turns back, whatever the move")

        // Used with Horizontal Scroll only (isVhAdjusterMode)
        val vhAdjuster = dialog.addPage("VH Adjuster")
        addBoolIf(vhAdjuster, "Enabled", DataID.vhAdjusterMode, () => Scroll.horizontal,
                  "Scrolls in one direction at a time, vertical or horizontal")
        vhAdjuster.addRadioButtons(Seq(DataID.Fixed -> "Fixed", DataID.Switching -> "Switching"),
                                   id => getVhAdjusterMethod(id) == VHAdjuster.method, id => onHook(setVhAdjusterMethod(id)),
                                   enabled = Some(() => isVhAdjusterMode),
                                   tip = Some("Fixed: the direction of the larger first move\nSwitching: also switched by a move above the switching threshold"),
                                   key = Some(DataID.vhAdjusterMethod))
        vhAdjuster.addGap()
        addBoolIf(vhAdjuster, "Prefer vertical first", DataID.firstPreferVertical, () => isVhAdjusterMode,
                  "The first direction prefers vertical")
        addNumberIf(vhAdjuster, DataID.firstMinThreshold, 1, 10, () => isVhAdjusterMode,
                    "The first direction is decided by a move above this (smaller moves are ignored)")
        // For Switching only (Windows.switchVHD)
        addNumberIf(vhAdjuster, DataID.switchingThreshold, 10, 500, () => isVhAdjusterMode && isVhAdjusterSwitching,
                    "A move above this switches the direction (Switching)")
        vhAdjuster.addNote("Used when Horizontal Scroll is enabled")

        val excluded = dialog.addPage("Excluded Programs")
        excluded.addList(() => getExcludedPrograms, items => onHook(setExcludedPrograms(items)),
                         () => askExcludedPrograms(owner), "Add...", "Remove", isSameProgram,
                         tip = Some("The programs (.exe) whose windows have scrolling disabled\nJudged by the window under the cursor when a trigger is pressed\n(UWP / Store apps are not supported)"))
        excluded.addNote("Scrolling is disabled in these programs")

        dialog
    }

    // The program (.exe) of a file chosen: its full path (normalized, as the list keeps it), a
    // shortcut taken for its target (through a shortcut to a shortcut). None if it is not one
    // (e.g. a shortcut to a document or to nothing, as a Store app).
    private def programOf(file: java.io.File): Option[String] = {
        val fsv = javax.swing.filechooser.FileSystemView.getFileSystemView
        def isShortcut(f: java.io.File) = f.getName.toLowerCase(java.util.Locale.ROOT).endsWith(".lnk")
        def target(f: java.io.File, depth: Int): Option[java.io.File] = {
            if (!isShortcut(f))
                Some(f)
            else if (depth == 0)
                None
            else {
                // The shell only for a shortcut (not every program)
                try Option(fsv.getLinkLocation(f)).flatMap(target(_, depth - 1))
                catch {
                    case e: Exception =>
                        logger.warn(s"programOf: $f: $e")
                        None
                }
            }
        }
        target(file, 8)
            .filter(f => f.isFile && f.getName.toLowerCase(java.util.Locale.ROOT).endsWith(".exe"))
            .map(_.toPath.toAbsolutePath.normalize.toString)
    }

    // The programs to exclude chosen in a file dialog modal to owner (the settings dialog): their
    // full paths, none if canceled. The dialog of Windows (FileDialog): "*.exe" in the name shows
    // only the programs (also in the other folders), but another name typed (e.g. "*") shows all
    // the files, so the files chosen that are not programs are told and not added. A shortcut is
    // taken for its target: the dialog does not when more than one is chosen.
    private def askExcludedPrograms(owner: java.awt.Frame): Seq[String] = {
        val d = new java.awt.FileDialog(owner, convLang("Select programs to exclude"), java.awt.FileDialog.LOAD)
        val chosen = try {
            d.setMultipleMode(true)
            d.setFile("*.exe")
            d.setVisible(true)
            d.getFiles.toSeq
        }
        finally {
            d.dispose()
        }

        val programs = chosen.map(f => f -> programOf(f))
        val others = programs.collect { case (f, None) => f.getAbsolutePath }
        // The first ones only: the message box would be taller than the screen (e.g. all the
        // files of a folder chosen, shown by a name typed as "*")
        val shown = 10
        if (others.nonEmpty) {
            val more = others.size - shown
            val lines = others.take(shown) ++
                (if (more > 0) Seq(convLang("... and {0} more").replace("{0}", more.toString)) else Seq.empty)
            Dialog.showWarning(owner, convLang("These files are not programs (.exe): they were not added"),
                               lines.mkString("\n"))
        }
        programs.flatMap(_._2).distinct
    }

    // From the tray menu, on the Swing thread
    def showSettingsDialog(): Unit = {
        if (settingsDialog == null) {
            settingsDialog = createSettingsDialog()
            // The message boxes are modal to it while it is shown
            Dialog.setDefaultOwner(() => Option(settingsDialog).filter(_.isVisible).map(_.frame))
        }
        settingsDialog.show()
    }

    // At exit, on the Swing thread: a number being typed, or waiting to be stored, is set (stored at
    // exit with the other settings)
    def commitSettingsDialog(): Unit =
        Option(settingsDialog).foreach(_.commitAll())

    // Exit (the tray menu, --sendExit): the message loop of the hook thread ends, then W10Wheel exits
    // (W10Wheel.main). From any thread.
    def exitAction(): Unit =
        HookThread.quit()

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

    private def setTrigger(s: String): Unit = {
        val res = Mouse.getTrigger(s)
        logger.debug("setTrigger: " + res.name);
        val changed = res != firstTrigger
        firstTrigger = res
        if (!res.isNone) lastMouseTrigger = res

        // With whether the trigger has changed (not on every properties load)
        EventHandler.changeTrigger(changed)
    }

    private def setStringOfProperty(name: String, setFunc: String => Unit): Unit = {
        setOrDefault(name)(p => setFunc(p.getString(name)))
    }

    private def setTriggerOfProperty: Unit = {
        setStringOfProperty(DataID.firstTrigger, setTrigger)
    }

    // The custom accel table as read from the properties (shown in the settings dialog)
    private enum CustomTableState {
        case NotWritten
        case Invalid(why: String)
        case Loaded(threshold: Array[Int], multiplier: Array[Double])
    }

    @volatile private var customTableState: CustomTableState = CustomTableState.NotWritten

    private def setCustomAccelOfProperty: Unit = {
        // Without a valid custom table in this file, do not keep the table of the previous file
        Accel.customDisabled = true
        customTableState = CustomTableState.NotWritten

        val hasT = prop.containsKey(DataID.customAccelThreshold)
        val hasM = prop.containsKey(DataID.customAccelMultiplier)
        if (hasT != hasM)
            customTableState = CustomTableState.Invalid("Not used: customAccelThreshold and customAccelMultiplier are both needed")
        else if (hasT) {
            try {
                val cat = prop.getIntArray(DataID.customAccelThreshold)
                val cam = prop.getDoubleArray(DataID.customAccelMultiplier)

                // The thresholds are looked up in ascending order (Windows.getNearestIndex), and a
                // multiplier that is not a number (NaN, Infinity: parsed without error) would make
                // the delta invalid
                val isAscending = cat.sliding(2).forall(p => p.length < 2 || p(0) < p(1))
                val isValidThreshold = cat.forall(_ >= 0) && isAscending
                val isValidMultiplier = cam.forall(d => java.lang.Double.isFinite(d) && d >= 0.0)

                if (cat.length == 0 && cam.length == 0)
                    // Written without values (e.g. customAccelThreshold=,): shown as not written
                    // (the state set above), not as numbers that differ
                    ()
                else if (cat.length != cam.length)
                    customTableState = CustomTableState.Invalid("Not used: the numbers of thresholds and multipliers differ")
                else if (!isValidThreshold)
                    customTableState = CustomTableState.Invalid("Not used: the thresholds must be ascending numbers of 0 or more")
                else if (!isValidMultiplier)
                    customTableState = CustomTableState.Invalid("Not used: the multipliers must be numbers of 0 or more")
                else {
                    logger.debug(s"customAccelThreshold: ${cat.toList}")
                    logger.debug(s"customAccelMultiplier: ${cam.toList}")

                    Accel.customThreshold = cat
                    Accel.customMultiplier = cam
                    Accel.customDisabled = false
                    customTableState = CustomTableState.Loaded(cat, cam)
                }
            }
            catch {
                case e: NoSuchElementException =>
                    logger.debug(s"Not found: ${e.getMessage}")
                case e: NumberFormatException =>
                    logger.warn(s"Parse error: ${e.getMessage}")
                    customTableState = CustomTableState.Invalid("Not used: a value is not a number")
            }
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

    @volatile private var loaded = false

    // Why the properties could not be read (the last load), or the settings stored (the last
    // time): told in the settings dialog. Cleared by a load that succeeds.
    @volatile private var loadError: Option[Throwable] = None
    @volatile private var storeError: Option[Throwable] = None

    // The message of an error as shown (some have none)
    private def messageOf(e: Throwable): String =
        Option(e.getMessage).getOrElse(e.toString)

    // The settings as they were loaded, or last stored: only those changed since are stored
    // (storeProperties); those not stored (e.g. the file could not be written) are lost by a switch,
    // which is asked
    @volatile private var storedSettings = new Properties.SProperties

    // The properties files are written (storeProperties, storeNoAdminCheck), and read and selected
    // by the hook thread (loadPropertiesOf, selectProperties), under it: written on the Swing thread
    // (the settings dialog) or the main thread (at exit). The hook thread waits for a write only if
    // a load comes during it (e.g. --sendReloadProp while a setting is stored). The settings dialog
    // reads on the Swing thread, which is the one writing: nothing is written between its read and
    // the selection.
    private val storeLock = new Object

    private def currentSettings: Properties.SProperties = {
        val p = new Properties.SProperties
        setSettings(p)
        p
    }

    // The settings changed since they were loaded or stored (current: as running)
    private def changedSettings(current: Properties.SProperties): List[String] =
        current.keys.filter(k => current.getOption(k) != storedSettings.getOption(k))

    private def hasUnstoredSettings: Boolean =
        changedSettings(currentSettings).nonEmpty

    // The settings, or the excluded programs
    private def hasUnstoredChanges: Boolean =
        hasUnstoredSettings || exclusionsUnstored

    // Why the changes left are not stored (a line, empty if not known): of the settings first if
    // some are left, else of the excluded programs (an error of the properties file may be older,
    // e.g. a Reload that failed)
    private def whyUnstored: String = {
        val ofSettings = storeError.orElse(loadError)
        val ofExclusions = exclusionsStoreError.orElse(exclusionsLoadError)
        val why = if (hasUnstoredSettings) ofSettings.orElse(ofExclusions) else ofExclusions.orElse(ofSettings)
        why.map(e => s"${messageOf(e)}\n").getOrElse("")
    }

    // The excluded programs, in a file of their own (Properties.getExclusionsPath), as they were read
    // from it, or last stored: written when they differ (storeExclusions). If the file could not be
    // read (exclusionsLoaded false, e.g. locked by another program): none (kept at a Reload, see
    // applyExclusions), and their changes are not stored (they would replace the file), which the
    // settings dialog tells. Under the lock of the stores (storeLock), as the settings.
    @volatile private var exclusionsLoaded = false
    @volatile private var storedExclusions = Vector.empty[String]
    @volatile private var exclusionsLoadError: Option[Throwable] = None
    @volatile private var exclusionsStoreError: Option[Throwable] = None
    // Set by Reset to defaults: the file is written (deleted) even if the list is as it was read, as
    // the properties file is: it may have been edited or made outside W10Wheel since. Reset when it
    // has been, or when other properties are loaded.
    @volatile private var exclusionsRewrite = false

    // The properties whose excluded programs run (applyExclusions), None before the first load
    @volatile private var exclusionsName: Option[String] = None

    // The excluded programs of the properties name read (on the thread that reads their properties
    // file, see onHook): why if they cannot be
    private def readExclusions(name: String): Either[Throwable, Array[String]] = {
        try {
            Right(Properties.readExclusions(Properties.getExclusionsPath(name)))
        }
        catch {
            case e: Exception =>
                logger.warn(s"Cannot read the exclusions of $name: $e")
                Left(e)
        }
    }

    // With the properties of name (selectProperties: they are loaded), read (readExclusions). If the
    // file could not be read (e.g. being written by an editor, or scanned), at a Reload (the same
    // properties): the list running is kept, as Reload keeps the settings when the properties file
    // cannot be read (they were excluded until then), and its changes are not stored until the file
    // is read again (as stored: the same). Else none (startup, a switch: those running are of other
    // properties).
    private def applyExclusions(name: String, read: Either[Throwable, Array[String]]): Unit = {
        read match {
            case Right(paths) =>
                exclusionsStoreError = None
                exclusionsRewrite = false
                setExcludedPrograms(paths.toSeq)
                exclusionsLoaded = true
                exclusionsLoadError = None
                // As they run (setExcludedPrograms drops the duplicates): not written again until
                // changed
                storedExclusions = getExcludedPrograms
            case Left(e) =>
                exclusionsLoaded = false
                exclusionsLoadError = Some(e)
                if (!exclusionsName.contains(name)) {
                    exclusionsStoreError = None
                    exclusionsRewrite = false
                    setExcludedPrograms(Seq.empty)
                    storedExclusions = Vector.empty
                }
        }
        exclusionsName = Some(name)
    }

    // The changes of the excluded programs are not stored yet (or could not be)
    private def exclusionsUnstored: Boolean =
        exclusionsRewrite || getExcludedPrograms != storedExclusions

    // With the settings (storeProperties), for the properties of name: only if they have been loaded
    // and have changed. The whole file written, or deleted if there is none.
    private def storeExclusions(name: String): Unit = {
        val current = getExcludedPrograms
        if (exclusionsLoaded && exclusionsUnstored) {
            try {
                Properties.writeExclusions(Properties.getExclusionsPath(name), current.toArray)
                storedExclusions = current
                exclusionsRewrite = false
                exclusionsStoreError = None
            }
            catch {
                case e: Exception =>
                    exclusionsStoreError = Some(e)
                    logger.warn(s"storeExclusions: $e")
            }
        }
        else if (exclusionsLoaded)
            // As stored (e.g. a change that could not be stored, undone): nothing is left not stored
            exclusionsStoreError = None
    }

    // Before leaving the selected properties (a switch, the restart as administrator), from the
    // settings dialog: stores the settings, then tells whether some are left not stored. Under one
    // lock: a load in between (--sendReloadProp, see loadPropertiesOf) would be taken for changes
    // not stored (the settings applied, not yet the stored ones).
    private def storeAndCheckUnstored(): Boolean = storeLock.synchronized {
        storeProperties
        hasUnstoredChanges
    }

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

    // Reads the file of the properties name: why if it cannot be read (e.g. locked by another
    // process, malformed, or deleted outside W10Wheel). Default may have no file yet (it is written
    // at the first exit): its settings are the default values then.
    private def readProperties(name: String): Either[Throwable, Properties.SProperties] = {
        val p = new Properties.SProperties
        val path = Properties.getPath(name)
        try {
            p.load(path)
            Right(p)
        }
        catch {
            case _: NoSuchFileException if name == DEFAULT_DEF =>
                logger.debug("Properties file not found (Default)")
                Right(p)
            case _: NoSuchFileException =>
                logger.warn(s"Properties not found: $name")
                Left(new NoSuchFileException(path.toString, null, s"${convLang("Properties does not exist")}: $name"))
            case e: Exception =>
                logger.warn(s"Cannot read the properties $name: $e")
                Left(e)
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
    //
    // On the hook thread (at startup, --sendReloadProp): the file is read and applied under the lock
    // of the stores (see storeLock). Read before, it could be read before a store in progress on the
    // Swing thread had written it, and its older settings applied after (the store's would run
    // until the next load, and be written over by the next store).
    private def loadPropertiesOf(name: String): Boolean = storeLock.synchronized {
        readPropertiesOrShowError(name) match {
            case None =>
                // At startup: the settings running (the default values), those changed from now on
                // are not stored
                if (!loaded) storedSettings = currentSettings
                refreshSettingsLater()
                false
            case Some(p) =>
                selectProperties(name, p, readExclusions(name))
                true
        }
    }

    // readProperties, with an error shown if the file cannot be read
    private def readPropertiesOrShowError(name: String): Option[Properties.SProperties] = {
        saveDefaultsOnce()
        readProperties(name) match {
            case Right(p) => Some(p)
            case Left(e) =>
                loadError = Some(e)
                Dialog.errorMessage(s"${convLang("The properties file could not be read")}: $name\n\n${messageOf(e)}")
                None
        }
    }

    // Selects the properties name, read in p (readProperties), and applies them, with their excluded
    // programs read (readExclusions, see applyExclusions)
    // Under the lock of the stores: a store on the Swing thread (the settings dialog) does not write
    // the settings of other properties, or a half selected state
    private def selectProperties(name: String, p: Properties.SProperties,
                                 exclusions: Either[Throwable, Array[String]]): Unit = storeLock.synchronized {
        // (the default values have been kept before p was read: see saveDefaultsOnce)
        selectedProperties = name

        // The keys of the previous file (Reload, switching properties) are not kept: they would be
        // applied. A missing or invalid value gets its default (setOrDefault).
        prop.copyFrom(p)
        loaded = true
        loadError = None
        storeError = None

        try {
            applyProperties()
        }
        catch {
            case e: Exception => logger.warn(s"load: ${e.toString}")
        }
        storedSettings = currentSettings
        applyExclusions(name, exclusions)

        refreshSettingsDialog()
        // The tip of the tray icon has the name of the properties
        Tray.update()
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
              DataID.vhAdjusterMode, DataID.firstPreferVertical,
              DataID.adminCheck
        )
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
            case DataID.adminCheck => adminCheck = b
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
        case DataID.adminCheck => adminCheck
        case DataID.passMode => Pass.mode
    }

    def storeProperties: Unit = storeLock.synchronized {
        logger.debug("storeProperties")

        // Only the settings changed since they were loaded (or stored) are written, in the file as it
        // is now: what was edited in it meanwhile (other settings too), its other keys (e.g.
        // customAccelThreshold), its comments and the order of its lines are kept. A setting changed
        // in both is written. The file of Default not found is written with all the settings (the
        // first store). Other properties not found have been renamed or deleted outside W10Wheel
        // since they were loaded: not written again (they would come back under the old name); if
        // settings have changed, they are not stored, which is told. Not stored if the properties
        // could not be loaded (the settings would be those of other properties, or defaults). The
        // excluded programs are stored too, in their file (storeExclusions), whether the settings
        // could be written or not; not for other properties whose file is not found (their list
        // would remain without them).
        try {
            if (!loaded)
                logger.debug("Not loaded properties")
            else {
                val name = selectedProperties
                val path = getSelectedPropertiesPath
                val current = currentSettings
                val changed = changedSettings(current)
                val exists = java.nio.file.Files.exists(path)

                if (exists || name == DEFAULT_DEF)
                    storeExclusions(name)

                if (!exists && name != DEFAULT_DEF) {
                    def notFound() =
                        new NoSuchFileException(path.toString, null, s"${convLang("Properties does not exist")}: $name")
                    // The excluded programs changed are not stored either (not for these
                    // properties): told as their error, also if only they have changed (none if
                    // they are as stored)
                    exclusionsStoreError = if (exclusionsUnstored) Some(notFound()) else None
                    if (changed.nonEmpty)
                        throw notFound()
                    logger.debug("Properties file not found, not changed")
                }
                else if (!exists) {
                    logger.debug("First write properties")
                    current.store(path)
                }
                else if (changed.isEmpty)
                    logger.debug("Not changed properties")
                else
                    current.update(path, changed)

                storedSettings = current
                storeError = None
            }
        }
        catch {
            case e: Exception =>
                storeError = Some(e)
                logger.warn(s"store: ${e.toString}")
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
