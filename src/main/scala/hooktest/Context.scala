package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

// SWT
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets._
import org.eclipse.swt.events._
import org.eclipse.swt.graphics.Image

//implicit
import scala.language.implicitConversions

import java.io.FileNotFoundException
import java.io.{ File, FileInputStream, FileOutputStream }
import java.io.IOException

import win32ex.{ MSLLHOOKSTRUCT => HookInfo }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

import scala.collection.mutable.HashMap
import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicBoolean

object Context {
    val PROGRAM_NAME = "W10Wheel"
    val PROGRAM_VERSION = "2.6.1"
    val ICON_RUN_NAME = "TrayIcon-Run.png"
    val ICON_STOP_NAME = "TrayIcon-Stop.png"
    val logger = Logger(LoggerFactory.getLogger(PROGRAM_NAME))
    lazy val systemShell = W10Wheel.shell

    @volatile private var selectedProperties = Properties.DEFAULT_DEF // default

    @volatile private var firstTrigger: Trigger = LRTrigger() // default
    @volatile private var pollTimeout = 200 // default
    @volatile private var processPriority: Windows.Priority = Windows.AboveNormal() //default
    @volatile private var sendMiddleClick = false // default

    @volatile private var keyboardHook = false // default
    @volatile private var targetVKCode = Keyboard.getVKCode("VK_NONCONVERT") // default

    private def getImage(name: String) = {
        val stream = getClass.getClassLoader.getResourceAsStream(name)
        new Image(Display.getDefault, stream)
    }

    private def getTrayIcon(b: Boolean): Image = {
        getImage(if (b) ICON_STOP_NAME else ICON_RUN_NAME)
    }

    private def getTrayText(b: Boolean) = {
        s"$PROGRAM_NAME - ${if (b) "Stopped" else "Runnable"}"
    }

    private def changeTrayMode(b: Boolean) {
        trayItem.setToolTipText(getTrayText(b))
        trayItem.setImage(getTrayIcon(b))
    }

    private object Pass {
        @volatile private var __mode = false
        def mode = __mode
        def mode_= (b: Boolean) {
            __mode = b
            changeTrayMode(b)
            passModeMenuItem.setSelection(b)
        }

