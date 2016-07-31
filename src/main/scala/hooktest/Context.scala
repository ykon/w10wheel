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
import org.eclipse.swt.graphics.Point
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout

//implicit
import scala.language.implicitConversions

import java.util.Properties
import java.io.FileNotFoundException
import java.io.{ File, FileInputStream, FileOutputStream }

import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }
import com.sun.jna.platform.win32.WinUser.{ KBDLLHOOKSTRUCT => KHookInfo }

import scala.collection.mutable.HashMap
import java.util.NoSuchElementException

object Context {
    val PROGRAM_NAME = "W10Wheel"
    val PROGRAM_VERSION = "1.4"
    val ICON_NAME = "icon_016.png"
    val logger = Logger(LoggerFactory.getLogger(PROGRAM_NAME))
    lazy val systemShell = W10Wheel.shell
    
    @volatile private var firstTrigger: Trigger = LRTrigger() // default
    @volatile private var pollTimeout = 300 // default
    @volatile private var passMode = false
    @volatile private var processPriority: Windows.Priority = Windows.AboveNormal() //default
    @volatile private var sendMiddleClick = false // default
    
    @volatile private var keyboardHook = false // default
    @volatile private var targetVKCode = Keyboard.getVKCode("VK_NONCONVERT") // default
    
    def isKeyboardHook =
        keyboardHook
        
    def getTargetVKCode =
        targetVKCode
        
    def isTriggerKey(ke: KeyboardEvent) =
        ke.vkCode == targetVKCode
    
    def isSendMiddleClick =
        sendMiddleClick
    
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
    
    /*
    private object Skip {
        @volatile var rightDown = false
        @volatile var rightUp = false
        @volatile var leftDown = false
        @volatile var leftUp = false
        @volatile var middleDown = false
        @volatile var middleUp = false
    }
    */
    
    private object Scroll {
        @volatile private var mode = false
        @volatile private var stime = 0
        @volatile private var sx = 0
        @volatile private var sy = 0
        @volatile var locktime = 300 // default
        @volatile var cursorChange = true // default
        @volatile var reverse = false // default
        @volatile var horizontal = true // default
        @volatile var draggedLock = false // default
        
        // Vertical <-> Horizontall
        @volatile var swap = false // default 
        
        def start(info: HookInfo) {
            if (RealWheel.mode)
                Windows.startWheelCount
            
            stime = info.time
            sx = info.pt.x
            sy = info.pt.y
            mode = true
            
            if (cursorChange && !firstTrigger.isDrag)
                Windows.changeCursor
        }
        
        def start(kinfo: KHookInfo) {
            if (RealWheel.mode)
                Windows.startWheelCount
            
            stime = kinfo.time
            val pt = Windows.getCursorPos
            sx = pt.x
            sy = pt.y
            mode = true
            
            if (cursorChange)
                Windows.changeCursor
        }
        
