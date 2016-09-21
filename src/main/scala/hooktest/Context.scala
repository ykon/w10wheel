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

import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

import scala.collection.mutable.HashMap
import java.util.NoSuchElementException

object Context {
    val PROGRAM_NAME = "W10Wheel"
    val PROGRAM_VERSION = "2.0.11"
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
    
    @volatile private var dpiCorrection = 1.00 // default
    
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
        
        @volatile var table = false // default 
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
        @volatile var horizontal = 50 // default
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
        
        private def setStartPoint {
            val pt = Windows.getCursorPos
            sx = (pt.x * dpiCorrection).toInt
            sy = (pt.y * dpiCorrection).toInt
        }
        
        def start(info: HookInfo) = synchronized { 
            stime = info.time
            setStartPoint
            Windows.initScroll
            
            if (cursorChange && !firstTrigger.isDrag)
                Windows.changeCursorV
                
            mode = true
            starting = false
        }
        
        def start(kinfo: KHookInfo) = synchronized {
            stime = kinfo.time
            setStartPoint
            Windows.initScroll
            
            if (cursorChange)
                Windows.changeCursorV
                
            mode = true
            starting = false
        }
        
        def exit = synchronized {
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
        @volatile private var ldR = false
        @volatile private var rdR = false
        
        // P = Passed
        @volatile private var ldP = false
        @volatile private var rdP = false
        
        // S = Suppressed
        @volatile private var ldS = false
        @volatile private var rdS = false
        @volatile private var sdS = false
        
        private val kdS = new Array[Boolean](256)
        
        def setResent(down: MouseEvent) = down match {
            case LeftDown(_) => ldR = true
            case RightDown(_) => rdR = true
            //case _ => {}
        }
        
        def getAndReset_ResentDown(up: MouseEvent) = up match {
            case LeftUp(_) => val res = ldR; ldR = false; res
            case RightUp(_) => val res = rdR; rdR = false; res
            //case _ => false
        }
        
        def setPassed(down: MouseEvent) = down match {
            case LeftDown(_) => ldP = true
            case RightDown(_) => rdP = true
        }
        
        def getAndReset_PassedDown(up: MouseEvent) = up match {
            case LeftUp(_) => val res = ldP; ldP = false; res
            case RightUp(_) => val res = rdP; rdP = false; res
        }
        
        def setSuppressed(down: MouseEvent) = down match {
            case LeftDown(_) => ldS = true
            case RightDown(_) => rdS = true 
            case MiddleDown(_) | X1Down(_) | X2Down(_) => sdS = true
            //case _ => {}
        }
        
        def setSuppressed(down: KeyboardEvent) = down match {
            case KeyDown(_) => kdS(down.vkCode) = true
            //case _ => {}
        }
        
        def getAndReset_SuppressedDown(up: MouseEvent) = up match {
            case LeftUp(_) => val res = ldS; ldS = false; res
            case RightUp(_) => val res = rdS; rdS = false; res
            case MiddleUp(_) | X1Up(_) | X2Up(_) => val res = sdS; sdS = false; res
            //case _ => false
        }
        
        def getAndReset_SuppressedDown(up: KeyboardEvent) = up match {
            case KeyUp(_) => val res = kdS(up.vkCode); kdS(up.vkCode) = false; res 
            //case _ => false
        }
        
        def resetLR(down: MouseEvent) = down match {
            case LeftDown(_) => ldR = false; ldS = false; ldP = false
            case RightDown(_) => rdR = false; rdS = false; rdP = false
            //case MiddleDown(_) | X1Down(_) | X2Down(_) => sdS = false
            //case _ => {}
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
    
    // http://stackoverflow.com/questions/3239106/scala-actionlistener-anonymous-function-type-mismatch
    
    implicit def toListener(f: Event => Unit) = new Listener {
        override def handleEvent(e: Event) { f(e) }
    }
    
    implicit def toSelectionAdapter(f: SelectionEvent => Unit) = new SelectionAdapter {
        override def widgetSelected(e: SelectionEvent) { f(e) }
    }
    
    private def resetTriggerMenuItems {  
        triggerMenuMap.foreach { case (name, item) =>
            item.setSelection(Mouse.getTrigger(name) == firstTrigger)
        }
    }
    
    private def resetAccelMenuItems {
        accelMenuMap.foreach { case (name, item) =>
            item.setSelection(Accel.getMultiplier(name) == Accel.multiplier)
        }
    }
    
    private def resetPriorityMenuItems: Unit = {
        priorityMenuMap.foreach { case (name, item) =>
            item.setSelection(Windows.getPriority(name) == processPriority)
        }
    }
    
    private def resetNumberMenuItems: Unit = {
        numberMenuMap.foreach { case (name, item) =>
            val n = getNumberOfName(name)
            item.setText(makeNumberText(name, n))
        }
    }
    
    private def resetBoolMenuItems: Unit = {
        boolMenuMap.foreach { case (name, item) =>
            item.setSelection(getBooleanOfName(name))
        }
    }
    
    private def resetKeyboardMenuItems: Unit = {
        keyboardMenuMap.foreach { case (name, item) =>
            item.setSelection(Keyboard.getVKCode(name) == targetVKCode)
        }
    }
    
    private def resetVhAdjusterMenuItems {
        boolMenuMap("vhAdjusterMode").setEnabled(Scroll.horizontal)
        
        vhAdjusterMenuMap.foreach { case (name, item) =>
            item.setSelection(getVhAdjusterMethod(name) == VHAdjuster.method)
        }
    }
    
    private def resetOnOffMenuItems {
        OnOffNames.foreach(name => {
              val item = boolMenuMap(name)
              item.setText(getOnOffText(getBooleanOfName(name)))
        })
    }
    
    private def resetDpiCorrectionMenuItems {
        dpiCorrectionMenuMap.foreach { case (name, item) =>
            item.setSelection(name == toString2F(dpiCorrection))
        }
    }
        
    private def resetMenuItems: Unit = {
        resetTriggerMenuItems
        resetKeyboardMenuItems
        resetAccelMenuItems
        resetPriorityMenuItems
        resetNumberMenuItems
        resetBoolMenuItems
        resetVhAdjusterMenuItems
        resetOnOffMenuItems
        resetDpiCorrectionMenuItems
    }
    
    private def textToName(s: String): String =
        s.split(" ")(0)
        
    private def unselectOtherItems(map: HashMap[String, MenuItem], selectedName: String) {        
        map.foreach { case (name, item) =>
            if (name != selectedName)
                item.setSelection(false)
        }
    }
    
    private val triggerMenuMap = new HashMap[String, MenuItem]
    
    private def createTriggerMenuItem(menu: Menu, text: String) {
        val item = new MenuItem(menu, SWT.RADIO)
        item.setText(text)
        val name = textToName(text)
        triggerMenuMap(name) = item
        
        item.addSelectionListener((e: SelectionEvent) => {
            if (item.getSelection) {
                unselectOtherItems(triggerMenuMap, name)
                setTrigger(name)
            }
        })
    }
    
    private def addSeparator(menu: Menu) {
        new MenuItem(menu, SWT.SEPARATOR)
    }
    
    private def createTriggerMenu(pMenu: Menu) {        
        val tMenu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createTriggerMenuItem(tMenu, text)

        add("LR (Left <<-->> Right)")
        add("Left (Left -->> Right)")
        add("Right (Right -->> Left)")
        addSeparator(tMenu)
        
        add("Middle")
        add("X1")
        add("X2")
        addSeparator(tMenu)

        add("LeftDrag")
        add("RightDrag")
        add("MiddleDrag")
        add("X1Drag")
        add("X2Drag")
        addSeparator(tMenu)
        
        add("None")
        addSeparator(tMenu)
        
        createBoolMenuItem(tMenu, "sendMiddleClick", "Send MiddleClick", isSingleTrigger)
        createBoolMenuItem(tMenu, "draggedLock", "Dragged Lock", isDragTrigger)
        
        val item = new MenuItem(pMenu, SWT.CASCADE)
        item.setText("Trigger")
        item.setMenu(tMenu)
    }
    
    private def setAccelMultiplier(name: String) {
        logger.debug(s"setAccelMultiplier: $name")
        Accel.multiplier = Accel.getMultiplier(name)
    }
    
    private val accelMenuMap = new HashMap[String, MenuItem]
    
    private def createAccelMenuItem(menu: Menu, text: String) {
        val item = new MenuItem(menu, SWT.RADIO)
        item.setText(text)
        val name = textToName(text)
        accelMenuMap(name) = item
        
        item.addSelectionListener((e: SelectionEvent) => {
            if (item.getSelection) {
                unselectOtherItems(accelMenuMap, name)
                setAccelMultiplier(name)
            }
        })
    }
    
    private def createAccelTableMenu(pMenu: Menu) {        
        val aMenu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createAccelMenuItem(aMenu, text)
        
        createOnOffMenuItem(aMenu, "accelTable")
        addSeparator(aMenu)

        add("M5 (1.0 ... 4.8)")
        add("M6 (1.2 ... 5.8)")
        add("M7 (1.4 ... 6.7)")
        add("M8 (1.6 ... 7.7)")
        add("M9 (1.8 ... 8.7)")
        addSeparator(aMenu)
        
        createBoolMenuItem(aMenu, "customAccelTable", "Custom Table", !Accel.customDisabled)
        
        val item = new MenuItem(pMenu, SWT.CASCADE)
        item.setText("Accel Table")
        item.setMenu(aMenu)
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
        val item = new MenuItem(menu, SWT.RADIO)
        item.setText(text)
        priorityMenuMap(text) = item
        
        item.addSelectionListener((e: SelectionEvent) => {
            if(item.getSelection)
                setPriority(item.getText)
            ()
        })
    }
    
    private def createPriorityMenu(popMenu: Menu) {
        val priMenu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createPriorityMenuItem(priMenu, text)
        
        add("High")
        add("Above Normal")
        add("Normal")
        
        val item = new MenuItem(popMenu, SWT.CASCADE)
        item.setText("Priority")
        item.setMenu(priMenu)
    }
    
    private val numberMenuMap = new HashMap[String, MenuItem]
    
    private def createNumberMenuItem(menu: Menu, name: String, low: Int, up: Int) {
        val n = getNumberOfName(name)
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText(makeNumberText(name, n))
        numberMenuMap(name) = item
        
        item.addListener(SWT.Selection, (e: Event) => {
            val num = openNumberInputDialog(name, low, up)
            num.foreach(n => {
                setNumberOfName(name, n)
                item.setText(makeNumberText(name, n))
            })
        })    
    }
    
    private def createNumberMenu(pMenu: Menu) {
        val nMenu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String, low: Int, up: Int) = createNumberMenuItem(nMenu, text, low, up)
        
        add("pollTimeout", 150, 500)
        add("scrollLocktime", 150, 500)
        addSeparator(nMenu)
        
        add("verticalThreshold", 0, 500)
        add("horizontalThreshold", 0, 500)
        
        val item = new MenuItem(pMenu, SWT.CASCADE)
        item.setText("Set Number")
        item.setMenu(nMenu)
    }
    
    private def getOnOffText(b: Boolean) = if (b) "ON" else "OFF"
        
    private def createOnOffMenuItem(menu: Menu, vname: String, action: Boolean => Unit = _ => {}): Unit = {
        val item = new MenuItem(menu, SWT.CHECK)
        item.setText(getOnOffText(getBooleanOfName(vname)))
        boolMenuMap(vname) = item
        
        item.addListener(SWT.Selection, (e: Event) => {
            val b = item.getSelection
            item.setText(getOnOffText(b))
            setBooleanOfName(vname, b)
            action(b)
        })
    }
    
    private def createBoolMenuItem(menu: Menu, vName: String, mName: String, enabled: Boolean = true) = {
        val item = new MenuItem(menu, SWT.CHECK)
        item.setText(mName)
        item.setEnabled(enabled)
        item.addListener(SWT.Selection, makeSetBooleanEvent(vName))
        boolMenuMap(vName) = item
        item
    }
    
    private def createBoolMenuItem(menu: Menu, vName: String) {
        createBoolMenuItem(menu, vName, vName)
    }
    
    private def createRealWheelModeMenu(pMenu: Menu) {
        val rMenu = new Menu(systemShell, SWT.DROP_DOWN)
        def addNum(name: String, low: Int, up: Int) = createNumberMenuItem(rMenu, name, low, up)
        def addBool(name: String) = createBoolMenuItem(rMenu, name)
        
        createOnOffMenuItem(rMenu, "realWheelMode")
        addSeparator(rMenu)
        
        addNum("wheelDelta", 10, 500)
        addNum("vWheelMove", 10, 500)
        addNum("hWheelMove", 10, 500)
        addSeparator(rMenu)
        
        addBool("quickFirst")
        addBool("quickTurn")
        
        val item = new MenuItem(pMenu, SWT.CASCADE)
        item.setText("Real Wheel Mode")
        item.setMenu(rMenu)
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
        val item = new MenuItem(menu, SWT.RADIO)
        item.setText(text)
        vhAdjusterMenuMap(text) = item
        
        item.addSelectionListener((e: SelectionEvent) => {
            if(item.getSelection)
                setVhAdjusterMethod(item.getText)
            ()
        })
    }
    
    private def createVhAdjusterMenu(pMenu: Menu) {
        val aMenu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createVhAdjusterMenuItem(aMenu, text)
        def addNum(name: String, low: Int, up: Int) = createNumberMenuItem(aMenu, name, low, up)
        def addBool(name: String) = createBoolMenuItem(aMenu, name)
        
        createOnOffMenuItem(aMenu, "vhAdjusterMode")
        boolMenuMap("vhAdjusterMode").setEnabled(Scroll.horizontal)
        addSeparator(aMenu)
        
        add("Fixed")
        add("Switching")
        addSeparator(aMenu)
        
        addBool("firstPreferVertical")
        addNum("firstMinThreshold", 1, 10)
        addNum("switchingThreshold", 10, 500)
        
        val item = new MenuItem(pMenu, SWT.CASCADE)
        item.setText("VH Adjuster")
        item.setMenu(aMenu)
    }
    
    private val dpiCorrectionMenuMap = new HashMap[String, MenuItem]
    
    private def toString2F(d: Double) =
        ("%.2f" format d)
    
    private def addDpiCorrectionMenuItem(menu: Menu, scale: Double) {
        val item = new MenuItem(menu, SWT.RADIO)
        val text = toString2F(scale)
        
        item.setText(text)
        dpiCorrectionMenuMap(text) = item
        
        item.addSelectionListener((e: SelectionEvent) => {
            if(item.getSelection)
                dpiCorrection = scale
            ()
        })
    }
    
    private def createDpiCorrectionMenu(pMenu: Menu) {
        val dMenu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(scale: Double) = addDpiCorrectionMenuItem(dMenu, scale)
        
        add(1.00)
        add(1.25)
        add(1.50)
        add(1.75)
        add(2.00)
        
        val item = new MenuItem(pMenu, SWT.CASCADE)
        item.setText("DPI Correction")
        item.setMenu(dMenu)        
    }
    
    private val keyboardMenuMap = new HashMap[String, MenuItem]
    
    private def setTargetVKCode(name: String) {
        logger.debug(s"setTargetVKCode: $name")
        targetVKCode = Keyboard.getVKCode(name)
    }
    
    private def createKeyboardMenuItem(menu: Menu, text: String) {
        val item = new MenuItem(menu, SWT.RADIO)
        item.setText(text)
        val name = textToName(text)
        keyboardMenuMap(name) = item
        
        item.addSelectionListener((e: SelectionEvent) => {
            if (item.getSelection) {
                unselectOtherItems(keyboardMenuMap, name)
                setTargetVKCode(name)
            }
        })
    }
    
    private def createKeyboardMenu(pMenu: Menu) {
        val kMenu = new Menu(systemShell, SWT.DROP_DOWN)
        def add(text: String) = createKeyboardMenuItem(kMenu, text)
        
        createOnOffMenuItem(kMenu, "keyboardHook", Hook.setOrUnsetKeyboardHook)
        addSeparator(kMenu)
        
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
        addSeparator(kMenu)
        
        add("None")
        
        val item = new MenuItem(pMenu, SWT.CASCADE)
        item.setText("Keyboard")
        item.setMenu(kMenu)
    }
    
    private val DEFAULT_DEF = Properties.DEFAULT_DEF
    
    private def setProperties(name: String) {
        if (selectedProperties != name) {
            logger.debug(s"setProperties: $name")
        
            selectedProperties = name
            loadProperties
            resetMenuItems
        }
    }
    
    private def addPropertiesMenu(menu: Menu, name: String) {
        val item = new MenuItem(menu, SWT.RADIO)
        item.setText(name)
        item.setSelection(name == selectedProperties)
        
        item.addListener(SWT.Selection, (e: Event) => {
            if (item.getSelection) {
                storeProperties
                setProperties(name)
            }
        })
    }
    
    private def addDefaultPropertiesMenu(menu: Menu) {
        addPropertiesMenu(menu, DEFAULT_DEF)
    }
    
    private def addPropertiesMenu(menu: Menu, file: File) {
        addPropertiesMenu(menu, Properties.getUserDefName(file))
    }
    
    private def createOpenDirMenuItem(menu: Menu, dir: String) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Open Dir")
        item.addListener(SWT.Selection, (e: Event) => {
            val desk = java.awt.Desktop.getDesktop
            desk.browse(new File(dir).toURI())
        })
    }
    