        def toggleMode {
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

    trait VHAdjusterMethod {
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

    def isVhAdjusterMode = VHAdjuster.mode
    def isVhAdjusterSwitching = VHAdjuster.method == Switching()
    def isFirstPreferVertical = VHAdjuster.firstPreferVertical
    def getFirstMinThreshold = VHAdjuster.firstMinThreshold
    def getSwitchingThreshold = VHAdjuster.switchingThreshold

    private object Accel {
        // MouseWorks by Kensington (TD, M5, M6, M7, M8, M9)
        // http://www.nanayojapan.co.jp/support/help/tmh00017.htm

        val TD = Array(1, 2, 3, 5, 7, 10, 14, 20, 30, 43, 63, 91)

        abstract class Multiplier(val name: String, val dArray: Array[Double])

        case class M5() extends Multiplier("M5", Array(1.0, 1.3, 1.7, 2.0, 2.4, 2.7, 3.1, 3.4, 3.8, 4.1, 4.5, 4.8))
        case class M6() extends Multiplier("M6", Array(1.2, 1.6, 2.0, 2.4, 2.8, 3.3, 3.7, 4.1, 4.5, 4.9, 5.4, 5.8))
        case class M7() extends Multiplier("M7", Array(1.4, 1.8, 2.3, 2.8, 3.3, 3.8, 4.3, 4.8, 5.3, 5.8, 6.3, 6.7))
        case class M8() extends Multiplier("M8", Array(1.6, 2.1, 2.7, 3.2, 3.8, 4.4, 4.9, 5.5, 6.0, 6.6, 7.2, 7.7))
        case class M9() extends Multiplier("M9", Array(1.8, 2.4, 3.0, 3.6, 4.3, 4.9, 5.5, 6.2, 6.8, 7.4, 8.1, 8.7))

        @volatile var customDisabled = true
        @volatile var customTable = false
        @volatile var customThreshold: Array[Int] = null
        @volatile var customMultiplier: Array[Double] = null

        @volatile var table = true // default
        @volatile var threshold: Array[Int] = TD // default
        @volatile var multiplier: Multiplier = M5() // default

        def getMultiplier(name: String) = name match {
            case "M5" => M5()
            case "M6" => M6()
            case "M7" => M7()
            case "M8" => M8()
            case "M9" => M9()
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

        private def setStartPoint(x: Int, y: Int) {
            sx = x;
            sy = y;
        }

        def start(info: HookInfo) = synchronized {
            stime = info.time
            setStartPoint(info.pt.x, info.pt.y)
            Windows.initScroll

            Windows.registerRawInput

            if (cursorChange && !firstTrigger.isDrag)
                Windows.changeCursorV

            mode = true
            starting = false
        }

        def start(kinfo: KHookInfo) = synchronized {
            stime = kinfo.time
            val pt = Windows.getCursorPos
            setStartPoint(pt.x, pt.y)
            Windows.initScroll

            Windows.registerRawInput

            if (cursorChange)
                Windows.changeCursorV

            mode = true
            starting = false
        }

        def exit = synchronized {
            Windows.unregisterRawInput

            mode = false
            releasedMode = false

            if (cursorChange)
                Windows.restoreCursor
        }

        def checkExit(time: Int) = {
            val dt = time - stime
            logger.debug(s"scroll time: $dt ms")
            dt > locktime
        }

        def isMode = mode
        def getStartTime = stime
        def getStartPoint = (sx, sy)

        def setStarting = synchronized {
            starting = !mode
        }

        def isStarting = starting
    }

    def startScrollMode(info: HookInfo) = Scroll.start(info)
    def startScrollMode(kinfo: KHookInfo) = Scroll.start(kinfo)

    def exitScrollMode = Scroll.exit
    def isScrollMode = Scroll.isMode
    def getScrollStartPoint = Scroll.getStartPoint
    def checkExitScroll(time: Int) = Scroll.checkExit(time)
    def isReverseScroll = Scroll.reverse
    def isHorizontalScroll = Scroll.horizontal
    def isDraggedLock = Scroll.draggedLock
    def isSwapScroll = Scroll.swap

    def isReleasedScrollMode = Scroll.releasedMode
    def isPressedScrollMode = Scroll.isMode && !Scroll.releasedMode
    def setReleasedScrollMode = Scroll.releasedMode = true

    def setStartingScrollMode = Scroll.setStarting
    def isStartingScrollMode = Scroll.isStarting

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
        private val sdS = new AtomicBoolean(false)

        private val kdS = (0 until 256).map(_ => new AtomicBoolean(false));

        def init {
            Array(ldR, rdR, ldP, rdP, ldS, rdS, sdS).foreach(f => f.set(false))
            kdS.foreach(f => f.set(false))
        }

        def setResent(down: MouseEvent): Unit = down match {
            case LeftDown(_) => ldR.set(true)
            case RightDown(_) => rdR.set(true)
        }

        def getAndReset_ResentDown(up: MouseEvent): Boolean = up match {
            case LeftUp(_) => ldR.getAndSet(false)
            case RightUp(_) => rdR.getAndSet(false)
        }

        def setPassed(down: MouseEvent): Unit = down match {
            case LeftDown(_) => ldP.set(true)
            case RightDown(_) => rdP.set(true)
        }

        def getAndReset_PassedDown(up: MouseEvent): Boolean = up match {
            case LeftUp(_) => ldP.getAndSet(false)
            case RightUp(_) => rdP.getAndSet(false)
        }

        def setSuppressed(down: MouseEvent): Unit = down match {
            case LeftDown(_) => ldS.set(true)
            case RightDown(_) => rdS.set(true)
            case MiddleDown(_) | X1Down(_) | X2Down(_) => sdS.set(true)
        }

        def setSuppressed(down: KeyboardEvent): Unit = down match {
            case KeyDown(_) => kdS(down.vkCode).set(true)
        }

        def getAndReset_SuppressedDown(up: MouseEvent): Boolean = up match {
            case LeftUp(_) => ldS.getAndSet(false)
            case RightUp(_) => rdS.getAndSet(false)
            case MiddleUp(_) | X1Up(_) | X2Up(_) => sdS.getAndSet(false)
        }

        def getAndReset_SuppressedDown(up: KeyboardEvent): Boolean = up match {
            case KeyUp(_) => kdS(up.vkCode).getAndSet(false)
        }

        def resetLR(down: MouseEvent): Unit = down match {
            case LeftDown(_) => ldR.set(false); ldS.set(false); ldP.set(false)
            case RightDown(_) => rdR.set(false); rdS.set(false); rdP.set(false)
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
    }

    def isSingleTrigger = firstTrigger.isSingle
    def isDoubleTrigger = firstTrigger.isDouble
    def isDragTrigger = firstTrigger.isDrag
    def isNoneTrigger = firstTrigger.isNone

    private def resetMapMenuItems(map: HashMap[String, MenuItem], pred: String => Boolean) {
        logger.debug("resetMapMenuItems")
        map.foreach { case (name, item) =>
            item.setSelection(pred(name))
        }
    }

    private def resetTriggerMenuItems {
        resetMapMenuItems(triggerMenuMap, (name => Mouse.getTrigger(name) == firstTrigger))
    }

    private def resetAccelMenuItems {
        resetMapMenuItems(accelMenuMap, (name => Accel.getMultiplier(name) == Accel.multiplier))
    }

    private def resetPriorityMenuItems {
        resetMapMenuItems(priorityMenuMap, (name => Windows.getPriority(name) == processPriority))
    }

    private def resetNumberMenuItems: Unit = {
        numberMenuMap.foreach { case (name, item) =>
            val n = getNumberOfName(name)
            item.setText(makeNumberText(name, n))
        }
    }

    private def resetBoolMenuItems {
        resetMapMenuItems(boolMenuMap, getBooleanOfName)
    }

    private def resetKeyboardMenuItems {
        resetMapMenuItems(keyboardMenuMap, (name => Keyboard.getVKCode(name) == targetVKCode))
    }

    private def resetVhAdjusterMenuItems {
        boolMenuMap("vhAdjusterMode").setEnabled(Scroll.horizontal)
        resetMapMenuItems(vhAdjusterMenuMap, (name => getVhAdjusterMethod(name) == VHAdjuster.method))
    }

    private def resetOnOffMenuItems {
        OnOffNames.foreach(name => {
            val item = boolMenuMap(name)
            item.setText(getOnOffText(getBooleanOfName(name)))
        })
    }

    private def resetAllMenuItems: Unit = {
        logger.debug("resetAllMenuItems")
        resetTriggerMenuItems
        resetKeyboardMenuItems
        resetAccelMenuItems
        resetPriorityMenuItems
        resetNumberMenuItems
        resetBoolMenuItems
        resetVhAdjusterMenuItems
        resetOnOffMenuItems
    }

    private def textToName(s: String): String =
        s.split(" ")(0)

    private def unselectAllItems(map: HashMap[String, MenuItem]) {
        map.foreach { case (_, item) =>
            item.setSelection(false)
        }
    }

    private def addListener(item: MenuItem, f: Event => Unit) {
        item.addListener(SWT.Selection, (e: Event) => f(e))
    }

    private def addListenerSelection(item: MenuItem, f: Event => Unit) {
        addListener(item, e =>
            if (item.getSelection)
                f(e)
        )
    }

    private val triggerMenuMap = new HashMap[String, MenuItem]

    private def addListenerMapMenuItem(item: MenuItem, map: HashMap[String, MenuItem], f: () => Unit) {
        logger.debug("addClickMapMenuItem: " + item.getText)

        addListenerSelection(item, _ => {
            logger.debug("Listener(Selection): " + item.getText)
            unselectAllItems(map)
            item.setSelection(true)
            f()
        })
    }

    private def createTriggerMenuItem(menu: Menu, text: String) {
        val item = createRadioMenuItem(menu, text)
        val name = textToName(text)
        triggerMenuMap(name) = item

        addListenerMapMenuItem(item, triggerMenuMap, () => setTrigger(name))
    }

    private def addSeparator(menu: Menu) {
        new MenuItem(menu, SWT.SEPARATOR)
    }

    private def createTriggerMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createTriggerMenuItem(menu, text)

        add("LR (Left <<-->> Right)")
        add("Left (Left -->> Right)")
        add("Right (Right -->> Left)")
        addSeparator(menu)

        add("Middle")
        add("X1")
        add("X2")
        addSeparator(menu)

        add("LeftDrag")
        add("RightDrag")
        add("MiddleDrag")
        add("X1Drag")
        add("X2Drag")
        addSeparator(menu)

        add("None")
        addSeparator(menu)

        createBoolMenuItem(menu, "sendMiddleClick", "Send MiddleClick", isSingleTrigger)
        createBoolMenuItem(menu, "draggedLock", "Dragged Lock", isDragTrigger)

        createCascadeMenuItem(parent, "Trigger", menu)
    }

    private def setAccelMultiplier(name: String) {
        logger.debug(s"setAccelMultiplier: $name")
        Accel.multiplier = Accel.getMultiplier(name)
    }

    private val accelMenuMap = new HashMap[String, MenuItem]

    private def createAccelMenuItem(menu: Menu, text: String) {
        val item = createRadioMenuItem(menu, text)
        val name = textToName(text)
        accelMenuMap(name) = item

        addListenerMapMenuItem(item, accelMenuMap, () => setAccelMultiplier(name))
    }

    private def createAccelTableMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createAccelMenuItem(menu, text)

        createOnOffMenuItem(menu, "accelTable")
        addSeparator(menu)

        add("M5 (1.0 ... 4.8)")
        add("M6 (1.2 ... 5.8)")
        add("M7 (1.4 ... 6.7)")
        add("M8 (1.6 ... 7.7)")
        add("M9 (1.8 ... 8.7)")
        addSeparator(menu)
        createBoolMenuItem(menu, "customAccelTable", "Custom Table", !Accel.customDisabled)

        createCascadeMenuItem(parent, "Accel Table", menu)
    }

    private def openNumberInputDialog(name: String, low: Int, up: Int) = {
        val cur = getNumberOfName(name)
        val dialog = new Dialog.NumberInputDialog(systemShell, name, low, up, cur)
        dialog.open
    }

    private def makeNumberText(name: String, n: Int) =
        s"$name = $n"

    private def setPriority(name: String) = {
        val p = Windows.getPriority(name)
        logger.debug(s"setPriority: ${p.name}")
        processPriority = p
        Windows.setPriority(p)
    }

    private val priorityMenuMap = new HashMap[String, MenuItem]

    private def createPriorityMenuItem(menu: Menu, text: String) {
        val item = createRadioMenuItem(menu, text)
        priorityMenuMap(text) = item

        addListenerSelection(item, _ =>
            setPriority(item.getText)
        )
    }

    private def createPriorityMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createPriorityMenuItem(menu, text)

        add("High")
        add("Above Normal")
        add("Normal")

        createCascadeMenuItem(parent, "Priority", menu)
    }