        def exit = {
            mode = false
            
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
    def isPassMode = passMode
        
    /*
    def checkSkip(me: MouseEvent): Boolean = {
        if (!Windows.isResendEvent(me))
            return false;
        
        val res = me match {
            case LeftDown(_) => Skip.leftDown
            case LeftUp(_) => Skip.leftUp
            case RightDown(_) => Skip.rightDown
            case RightUp(_) => Skip.rightUp
            case MiddleDown(_) => Skip.middleDown
            case MiddleUp(_) => Skip.middleUp
        }
        
        setSkip(me, false)
        res
    }
    
    def setSkip(me: MouseEvent, enabled: Boolean): Unit = me match {    
        case LeftDown(_) => Skip.leftDown = enabled
        case LeftUp(_) => Skip.leftUp = enabled
        case RightDown(_) => Skip.rightDown = enabled
        case RightUp(_) => Skip.rightUp = enabled
        case MiddleDown(_) => Skip.middleDown = enabled
        case MiddleUp(_) => Skip.middleUp = enabled
    }
    
    def setSkip(mc: MouseClick, enabled: Boolean): Unit = mc match {
        case LeftClick(_) => {
            setSkip(LeftDown(mc.info), enabled)
            setSkip(LeftUp(mc.info), enabled)
        }
        case RightClick(_) => {
            setSkip(RightDown(mc.info), enabled)
            setSkip(RightUp(mc.info), enabled)
        }
        case MiddleClick(_) => {
            setSkip(MiddleDown(mc.info), enabled)
            setSkip(MiddleUp(mc.info), enabled)
        }
    }
    */
    
    object LastFlags {
        // R = Resent
        @volatile private var ldR = false
        @volatile private var rdR = false
        
        // S = Suppressed
        @volatile private var ldS = false
        @volatile private var rdS = false
        @volatile private var sdS = false
        
        @volatile private var kdSMap = new HashMap[Int, Boolean]
        
        def setResent(down: MouseEvent) = down match {
            case LeftDown(_) => ldR = true
            case RightDown(_) => rdR = true
            case _ => {}
        }
        
        def isDownResent(up: MouseEvent) = up match {
            case LeftUp(_) => ldR
            case RightUp(_) => rdR
        }
        
        def setSuppressed(down: MouseEvent) = down match {
            case LeftDown(_) => ldS = true
            case RightDown(_) => rdS = true 
            case MiddleDown(_) | X1Down(_) | X2Down(_) => sdS = true
            case _ => {}
        }
        
        def setSuppressed(down: KeyboardEvent) = down match {
            case KeyDown(_) => kdSMap(down.vkCode) = true
            case _ => {}
        }
        
        def isDownSuppressed(up: MouseEvent) = up match {
            case LeftUp(_) => ldS
            case RightUp(_) => rdS
            case MiddleUp(_) | X1Up(_) | X2Up(_) => sdS
        }
        
        def isDownSuppressed(up: KeyboardEvent) = up match {
            case KeyUp(_) => kdSMap.getOrElse(up.vkCode, false)
        }
        
        def reset(down: MouseEvent) = down match {
            case LeftDown(_) => ldR = false; ldS = false
            case RightDown(_) => rdR = false; rdS = false
            case MiddleDown(_) | X1Down(_) | X2Down(_) => sdS = false
        }
        
        def reset(down: KeyboardEvent) = down match {
            case KeyDown(_) => kdSMap(down.vkCode) = false
        }
    }
    
    def isTrigger(e: Trigger) = firstTrigger == e
    def isLRTrigger = isTrigger(LRTrigger())
        
    def isTriggerEvent(e: MouseEvent): Boolean = isTrigger(Mouse.getTrigger(e))
    
    def isDragTriggerEvent(e: MouseEvent): Boolean = e match {
        case LeftDown(_) | LeftUp(_) => isTrigger(LeftDragTrigger())
        case RightDown(_) | RightUp(_) => isTrigger(RightDragTrigger())
        case MiddleDown(_) | MiddleUp(_) => isTrigger(MiddleDragTrigger())
        case X1Down(_) | X1Up(_) => isTrigger(X1DragTrigger())
        case X2Down(_) | X2Up(_) => isTrigger(X2DragTrigger())
    }
    
    def isSingleTrigger = firstTrigger.isSingle
    def isDragTrigger = firstTrigger.isDrag
    
    // http://stackoverflow.com/questions/3239106/scala-actionlistener-anonymous-function-type-mismatch
    /*
    implicit def toActionListener(f: ActionEvent => Unit) = new ActionListener {
        override def actionPerformed(e: ActionEvent) { f(e) }
    }
    
    implicit def toItemListener(f: ItemEvent => Unit) = new ItemListener {
        override def itemStateChanged(e: ItemEvent) { f(e) }
    }
    */
    
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
        
    private def resetMenuItem: Unit = {
        resetTriggerMenuItems
        resetAccelMenuItems
        resetPriorityMenuItems
        resetNumberMenuItems
        resetBoolMenuItems
        resetKeyboardMenuItems
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
    
    private class NumberInputDialog(name: String, low: Int, up: Int) extends Dialog(systemShell) {
        private def createSpinner(shell: Shell) = {
            val spinner = new Spinner(shell, SWT.BORDER)
            spinner.setMinimum(low)
            spinner.setMaximum(up)
            spinner.setSelection(getNumberOfName(name))
            spinner
        }
        
        private def createShell = {
            val shell = new Shell(getParent, SWT.TITLE | SWT.BORDER | SWT.APPLICATION_MODAL | SWT.ON_TOP)
            shell.setText("Set Number")
            shell.setLayout(new GridLayout(2, false))
            shell
        }
        
        private def createLabel(shell: Shell) = {
            val label = new Label(shell, SWT.NULL)
            label.setText(s"$name ($low - $up): ")
            label
        }
        
        private def createButton(shell: Shell, text: String, gridData: GridData) = {
            val button = new Button(shell, SWT.PUSH)
            button.setText(text)
            button.setLayoutData(gridData)
            button
        }
        
        private def makeKeyAdapter(ok: Button, cancel: Button) = {
            new KeyAdapter {
                override def keyReleased(e: KeyEvent) {
                    if (e.character == SWT.CR)
                        ok.notifyListeners(SWT.Selection, null)
                    else if (e.keyCode == SWT.ESC)
                        cancel.notifyListeners(SWT.Selection, null)
                }
            }
        }
        
        private def messageLoop(shell: Shell) = {
            val display = getParent.getDisplay
            while (!shell.isDisposed) {
                if (!display.readAndDispatch)
                    display.sleep
            }            
        }
        
        private def setLocation(shell: Shell) {
            val pt = Display.getDefault.getCursorLocation
            shell.setLocation(pt.x - (shell.getBounds.width / 2), pt.y - (shell.getBounds.height / 2))                
        }
        
        private def makeButtonGridData = {
            val gridData = new GridData(GridData.HORIZONTAL_ALIGN_END)
            gridData.widthHint = 70
            gridData
        }
        
        private def isValidNumber(res: Int) =
            res >= low && res <= up
            
        private def errorMessage(input: Int) {
            val mb = new MessageBox(systemShell, SWT.OK | SWT.ICON_ERROR)
            mb.setText("Error")
            mb.setMessage(s"Invalid Number: $input")
            mb.open()
        }
        
        def open: Option[Int] = {
            val shell = createShell
            val label = createLabel(shell)
            val spinner = createSpinner(shell)
            
            val gridData = makeButtonGridData
            val okButton = createButton(shell, "OK", gridData)
            val cancelButton = createButton(shell, "Cancel", gridData)
            
            spinner.addKeyListener(makeKeyAdapter(okButton, cancelButton))
            
            var res: Option[Int] = None
            okButton.addListener(SWT.Selection, (e: Event) => {
                val input = spinner.getSelection
                
                if (!isValidNumber(input))
                    errorMessage(input)
                else {
                    res = Some(input)
                    shell.dispose
                }
            })
            
            cancelButton.addListener(SWT.Selection, (e: Event) => shell.dispose)
            shell.addListener(SWT.Traverse, (e: Event) => if (e.detail == SWT.TRAVERSE_ESCAPE) e.doit = false)

            shell.pack
            setLocation(shell)    
            shell.open
            
            messageLoop(shell)
            res
        }
    }
    
    // http://www.java2s.com/Code/Java/SWT-JFace-Eclipse/NumberInputDialog.htm
    private def openNumberInputDialog(name: String, low: Int, up: Int) = {
        val dialog = new NumberInputDialog(name, low, up)
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
        
    private def createOnOffMenuItem(menu: Menu, vname: String, action: Boolean => Unit = _ => {}): Unit = {
        def getOnOff(b: Boolean) = if (b) "ON" else "OFF"
        val item = new MenuItem(menu, SWT.CHECK)
        item.setText(getOnOff(getBooleanOfName(vname)))
        boolMenuMap(vname) = item
        
        item.addListener(SWT.Selection, (e: Event) => {
            val b = item.getSelection
            item.setText(getOnOff(b))
            setBooleanOfName(vname, b)
            action(b)
        })
    }
    
    private def createBoolMenuItem(menu: Menu, vName: String, mName: String, enabled: Boolean = true) {
        val item = new MenuItem(menu, SWT.CHECK)
        item.setText(mName)
        item.setEnabled(enabled)
        item.addListener(SWT.Selection, makeSetBooleanEvent(vName))
        boolMenuMap(vName) = item
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
        
        add("VK_PAUSE (Pause)")
        add("VK_CAPITAL (Caps Lock)")
        add("VK_CONVERT (Henkan)")
        add("VK_NONCONVERT (Muhenkan)")
        add("VK_SNAPSHOT (Print Screen)")
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
        
        val item = new MenuItem(pMenu, SWT.CASCADE)
        item.setText("Keyboard")
        item.setMenu(kMenu)
    }
    
    private def setUnhook: Unit =
        W10Wheel.unhook.success(true)
    
    private def createReloadPropertiesMenu(menu: Menu) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Reload Properties")
        item.addListener(SWT.Selection, (e: Event) => {
            loadProperties
            resetMenuItem
        })        
    }
    
    private def createSavePropertiesMenu(menu: Menu) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Save Properties")
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
        createBoolMenuItem(menu, "horizontalScroll", "Horizontal Scroll")
    }
    
    private def createReverseScrollMenu(menu: Menu) = {
        createBoolMenuItem(menu, "reverseScroll", "Reverse Scroll (Flip)")
    }
    
    private def createSwapScrollMenu(menu: Menu) = {
        createBoolMenuItem(menu, "swapScroll", "Swap Scroll (V.H)")
    }
    
    private def createPassModeMenu(menu: Menu) {
        val item = new MenuItem(menu, SWT.CHECK)
        item.setText("Pass Mode")
        item.addListener(SWT.Selection, makeSetBooleanEvent("passMode"))
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
    
    private def createExitMenu(menu: Menu) {
        val item = new MenuItem(menu, SWT.PUSH)
        item.setText("Exit")
        item.addListener(SWT.Selection, (e: Event) => setUnhook)        
    }
    
    def getIconImage = {
        val stream = getClass.getClassLoader.getResourceAsStream(ICON_NAME)
        new Image(Display.getDefault, stream)
    }
    
    def createTrayItem(menu: Menu) = {
        val trayItem = new TrayItem(Display.getDefault.getSystemTray, SWT.NONE)
        trayItem.setToolTipText(PROGRAM_NAME)
        trayItem.setImage(getIconImage)
        
        trayItem.addListener(SWT.MenuDetect, (e: Event) => menu.setVisible(true))        
        trayItem.addListener(SWT.DefaultSelection, (e: Event) => setUnhook)
        
        trayItem
    }
    
    def setSystemTray {
        val menu = new Menu(systemShell, SWT.POP_UP)
        createTrayItem(menu)
        
        createTriggerMenu(menu)
        createAccelTableMenu(menu)
        createPriorityMenu(menu)
        createNumberMenu(menu)
        createRealWheelModeMenu(menu)
        createKeyboardMenu(menu)
        addSeparator(menu)
        
        createReloadPropertiesMenu(menu)
        createSavePropertiesMenu(menu)
        addSeparator(menu)
        
        createCursorChangeMenu(menu)
        createHorizontalScrollMenu(menu)
        createReverseScrollMenu(menu)
        createSwapScrollMenu(menu)
        createPassModeMenu(menu)
        addSeparator(menu)
        
        createInfoMenu(menu)
        createExitMenu(menu)
        
        //systemShell.h
        
        resetMenuItem
    }
    
    val PROP_NAME = s".$PROGRAM_NAME.properties" 
    val USER_DIR = System.getProperty("user.home")  
        
    private def getPropertiesFile =
        new File(USER_DIR, PROP_NAME)
    
    private def getPropertiesInput =
        new FileInputStream(getPropertiesFile)
    
    private def getPropertiesOutput =
        new FileOutputStream(getPropertiesFile)
    
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
        
        val smc = "sendMiddleClick"
        
        setEnabledMenu(boolMenuMap, "sendMiddleClick", res.isSingle)
        setEnabledMenu(boolMenuMap, "draggedLock", res.isDrag)
        
        //EventHandler.changeTrigger
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
    
    private def setDefaultPriority {
        logger.debug("setDefaultPriority")
        Windows.setPriority(processPriority)
    }
    
    private class SProperties extends Properties {
        // http://stackoverflow.com/questions/17011108/how-can-i-write-java-properties-in-a-defined-order
        override def keys(): java.util.Enumeration[Object] = synchronized {
            java.util.Collections.enumeration(new java.util.TreeSet[Object](super.keySet))
        }
        
        override def load(inStream: java.io.InputStream) = synchronized {
            super.load(inStream)
            inStream.close
        }
        
        override def load(reader: java.io.Reader) = synchronized {
            super.load(reader)
            reader.close
        }
        
        def getPropertyE(key: String): String = synchronized {
            val res = super.getProperty(key)
            if (res != null) res else throw new NoSuchElementException(key)
        }
        
        def getString(key: String) = synchronized {
            getPropertyE(key)
        }
        
        def getInt(key: String) = synchronized {
            getString(key).toInt
        }
        
        def getBoolean(key: String) = synchronized {
            getString(key).toBoolean
        }
        
        def getArray(key: String): Array[String] = synchronized {
            getString(key).split(",").map(_.trim).filter(!_.isEmpty)
        }
        
        def getIntArray(key: String): Array[Int] = synchronized {
            getArray(key).map(_.toInt)
        }
        
        def getDoubleArray(key: String): Array[Double] = synchronized {
            getArray(key).map(_.toDouble)
        }
        
        def store(out: java.io.OutputStream) = synchronized {
            super.store(out, null)
            out.close
        }
        
        def store(writer: java.io.Writer) = synchronized {
            super.store(writer, null)
            writer.close
        }
        
        def setInt(key: String, n: Int) = synchronized {
            super.setProperty(key, n.toString)
        }
        
        def setBoolean(key: String, b: Boolean) = synchronized {
            super.setProperty(key, b.toString)
        }
    }
    
    private val prop = new SProperties
    private var loaded = false
    
    def loadProperties = {
        loaded = true
        try {
            prop.load(getPropertiesInput)
            
            setTriggerOfProperty
            setAccelOfProperty
            setCustomAccelOfProperty
            setPriorityOfProperty
            setVKCodeOfProperty
            
            BooleanNames.foreach(n => setBooleanOfProperty(n))
            Hook.setOrUnsetKeyboardHook(keyboardHook)
            
            setNumberOfProperty("pollTimeout", 50, 500)
            setNumberOfProperty("scrollLocktime", 150, 500)
            setNumberOfProperty("verticalThreshold" , 0, 500)
            setNumberOfProperty("horizontalThreshold", 0, 500)
            
            setNumberOfProperty("wheelDelta", 10, 500)
            setNumberOfProperty("vWheelMove", 10, 500)
            setNumberOfProperty("hWheelMove", 10, 500)
        }
        catch {
            case _: FileNotFoundException => {
                logger.debug("Properties file not found")
                setDefaultPriority                
            }
            case e: Exception => logger.warn(s"load: ${e.toString}")
        }
    }
    
    private def isChangedBoolean =
        BooleanNames.map(n => prop.getBoolean(n) != getBooleanOfName(n)).contains(true)
        
    private def isChangedNumber =
        NumberNames.map(n => prop.getInt(n) != getNumberOfName(n)).contains(true)
    
    private def isChangedProperties: Boolean = {
        try {
            prop.load(getPropertiesInput)
            
            prop.getString("firstTrigger") != firstTrigger.name ||
            prop.getString("accelMultiplier") != Accel.multiplier.name ||
            prop.getString("processPriority") != processPriority.name ||
            prop.getString("targetVKCode") != Keyboard.getName(targetVKCode) ||
            isChangedBoolean || isChangedNumber
        }
        catch {
            case _: FileNotFoundException => logger.debug("First write"); true
            case e: NoSuchElementException => logger.warn(s"Not found: ${e.getMessage}"); true
            case e: Exception => logger.warn(s"isChanged: ${e.toString}"); true
        }
    }
    
    private val NumberNames: Array[String] = {
        Array("pollTimeout", "scrollLocktime", 
              "verticalThreshold", "horizontalThreshold",
              "wheelDelta", "vWheelMove", "hWheelMove")
    }
    
    private val BooleanNames: Array[String] = {
        Array("realWheelMode", "cursorChange",
              "horizontalScroll", "reverseScroll",
              "quickFirst", "quickTurn",
              "accelTable", "customAccelTable",
              "draggedLock", "swapScroll",
              "sendMiddleClick", "keyboardHook"
        )
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
            case "passMode" => passMode = b
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
        case "passMode" => passMode
    }
    
    def storeProperties: Unit = {          
        try {   
            if (!loaded || !isChangedProperties) {
                logger.debug("Not changed properties")
                return
            }
            
            prop.setProperty("firstTrigger", firstTrigger.name)
            prop.setProperty("accelMultiplier", Accel.multiplier.name)
            prop.setProperty("processPriority", processPriority.name)
            prop.setProperty("targetVKCode", Keyboard.getName(targetVKCode))
            
            BooleanNames.foreach(n => prop.setBoolean(n, getBooleanOfName(n)))
            NumberNames.foreach(n => prop.setInt(n, getNumberOfName(n)))
            
            prop.store(getPropertiesOutput)
        }
        catch {
            case e: Exception => logger.warn(s"store: ${e.toString}")
        }
    }
}