    private def isValidPropertiesName(name: String) =
        (name != DEFAULT_DEF) && !(name.startsWith("--"))
    
    private def createAddPropertiesMenuItem(menu: Menu) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Add")
        item.addListener(SWT.Selection, (e: Event) => {            
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
                        Dialog.errorMessage(systemShell, s"Invalid Name: $name", "Name Error")
                })
            }
            catch {
                case e: Exception =>
                    Dialog.errorMessage(systemShell, e)
            }
        })
    }
    
    private def createDeletePropertiesMenuItem(menu: Menu) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Delete")
        val name = selectedProperties
        item.setEnabled(name != DEFAULT_DEF)
        item.addListener(SWT.Selection, (e: Event) => {            
            try {
                if (Dialog.openYesNoMessage(systemShell, s"Delete the '$name' properties?")) {
                    Properties.delete(name)
                    setProperties(DEFAULT_DEF)
                }
            }
            catch {
                case e: Exception =>
                    Dialog.errorMessage(systemShell, e)
            }
        })
    }
    
    private def createPropertiesMenu(pMenu: Menu) {
        val sMenu = new Menu(systemShell, SWT.DROP_DOWN)
        def addDefault = addDefaultPropertiesMenu(sMenu)
        def add(f: File) = addPropertiesMenu(sMenu, f)
        
        sMenu.addMenuListener(new MenuListener() {
            override def menuHidden(e: MenuEvent) {}
            override def menuShown(e: MenuEvent) {
                sMenu.getItems.foreach(_.dispose)
                
                createReloadPropertiesMenu(sMenu)
                createSavePropertiesMenu(sMenu)
                addSeparator(sMenu)
        
                createOpenDirMenuItem(sMenu, Properties.USER_DIR)
                createAddPropertiesMenuItem(sMenu)
                createDeletePropertiesMenuItem(sMenu)
                addSeparator(sMenu)
                
                addDefault
                addSeparator(sMenu)
                
                Properties.getPropFiles.foreach(add)
            }
        })
        
        
        val item = new MenuItem(pMenu, SWT.CASCADE)
        item.setText("Properties")
        item.setMenu(sMenu)
    }
    
    private def createReloadPropertiesMenu(menu: Menu) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Reload")
        item.addListener(SWT.Selection, (e: Event) => {
            loadProperties
            resetMenuItems
        })        
    }
    
    private def createSavePropertiesMenu(menu: Menu) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Save")
        item.addListener(SWT.Selection, (e: Event) => {
            storeProperties
        })  
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
        item.addListener(SWT.Selection, (e: Event) => {
            boolMenuMap("vhAdjusterMode").setEnabled(item.getSelection)
        })
    }
    
    private def createReverseScrollMenu(menu: Menu) = {
        createBoolMenuItem(menu, "reverseScroll", "Reverse Scroll (Flip)")
    }
    
    private def createSwapScrollMenu(menu: Menu) = {
        createBoolMenuItem(menu, "swapScroll", "Swap Scroll (V.H)")
    }
    
    private var passModeMenuItem: MenuItem = null
    
    private def createPassModeMenu(menu: Menu) {
        val item = new MenuItem(menu, SWT.CHECK)
        item.setText("Pass Mode")
        item.addListener(SWT.Selection, makeSetBooleanEvent("passMode"))
        passModeMenuItem = item
    }
    
    private def createInfoMenu(menu: Menu) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Info")
        item.addListener(SWT.Selection, (e: Event) => {
            val nameVer = s"Name: $PROGRAM_NAME / Version: $PROGRAM_VERSION\n\n"
            val jVer = s"java.version: ${System.getProperty("java.version")}\n"
            val osArch = s"os.arch: ${System.getProperty("os.arch")}\n"
            val dataModel = s"sun.arch.data.model: ${System.getProperty("sun.arch.data.model")}"    

            val mb = new MessageBox(systemShell, SWT.OK | SWT.ICON_INFORMATION)
            mb.setText("Info")
            mb.setMessage(nameVer + jVer + osArch + dataModel)
            mb.open()
            ()
        })
    }
    
    def exitAction(e: Event) {
        trayItem.setVisible(false)
        W10Wheel.exitMessageLoop
    }
    
    private def createExitMenu(menu: Menu) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Exit")
        item.addListener(SWT.Selection, exitAction _)
    }
    
    private def createTrayItem(menu: Menu) = {
        val tray = new TrayItem(Display.getDefault.getSystemTray, SWT.NONE)
        tray.setToolTipText(getTrayText(false))
        tray.setImage(getTrayIcon(false))
        
        tray.addListener(SWT.MenuDetect, (e: Event) => menu.setVisible(true))        
        tray.addListener(SWT.DefaultSelection, (e: Event) => Pass.toggleMode)
        
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
        createDpiCorrectionMenu(menu)
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
        
        resetMenuItems
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
    
    private def setDpiCorrectionOfProperty {
        try {
            dpiCorrection = prop.getDouble("dpiCorrection")
        }
        catch {
            case e: NoSuchElementException => logger.warn(s"Not found: ${e.getMessage}")
            case e: scala.MatchError => logger.warn(s"Match error: ${e.getMessage}")     
        }
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
            
            setDpiCorrectionOfProperty
            
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
            check("dpiCorrection", toString2F(dpiCorrection)) ||
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
            
            prop.setDouble("dpiCorrection", dpiCorrection)
            
            BooleanNames.foreach(n => prop.setBoolean(n, getBooleanOfName(n)))
            NumberNames.foreach(n => prop.setInt(n, getNumberOfName(n)))
            
            prop.store(getSelectedPropertiesPath)
        }
        catch {
            case e: Exception => logger.warn(s"store: ${e.toString}")
        }
    }
}