    private val numberMenuMap = new HashMap[String, MenuItem]

    private def createMenuItem(parent: Menu, style: Int, text: String) = {
        val item = new MenuItem(parent, style)
        item.setText(text)
        item
    }

    private def createPushMenuItem(parent: Menu, text: String) = {
        createMenuItem(parent, SWT.PUSH, text)
    }

    private def createRadioMenuItem(parent: Menu, text: String) = {
        createMenuItem(parent, SWT.RADIO, text)
    }

    private def createCheckMenuItem(parent: Menu, text: String) = {
        createMenuItem(parent, SWT.CHECK, text)
    }

    private def createCascadeMenuItem(parent: Menu, text: String, pulldown: Menu) = {
        val item = createMenuItem(parent, SWT.CASCADE, text)
        item.setMenu(pulldown)
        item
    }

    private def createNumberMenuItem(menu: Menu, name: String, low: Int, up: Int) {
        val n = getNumberOfName(name)
        val item = createPushMenuItem(menu, makeNumberText(name, n))
        numberMenuMap(name) = item

        addListener(item, _ => {
            val num = openNumberInputDialog(name, low, up)
            num.foreach(n => {
                setNumberOfName(name, n)
                item.setText(makeNumberText(name, n))
            })
        })
    }

    private def createNumberMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String, low: Int, up: Int) = createNumberMenuItem(menu, text, low, up)

