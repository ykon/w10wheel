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
    val PROGRAM_VERSION = "2.7"
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
        new Image(Display.getDefault, stream)
    }

    private def getTrayIcon(b: Boolean): Image = {
        getImage(if (b) ICON_STOP_NAME else ICON_RUN_NAME)
    }

    private def getTrayText(b: Boolean) = {
        s"$PROGRAM_NAME - ${convLang(if (b) "Stopped" else "Runnable")}"
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
            case MiddleDown(_) | X1Down(_) | X2Down(_) => sdS.set(true)
            case _ => throw new IllegalArgumentException()
        }

        def setSuppressed(down: KeyboardEvent): Unit = down match {
            case KeyDown(_) => kdS(down.vkCode).set(true)
            case _ => throw new IllegalArgumentException()
        }

        def getAndReset_SuppressedDown(up: MouseEvent): Boolean = up match {
            case LeftUp(_) => ldS.getAndSet(false)
            case RightUp(_) => rdS.getAndSet(false)
            case MiddleUp(_) | X1Up(_) | X2Up(_) => sdS.getAndSet(false)
            case _ => throw new IllegalArgumentException()
        }

        def getAndReset_SuppressedDown(up: KeyboardEvent): Boolean = up match {
            case KeyUp(_) => kdS(up.vkCode).getAndSet(false)
            case _ => throw new IllegalArgumentException()
        }

        def resetLR(down: MouseEvent): Unit = down match {
            case LeftDown(_) => ldR.set(false); ldS.set(false); ldP.set(false)
            case RightDown(_) => rdR.set(false); rdS.set(false); rdP.set(false)
            case _ => throw new IllegalArgumentException()
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
        resetTriggerMenuItems
        resetKeyboardMenuItems
        resetAccelMenuItems
        resetPriorityMenuItems
        resetLanguageMenuItems
        resetNumberMenuItems
        resetBoolMenuItems
        resetVhAdjusterMenuItems
        resetOnOffMenuItems
    }

    private def getFirstWord(s: String): String =
        s.split(" ")(0)
    
    private def unselectAllItems(map: MenuMap) {
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

    private def createBoolMenuItem(menu: Menu, name: String) {
        createBoolMenuItem(menu, MenuData(name, Some(name)))
    }

    private def addSeparator(menu: Menu) {
        new MenuItem(menu, SWT.SEPARATOR)
    }
    
    private def isSeparator(item: MenuItem): Boolean = {
        item.getStyle == SWT.SEPARATOR
    }

    private def createTriggerMenu(parent: Menu) {
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

    private def setAccelMultiplier(name: String) {
        logger.debug(s"setAccelMultiplier: $name")
        Accel.multiplier = Accel.getMultiplier(name)
    }

    private val accelMenuMap = new MenuMap()

    private def createAccelTableMenu(parent: Menu) {
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

    private def createPriorityMenu(parent: Menu) {
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

    private def createNumberMenuItem(menu: Menu, name: String, low: Int, up: Int) {
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

    private def createNumberMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        val add = (name: String, low: Int, up: Int) => createNumberMenuItem(menu, name, low, up)

        add(DataID.pollTimeout, 150, 500)
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

    private def createRealWheelModeMenu(parent: Menu) {
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

    private def createVhAdjusterMenu(parent: Menu) {
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
        ("%.2f" format d)

    private val keyboardMenuMap = new MenuMap()

    private def setTargetVKCode(name: String): Unit = {
        logger.debug(s"setTargetVKCode: $name")
        targetVKCode = Keyboard.getVKCode(name)
    }

    private def createKeyboardMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        
        val add = (text: String) => {
            val data = MenuData(text, Some(getFirstWord(text)))
            createMapMenuItem(menu, data, keyboardMenuMap, setTargetVKCode)
        }

        createOnOffMenuItem(menu, DataID.keyboardHook, Hook.setOrUnsetKeyboardHook)
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

    private def setProperties(name: String) {
        if (selectedProperties != name) {
            logger.debug(s"setProperties: $name")

            selectedProperties = name
            loadProperties
            resetAllMenuItems()
        }
    }

    private def addPropertiesMenu(menu: Menu, name: String) {
        val item = createRadioMenuItem(menu, MenuData(name, None))
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
        val item = createPushMenuItem(menu, MenuData("Open Dir", None))
        addListener(item, _ => openDir(dir))
    }

    private def isValidPropertiesName(name: String) =
        (name != DEFAULT_DEF) && !(name.startsWith("--"))

    private def createAddPropertiesMenuItem(menu: Menu) {
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

    private def createDeletePropertiesMenuItem(menu: Menu) {
        val item = createPushMenuItem(menu, MenuData("Delete", None))
        val name = selectedProperties
        item.setEnabled(name != DEFAULT_DEF)
        addListener(item, _ => {
            try {
                if (Dialog.openYesNoMessage(s"${convLang("Delete properties")}: $name")) {
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

    def reloadProperties {
        loadProperties
        resetAllMenuItems()
    }

    private def createReloadPropertiesMenu(menu: Menu) {
        val item = createPushMenuItem(menu, MenuData("Reload", None))
        addListener(item, _ => reloadProperties)
    }

    private def createSavePropertiesMenu(menu: Menu) {
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

    private def createPassModeMenu(menu: Menu) {
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

    private def createLanguageMenu(parent: Menu) {
        val menu = new Menu(systemShell, SWT.DROP_DOWN)
        val add = (text: String, id: String) =>
            createLanguageMenuItem(menu, text, id)

        add("English", DataID.English)
        add("Japanese", DataID.Japanese)

        createCascadeMenuItem(parent, menu, "Language")
    }

    private def createInfoMenu(menu: Menu) {
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

    def exitAction(e: Event) {
        trayItem.setVisible(false)
        W10Wheel.exitMessageLoop
    }

    private def createExitMenu(menu: Menu) {
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

    def setSystemTray() {
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

    private def setEnabledMenu(menuMap: MenuMap, key: String, enabled: Boolean) {
         if (menuMap.contains(key))
            menuMap(key).setEnabled(enabled)
    }

    private def setTrigger(s: String): Unit = {
        val res = Mouse.getTrigger(s)
        logger.debug("setTrigger: " + res.name);
        firstTrigger = res

        setEnabledMenu(boolMenuMap, DataID.sendMiddleClick, res.isSingle)
        setEnabledMenu(boolMenuMap, DataID.draggedLock, res.isDrag)

        EventHandler.changeTrigger
    }
    
    private def setStringOfProperty(name: String, setFunc: String => Unit) {
        try {
            setFunc(prop.getString(name))
        }
        catch {
            case e: NoSuchElementException => logger.warn(s"Not found: ${e.getMessage}")
            case e: scala.MatchError => logger.warn(s"Match error: ${e.getMessage}")
        }
    }

    private def setTriggerOfProperty: Unit = {
        setStringOfProperty(DataID.firstTrigger, setTrigger)
    }

    private def setCustomAccelOfProperty {
        try {
            val cat = prop.getIntArray(DataID.customAccelThreshold)
            val cam = prop.getDoubleArray(DataID.customAccelMultiplier)

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
        setStringOfProperty(DataID.accelMultiplier, setAccelMultiplier)
    }

    private def setPriorityOfProperty: Unit = {
        try {
            setPriority(prop.getString(DataID.processPriority))
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
        setStringOfProperty(DataID.targetVKCode, setTargetVKCode)
    }
    
    private def setVhAdjusterMethodOfProperty: Unit = {
        setStringOfProperty(DataID.vhAdjusterMethod, setVhAdjusterMethod)
    }
    
    private def setUILanguageOfProperty: Unit = {
        setStringOfProperty(DataID.uiLanguage, setUILanguage)
    }

    private def setDefaultPriority: Unit = {
        logger.debug("setDefaultPriority")
        //Windows.setPriority(processPriority)
        setPriority(processPriority.name)
    }

    private def setDefaultTrigger: Unit = {
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
            setUILanguageOfProperty

            BooleanNames.foreach(n => setBooleanOfProperty(n))
            Hook.setOrUnsetKeyboardHook(keyboardHook)

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

            val check = (n: String, v: String) => prop.getString(n) != v

            check(DataID.firstTrigger, firstTrigger.name) ||
            check(DataID.accelMultiplier, Accel.multiplier.name) ||
            check(DataID.processPriority, processPriority.name) ||
            check(DataID.targetVKCode, Keyboard.getName(targetVKCode)) ||
            check(DataID.vhAdjusterMethod, VHAdjuster.method.name) ||
            check(DataID.uiLanguage, this.uiLanguage) ||
            isChangedBoolean || isChangedNumber
        }
        catch {
            case _: FileNotFoundException => logger.debug("First write properties"); true
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

        try {
            //(Properties.exists(selectedProperties) &&
            if (!loaded || !isChangedProperties) {
                logger.debug("Not changed properties")
                return
            }

            val set = (n: String, v: String) => prop.setProperty(n, v)

            set(DataID.firstTrigger, firstTrigger.name)
            set(DataID.accelMultiplier, Accel.multiplier.name)
            set(DataID.processPriority, processPriority.name)
            set(DataID.targetVKCode, Keyboard.getName(targetVKCode))
            set(DataID.vhAdjusterMethod, VHAdjuster.method.name)
            set(DataID.uiLanguage, this.uiLanguage)

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