        add("pollTimeout", 150, 500)
        add("scrollLocktime", 150, 500)
        addSeparator(menu)

        add("verticalThreshold", 0, 500)
        add("horizontalThreshold", 0, 500)

        createCascadeMenuItem(parent, "Set Number", menu)
    }

    private def getOnOffText(b: Boolean) = if (b) "ON" else "OFF"

    private def createOnOffMenuItem(menu: Menu, vname: String, action: Boolean => Unit = _ => {}): Unit = {
        val item = createCheckMenuItem(menu, getOnOffText(getBooleanOfName(vname)))
        boolMenuMap(vname) = item

        addListener(item, _ => {
            val b = item.getSelection
            item.setText(getOnOffText(b))
            setBooleanOfName(vname, b)
            action(b)
        })
    }

    private def createBoolMenuItem(menu: Menu, vName: String, mName: String, enabled: Boolean = true) = {
        val item = createCheckMenuItem(menu, mName)
        item.setEnabled(enabled)
        addListener(item, makeSetBooleanEvent(vName))
        boolMenuMap(vName) = item
        item
    }

    private def createBoolMenuItem(menu: Menu, vName: String) {
        createBoolMenuItem(menu, vName, vName)
    }

    private def createRealWheelModeMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        def addNum(name: String, low: Int, up: Int) = createNumberMenuItem(menu, name, low, up)
        def addBool(name: String) = createBoolMenuItem(menu, name)

        createOnOffMenuItem(menu, "realWheelMode")
        addSeparator(menu)

        addNum("wheelDelta", 10, 500)
        addNum("vWheelMove", 10, 500)
        addNum("hWheelMove", 10, 500)
        addSeparator(menu)

        addBool("quickFirst")
        addBool("quickTurn")

        createCascadeMenuItem(parent, "Real Wheel Mode", menu)
    }

    private val vhAdjusterMenuMap = new HashMap[String, MenuItem]

    private def getVhAdjusterMethod(name: String) = name match {
        case "Fixed" => Fixed()
        case "Switching" => Switching()
    }

    private def setVhAdjusterMethod(name: String) = {
        logger.debug(s"setVhAdjusterMethod: $name")
        VHAdjuster.method = getVhAdjusterMethod(name)
    }

    private def createVhAdjusterMenuItem(menu: Menu, text: String) {
        val item = createRadioMenuItem(menu, text)
        vhAdjusterMenuMap(text) = item

        addListenerSelection(item, _ =>
            setVhAdjusterMethod(item.getText)
        )
    }

    private def createVhAdjusterMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createVhAdjusterMenuItem(menu, text)
        def addNum(name: String, low: Int, up: Int) = createNumberMenuItem(menu, name, low, up)
        def addBool(name: String) = createBoolMenuItem(menu, name)

        createOnOffMenuItem(menu, "vhAdjusterMode")
        boolMenuMap("vhAdjusterMode").setEnabled(Scroll.horizontal)
        addSeparator(menu)

        add("Fixed")
        add("Switching")
        addSeparator(menu)

        addBool("firstPreferVertical")
        addNum("firstMinThreshold", 1, 10)
        addNum("switchingThreshold", 10, 500)

        createCascadeMenuItem(parent, "VH Adjuster", menu)
    }

    private def toString2F(d: Double) =
        ("%.2f" format d)

    private val keyboardMenuMap = new HashMap[String, MenuItem]

    private def setTargetVKCode(name: String) {
        logger.debug(s"setTargetVKCode: $name")
        targetVKCode = Keyboard.getVKCode(name)
    }

    private def createKeyboardMenuItem(menu: Menu, text: String) {
        val item = createRadioMenuItem(menu, text)
        val name = textToName(text)
        keyboardMenuMap(name) = item

        addListenerMapMenuItem(item, keyboardMenuMap, () => setTargetVKCode(name))
    }

    private def createKeyboardMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createKeyboardMenuItem(menu, text)

        createOnOffMenuItem(menu, "keyboardHook", Hook.setOrUnsetKeyboardHook)
        addSeparator(menu)

        add("VK_TAB (Tab)")
        add("VK_PAUSE (Pause)")
        add("VK_CAPITAL (Caps Lock)")
        add("VK_CONVERT (Henkan)")
        add("VK_NONCONVERT (Muhenkan)")
        add("VK_PRIOR (Page Up)")
        add("VK_NEXT (Page Down)")
        add("VK_END (End)")
        add("VK_HOME (Home)")
        add("VK_SNAPSHOT (Print Screen)")
        add("VK_INSERT (Insert)")
        add("VK_DELETE (Delete)")
        add("VK_LWIN (Left Windows)")
        add("VK_RWIN (Right Windows)")
        add("VK_APPS (Application)")
        add("VK_NUMLOCK (Number Lock)")
        add("VK_SCROLL (Scroll Lock)")
        add("VK_LSHIFT (Left Shift)")
        add("VK_RSHIFT (Right Shift)")
        add("VK_LCONTROL (Left Ctrl)")
        add("VK_RCONTROL (Right Ctrl)")
        add("VK_LMENU (Left Alt)")
        add("VK_RMENU (Right Alt)")
        addSeparator(menu)
        add("None")

        createCascadeMenuItem(parent, "Keyboard", menu)
    }

    private val DEFAULT_DEF = Properties.DEFAULT_DEF

    private def setProperties(name: String) {
        if (selectedProperties != name) {
            logger.debug(s"setProperties: $name")

            selectedProperties = name
            loadProperties
            resetAllMenuItems
        }
    }

    private def addPropertiesMenu(menu: Menu, name: String) {
        val item = createRadioMenuItem(menu, name)
        item.setSelection(name == selectedProperties)

        addListenerSelection(item, _ => {
            storeProperties
            setProperties(name)
        })
    }

    private def addDefaultPropertiesMenu(menu: Menu) {
        addPropertiesMenu(menu, DEFAULT_DEF)
    }

    private def addPropertiesMenu(menu: Menu, file: File) {
        addPropertiesMenu(menu, Properties.getUserDefName(file))
    }

    private def openDir(path: String) {
        val desk = java.awt.Desktop.getDesktop
        desk.browse(new File(path).toURI())
    }

    private def createOpenDirMenuItem(menu: Menu, dir: String) {
        val item = createPushMenuItem(menu, "Open Dir")
        addListener(item, _ => openDir(dir))
    }

    private def isValidPropertiesName(name: String) =
        (name != DEFAULT_DEF) && !(name.startsWith("--"))

    private def createAddPropertiesMenuItem(menu: Menu) {
        val item = createPushMenuItem(menu, "Add")
        addListener(item, _ => {
            val dialog = new Dialog.TextInputDialog(systemShell, "Properties Name", "Add Properties")
            val res = dialog.open

            try {
                res.foreach(name => {
                    if (isValidPropertiesName(name)) {
                        storeProperties
                        Properties.copy(selectedProperties, name)
                        selectedProperties = name
                    }
                    else
                        Dialog.errorMessage(s"Invalid Name: $name", "Name Error")
                })
            }
            catch {
                case e: Exception =>
                    Dialog.errorMessageE(e)
            }
        })
    }

    private def createDeletePropertiesMenuItem(menu: Menu) {
        val item = createPushMenuItem(menu, "Delete")
        val name = selectedProperties
        item.setEnabled(name != DEFAULT_DEF)
        addListener(item, _ => {
            try {
                if (Dialog.openYesNoMessage(s"Delete the '$name' properties?")) {
                    Properties.delete(name)
                    setProperties(DEFAULT_DEF)
                }
            }
            catch {
                case e: Exception =>
                    Dialog.errorMessageE(e)
            }
        })
    }

    private def createPropertiesMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        def addDefault = addDefaultPropertiesMenu(menu)
        def add(f: File) = addPropertiesMenu(menu, f)

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

            addDefault
            addSeparator(menu)

            Properties.getPropFiles.foreach(add)
        })

        createCascadeMenuItem(parent, "Properties", menu)
    }

    def reloadProperties {
        loadProperties
        resetAllMenuItems
    }

    private def createReloadPropertiesMenu(menu: Menu) {
        val item = createPushMenuItem(menu, "Reload")
        addListener(item, _ => reloadProperties)
    }

    private def createSavePropertiesMenu(menu: Menu) {
        val item = createPushMenuItem(menu, "Save")
        addListener(item, _ => storeProperties)
    }

    private def makeSetBooleanEvent(name: String) = {
        (e: Event) => {
            val item = e.widget.asInstanceOf[MenuItem]
            val b = item.getSelection
            setBooleanOfName(name, b)
        }
    }

    private val boolMenuMap = new HashMap[String, MenuItem]

    private def createCursorChangeMenu(menu: Menu) = {
        createBoolMenuItem(menu, "cursorChange", "Cursor Change")
    }

    private def createHorizontalScrollMenu(menu: Menu) = {
        val item = createBoolMenuItem(menu, "horizontalScroll", "Horizontal Scroll")
        addListener(item, _ =>
            boolMenuMap("vhAdjusterMode").setEnabled(item.getSelection)
        )
    }

    private def createReverseScrollMenu(menu: Menu) = {
        createBoolMenuItem(menu, "reverseScroll", "Reverse Scroll (Flip)")
    }

    private def createSwapScrollMenu(menu: Menu) = {
        createBoolMenuItem(menu, "swapScroll", "Swap Scroll (V.H)")
    }

    private var passModeMenuItem: MenuItem = null

    private def createPassModeMenu(menu: Menu) {
        val item = createCheckMenuItem(menu, "Pass Mode")
        addListener(item, makeSetBooleanEvent("passMode"))
        passModeMenuItem = item
    }

    private def createInfoMenu(menu: Menu) {
        val item = createPushMenuItem(menu, "Info")
        addListener(item, _ => {
            val nameVer = s"Name: $PROGRAM_NAME / Version: $PROGRAM_VERSION\n\n"
            val jVer = s"java.version: ${System.getProperty("java.version")}\n"
            val osArch = s"os.arch: ${System.getProperty("os.arch")}\n"
            val dataModel = s"sun.arch.data.model: ${System.getProperty("sun.arch.data.model")}"

            val mb = new MessageBox(systemShell, SWT.OK | SWT.ICON_INFORMATION)
            mb.setText("Info")
            mb.setMessage(nameVer + jVer + osArch + dataModel)
            mb.open()
        })
    }

    def exitAction(e: Event) {
        trayItem.setVisible(false)
        W10Wheel.exitMessageLoop
    }

    private def createExitMenu(menu: Menu) {
        val item = createPushMenuItem(menu, "Exit")
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

    private var trayItem: TrayItem = null

    def setSystemTray {
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

        createInfoMenu(menu)
        createExitMenu(menu)

        resetAllMenuItems
    }

    def resetSystemTray {
        Display.getDefault.asyncExec(() => {
            trayItem.dispose()
            setSystemTray
        })
    }

    private def setNumberOfProperty(name: String, low: Int, up: Int): Unit = {
        try {
            val n = prop.getInt(name)
            if (n < low || n > up)
                logger.warn(s"Number out of bounds: $name")
            else
                setNumberOfName(name, n)
        }
        catch {
            case _: NoSuchElementException => logger.warn(s"Not found: $name")
            case _: NumberFormatException => logger.warn(s"Parse error: $name")
            case _: scala.MatchError  => logger.warn(s"Match error: $name")
        }
    }

    private def setBooleanOfProperty(name: String) {
        try {
            setBooleanOfName(name, prop.getBoolean(name))
        }
        catch {
            case _: NoSuchElementException => logger.warn(s"Not found: $name")
            case _: IllegalArgumentException => logger.warn(s"Parse error: $name")
            case _: scala.MatchError => logger.warn(s"Match error: $name")
        }
    }

    private def setEnabledMenu(menuMap: HashMap[String, MenuItem], key: String, enabled: Boolean) {
         if (menuMap.contains(key))
            menuMap(key).setEnabled(enabled)
    }

    private def setTrigger(s: String): Unit = {
        val res = Mouse.getTrigger(s)
        logger.debug("setTrigger: " + res.name);
        firstTrigger = res

        setEnabledMenu(boolMenuMap, "sendMiddleClick", res.isSingle)
        setEnabledMenu(boolMenuMap, "draggedLock", res.isDrag)

        EventHandler.changeTrigger
    }

    private def setTriggerOfProperty: Unit = {
        try {
            setTrigger(prop.getString("firstTrigger"))
        }
        catch {
            case e: NoSuchElementException => logger.warn(s"Not found: ${e.getMessage}")
            case e: scala.MatchError => logger.warn(s"Match error: ${e.getMessage}")
        }
    }

    private def setCustomAccelOfProperty {
        try {
            val cat = prop.getIntArray("customAccelThreshold")
            val cam = prop.getDoubleArray("customAccelMultiplier")

            if (cat.length != 0 && cat.length == cam.length) {
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
        try {
            setAccelMultiplier(prop.getString("accelMultiplier"))
        }
        catch {
            case e: NoSuchElementException => logger.warn(s"Not found: ${e.getMessage}")
            case e: scala.MatchError => logger.warn(s"Match error: ${e.getMessage}")
        }
    }

    private def setPriorityOfProperty: Unit = {
        try {
            setPriority(prop.getString("processPriority"))
        }
        catch {
            case e: NoSuchElementException => {
                logger.warn(s"Not found: ${e.getMessage}")
                setDefaultPriority
            }
            case e: scala.MatchError => {
                logger.warn(s"Match error: ${e.getMessage}")
                setDefaultPriority
            }
        }
    }

    private def setVKCodeOfProperty: Unit = {
        try {
            setTargetVKCode(prop.getString("targetVKCode"))
        }
        catch {
            case e: NoSuchElementException => logger.warn(s"Not found: ${e.getMessage}")
            case e: scala.MatchError => logger.warn(s"Match error: ${e.getMessage}")
        }
    }

    private def setVhAdjusterMethodOfProperty {
        try {
            setVhAdjusterMethod(prop.getString("vhAdjusterMethod"))
        }
        catch {
            case e: NoSuchElementException => logger.warn(s"Not found: ${e.getMessage}")
            case e: scala.MatchError => logger.warn(s"Match error: ${e.getMessage}")
        }
    }

    private def setDefaultPriority {
        logger.debug("setDefaultPriority")
        //Windows.setPriority(processPriority)
        setPriority(processPriority.name)
    }

    private def setDefaultTrigger {
        setTrigger(firstTrigger.name)
    }

    private val prop = new Properties.SProperties

    private def getSelectedPropertiesPath =
        Properties.getPath(selectedProperties)

    private var loaded = false

    def loadProperties {
        loaded = true
        try {
            prop.load(getSelectedPropertiesPath)

            setTriggerOfProperty
            setAccelOfProperty
            setCustomAccelOfProperty
            setPriorityOfProperty
            setVKCodeOfProperty
            setVhAdjusterMethodOfProperty

            BooleanNames.foreach(n => setBooleanOfProperty(n))
            Hook.setOrUnsetKeyboardHook(keyboardHook)

            def setNum(n: String, l: Int, u: Int) = setNumberOfProperty(n, l, u)
            setNum("pollTimeout", 50, 500)
            setNum("scrollLocktime", 150, 500)
            setNum("verticalThreshold" , 0, 500)
            setNum("horizontalThreshold", 0, 500)

            setNum("wheelDelta", 10, 500)
            setNum("vWheelMove", 10, 500)
            setNum("hWheelMove", 10, 500)

            setNum("firstMinThreshold", 1, 10)
            setNum("switchingThreshold", 10, 500)
        }
        catch {
            case _: FileNotFoundException => {
                logger.debug("Properties file not found")
                setDefaultPriority
                setDefaultTrigger
            }
            case e: Exception => logger.warn(s"load: ${e.toString}")
        }
    }

    private def isChangedBoolean =
        BooleanNames.map(n => prop.getBoolean(n) != getBooleanOfName(n)).contains(true)

    private def isChangedNumber =
        NumberNames.map(n => prop.getInt(n) != getNumberOfName(n)).contains(true)

    private def isChangedProperties: Boolean = {
        logger.debug("isChangedProperties")

        try {
            prop.load(getSelectedPropertiesPath)

            def check(n: String, v: String) = prop.getString(n) != v

            check("firstTrigger", firstTrigger.name) ||
            check("accelMultiplier", Accel.multiplier.name) ||
            check("processPriority", processPriority.name) ||
            check("targetVKCode", Keyboard.getName(targetVKCode)) ||
            check("vhAdjusterMethod", VHAdjuster.method.name) ||
            isChangedBoolean || isChangedNumber
        }
        catch {
            case _: FileNotFoundException => logger.debug("First write properties"); true
            case e: NoSuchElementException => logger.warn(s"Not found: ${e.getMessage}"); true
            case e: Exception => logger.warn(s"isChanged: ${e.toString}"); true
        }
    }

    private val NumberNames: Array[String] = {
        Array("pollTimeout", "scrollLocktime",
              "verticalThreshold", "horizontalThreshold",
              "wheelDelta", "vWheelMove", "hWheelMove",
              "firstMinThreshold", "switchingThreshold")
    }

    private val BooleanNames: Array[String] = {
        Array("realWheelMode", "cursorChange",
              "horizontalScroll", "reverseScroll",
              "quickFirst", "quickTurn",
              "accelTable", "customAccelTable",
              "draggedLock", "swapScroll",
              "sendMiddleClick", "keyboardHook",
              "vhAdjusterMode", "firstPreferVertical"
        )
    }

    private val OnOffNames: Array[String] = {
        Array("realWheelMode", "accelTable", "keyboardHook", "vhAdjusterMode")
    }

    private def setNumberOfName(name: String, n: Int): Unit = {
        logger.debug(s"setNumber: $name = $n")

        name match {
            case "pollTimeout" => pollTimeout = n
            case "scrollLocktime" => Scroll.locktime = n
            case "verticalThreshold" => Threshold.vertical = n
            case "horizontalThreshold" => Threshold.horizontal = n
            case "wheelDelta" => RealWheel.wheelDelta = n
            case "vWheelMove" => RealWheel.vWheelMove = n
            case "hWheelMove" => RealWheel.hWheelMove = n
            case "firstMinThreshold" => VHAdjuster.firstMinThreshold = n
            case "switchingThreshold" => VHAdjuster.switchingThreshold = n
        }
    }

    private def getNumberOfName(name: String): Int = name match {
        case "pollTimeout" => pollTimeout
        case "scrollLocktime" => Scroll.locktime
        case "verticalThreshold" => Threshold.vertical
        case "horizontalThreshold" => Threshold.horizontal
        case "wheelDelta" => RealWheel.wheelDelta
        case "vWheelMove" => RealWheel.vWheelMove
        case "hWheelMove" => RealWheel.hWheelMove
        case "firstMinThreshold" => VHAdjuster.firstMinThreshold
        case "switchingThreshold" => VHAdjuster.switchingThreshold
    }

    private def setBooleanOfName(name: String, b: Boolean) = {
        logger.debug(s"setBoolean: $name = ${b.toString}")
        name match {
            case "realWheelMode" => RealWheel.mode = b
            case "cursorChange" => Scroll.cursorChange = b
            case "horizontalScroll" => Scroll.horizontal = b
            case "reverseScroll" => Scroll.reverse = b
            case "quickFirst" => RealWheel.quickFirst = b
            case "quickTurn" => RealWheel.quickTurn = b
            case "accelTable" => Accel.table = b
            case "customAccelTable" => Accel.customTable = b
            case "draggedLock" => Scroll.draggedLock = b
            case "swapScroll" => Scroll.swap = b
            case "sendMiddleClick" => sendMiddleClick = b
            case "keyboardHook" => keyboardHook = b
            case "vhAdjusterMode" => VHAdjuster.mode = b
            case "firstPreferVertical" => VHAdjuster.firstPreferVertical = b
            case "passMode" => Pass.mode = b
        }
    }

    private def getBooleanOfName(name: String): Boolean = name  match {
        case "realWheelMode" => RealWheel.mode
        case "cursorChange" => Scroll.cursorChange
        case "horizontalScroll" => Scroll.horizontal
        case "reverseScroll" => Scroll.reverse
        case "quickFirst" => RealWheel.quickFirst
        case "quickTurn" => RealWheel.quickTurn
        case "accelTable" => Accel.table
        case "customAccelTable" => Accel.customTable
        case "draggedLock" => Scroll.draggedLock
        case "swapScroll" => Scroll.swap
        case "sendMiddleClick" => sendMiddleClick
        case "keyboardHook" => keyboardHook
        case "vhAdjusterMode" => VHAdjuster.mode
        case "firstPreferVertical" => VHAdjuster.firstPreferVertical
        case "passMode" => Pass.mode
    }

    def storeProperties: Unit = {
        logger.debug("storeProperties")

        try {
            //(Properties.exists(selectedProperties) &&
            if (!loaded || !isChangedProperties) {
                logger.debug("Not changed properties")
                return
            }

            def set(n: String, v: String) = prop.setProperty(n, v)

            set("firstTrigger", firstTrigger.name)
            set("accelMultiplier", Accel.multiplier.name)
            set("processPriority", processPriority.name)
            set("targetVKCode", Keyboard.getName(targetVKCode))
            set("vhAdjusterMethod", VHAdjuster.method.name)

            BooleanNames.foreach(n => prop.setBoolean(n, getBooleanOfName(n)))
            NumberNames.foreach(n => prop.setInt(n, getNumberOfName(n)))

            prop.store(getSelectedPropertiesPath)
        }
        catch {
            case e: Exception => logger.warn(s"store: ${e.toString}")
        }
    }

    def initState: Unit = {
        EventHandler.initState
        KEventHandler.initState()
        LastFlags.init
        exitScrollMode
        EventWaiter.offer(Cancel(null))
    }
}
