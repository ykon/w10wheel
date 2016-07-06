package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

//implicit
import scala.language.implicitConversions

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import java.util.Properties
import java.io.{ File, FileInputStream, FileOutputStream }
import java.awt._
import java.awt.event._
import javax.swing._
import javax.imageio.ImageIO

import com.sun.jna.platform.win32.WinDef.HCURSOR
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

object Context {
	val PROGRAM_NAME = "W10Wheel"
	val PROGRAM_VERSION = "0.2"
	val logger = Logger(LoggerFactory.getLogger(PROGRAM_NAME))
	
	@volatile private var firstTrigger: Trigger = LRTrigger() // default
	@volatile private var pollTimeout = 300 // default
	@volatile private var cursorChange = true // default
	@volatile private var passMode = false
	
	private object Vertical {
		@volatile var accel = 0  // default
		@volatile var threshold = 0 // default
	}
	
	private object Horizontal {
		@volatile var scroll = true // default
		@volatile var accel = 0 // default
		@volatile var threshold = 50 // default
	}
	
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
		@volatile var mode = false
		@volatile var stime = 0
		@volatile var sx = 0
		@volatile var sy = 0
		@volatile var locktime = 300 // default
	}
	
	private object ResetMenu {
		var cursorChange: CheckboxMenuItem = null
		var horizontalScroll: CheckboxMenuItem = null
		var trigger: Menu = null
		var number: Menu = null
	}
	
	def getPollTimeout =
		pollTimeout
		
	def getHorizontalScroll =
		Horizontal.scroll
		
	def getVerticalAccel =
		Vertical.accel
		
	def getHorizontalAccel =
		Horizontal.accel
		
	def getVerticalThreshold =
		Vertical.threshold
		
	def getHorizontalThreshold =
		Horizontal.threshold
	
	def getScrollStartPoint =
		(Scroll.sx, Scroll.sy)
	
	def startScrollMode(info: HookInfo) {
		Scroll.stime = info.time
		Scroll.sx = info.pt.x
		Scroll.sy = info.pt.y
		Scroll.mode = true
		
		if (cursorChange && !isLROnlyTrigger)
			Windows.changeCursor
	}
	
	def isScrollMode =
		Scroll.mode
		
	def isCursorChange =
		cursorChange
	
	def exitScrollMode = {
		Scroll.mode = false
		
		if (cursorChange)
			Windows.restoreCursor
	}
		
	def checkTimeExitScroll(time: Int) = {
		val dt = time - Scroll.stime
		logger.debug(s"scroll time: $dt ms")
		dt > Scroll.locktime
	}
	
	def isPassMode =
		passMode
		
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
		
	def isTrigger(e: Trigger) =
		firstTrigger == e
		
	def isTrigger(e: MouseEvent): Boolean =
		isTrigger(Mouse.getTrigger(e))
		
	def isLRTrigger =
		isTrigger(LRTrigger())
		
	def isTriggerLROnly(e: MouseEvent): Boolean = e match {
		case LeftDown(_) | LeftUp(_) => isTrigger(LeftOnlyTrigger())
		case RightDown(_) | RightUp(_) => isTrigger(RightOnlyTrigger())
	}
		
	def isSingleTrigger = firstTrigger match {
		case MiddleTrigger() | X1Trigger() | X2Trigger() => true
		case _ => false
	}
	
	def isLROnlyTrigger = firstTrigger match {
		case LeftOnlyTrigger() | RightOnlyTrigger() => true
		case _ => false
	}
	
	// http://stackoverflow.com/questions/3239106/scala-actionlistener-anonymous-function-type-mismatch
	implicit def toActionListener(f: ActionEvent => Unit) = new ActionListener {
		override def actionPerformed(e: ActionEvent) { f(e) }
	}
	
	implicit def toItemListener(f: ItemEvent => Unit) = new ItemListener {
		override def itemStateChanged(e: ItemEvent) { f(e) }
	}
	
	private def isSelected(e: ItemEvent) =
		(e.getStateChange == ItemEvent.SELECTED)
		
	private def setCursorChange(e: ItemEvent) = {
		val b = isSelected(e)
		logger.debug(s"setCursorChange: $b")
		cursorChange = b
	}
		
	private def setHorizontalScroll(e: ItemEvent) = {
		val b = isSelected(e)
		logger.debug(s"setHorizontalScroll: $b")
		Horizontal.scroll = isSelected(e)
	}

	private def setPassMode(e: ItemEvent) = {
		val b = isSelected(e)
		logger.debug(s"setPassMode: $b")
		passMode = b
	}
		
	private def resetTriggerItems: Unit = {
		val items = getCheckboxMenuItems(ResetMenu.trigger)
		items.foreach(i => {
			val b = firstTrigger == Mouse.getTrigger(labelToName(i.getLabel))
			i.setState(b)
		})
	}
	
	private def getNumberItems =
	    getMenuItems(ResetMenu.number).filter(_.getLabel != "-")
	
	private def resetNumberItems: Unit = {
	    val items = getNumberItems
	    items.foreach(i => {
	        val name = labelToName(i.getLabel)
	        val n = getNumberOfName(name)
	        i.setLabel(makeNumberLabel(name, n))
	    })
	}
		
	private def resetMenuItem: Unit = {
		ResetMenu.cursorChange.setState(cursorChange)
		ResetMenu.horizontalScroll.setState(Horizontal.scroll)
		resetTriggerItems
		resetNumberItems
	}
	
	private def getMenuItems(m: Menu) =
	    (0 until m.getItemCount).map(m.getItem)
	    
	private def getCheckboxMenuItems(m: Menu) =
	    getMenuItems(m).map(_.asInstanceOf[CheckboxMenuItem])
	
	private def disableOtherItem(e: ItemEvent): Unit = {
		val label = e.getItem.toString()
		val items = getCheckboxMenuItems(ResetMenu.trigger)
	    items.filter(_.getLabel != label).foreach(_.setState(false))
	}
	
	private def labelToName(s: String): String =
		s.split(" ")(0)
	
	private def setTrigger(e: ItemEvent): Unit = {
		disableOtherItem(e)
		val name = labelToName(e.getItem.toString())
		setTrigger(name)
	}
	
	private def createTriggerItem(label: String) = {
	    val item = new CheckboxMenuItem(label)
	    item.addItemListener(setTrigger _)
	    item
	}
	
	private def createTriggerMenu: Menu = {
		val menu = new Menu("Trigger")
		
		menu.add(createTriggerItem("LRTrigger (Left <<-->> Right)"))
		menu.add(createTriggerItem("Left (Left -->> Right)"))
		menu.add(createTriggerItem("Right (Right -->> Left)"))
		menu.add(createTriggerItem("Middle"))
		menu.add(createTriggerItem("X1"))
		menu.add(createTriggerItem("X2"))
		menu.add(createTriggerItem("LeftOnly"))
		menu.add(createTriggerItem("RightOnly"))
		
		ResetMenu.trigger = menu
		
		menu
	}
	
	// http://stackoverflow.com/questions/10107422/jspinner-in-joptionpane
	// http://tomorrowscode.blogspot.com/2010/02/javaswingjspinner.html
	// http://www.javadrive.jp/tutorial/jspinner/index21.html
	
	private def setDigitOnly(js: JSpinner) {
		val jtf = js.getEditor().getComponents()(0).asInstanceOf[JFormattedTextField]
		jtf.addKeyListener(new KeyAdapter() {
			override def keyTyped(e: KeyEvent) {
				if (!Character.isDigit(e.getKeyChar))
					e.consume 
			}
		})
	}
	
	private def makeNumberLabel(name: String, n: Int) =
	    s"$name = $n"
	
	private def setNumberOfSpinner(e: ActionEvent, mi: MenuItem, lowLimit: Int, upLimit: Int): Unit = {
	    val name = labelToName(mi.getLabel)
		val sModel = new SpinnerNumberModel(getNumberOfName(name), lowLimit, upLimit, 1);
		val spinner = new JSpinner(sModel);
		setDigitOnly(spinner)
		
		val title = s"$name ($lowLimit - $upLimit)"
		val option = JOptionPane.showOptionDialog(null, spinner, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, null, null);

		if (option == JOptionPane.OK_OPTION) {
			val n = sModel.getNumber.intValue
			
	    	setNumberOfName(name, n)
	    	mi.setLabel(makeNumberLabel(name, n))
		}
	}
	
	private def createNumberMenuItem(name: String, lowLimit: Int, upLimit: Int): MenuItem = {
	    val n = getNumberOfName(name)
		val mi = new MenuItem(makeNumberLabel(name, n))
		val action = setNumberOfSpinner(_: ActionEvent, mi, lowLimit, upLimit)
		mi.addActionListener(action)
		mi
	}
	
	private def createSetNumberMenu: Menu = {
		val menu = new Menu("Set Number")
		
		menu.add(createNumberMenuItem("pollTimeout", 150, 500))
		menu.add(createNumberMenuItem("scrollLocktime", 150, 500))
		menu.addSeparator
		
		menu.add(createNumberMenuItem("verticalAccel", 0, 500))
		menu.add(createNumberMenuItem("verticalThreshold", 0, 500))
		menu.addSeparator
		
		menu.add(createNumberMenuItem("horizontalAccel", 0, 500))
		menu.add(createNumberMenuItem("horizontalThreshold", 0, 500))
		
		ResetMenu.number = menu
		menu
	}
	
	private def setUnhook(e: ActionEvent): Unit =
		W10Wheel.unhook.success(true)
		
	private def showVersion(e: ActionEvent): Unit = {
		val msg = s"Name: $PROGRAM_NAME / Version: $PROGRAM_VERSION"
		JOptionPane.showMessageDialog(null, msg, "Info", JOptionPane.INFORMATION_MESSAGE)
	}
	
	private def reloadProperties(e: ActionEvent): Unit = {
		loadProperties
		resetMenuItem
	}
	
	private def createReloadPropertiesMenuItem = {
		val item = new MenuItem("Reload Properties")
		item.addActionListener(reloadProperties _)
		item
	}
	
	private def createCursorChangeMenuItem = {
		val item = new CheckboxMenuItem("Cursor Change", cursorChange)
		ResetMenu.cursorChange = item
		item.addItemListener(setCursorChange _)
		item
	}
	
	private def createHorizontalScrollMenuItem = {
		val item = new CheckboxMenuItem("Horizontal Scroll", Horizontal.scroll)
		ResetMenu.horizontalScroll = item
		item.addItemListener(setHorizontalScroll _)
		item
	}
	
	private def createPassModeMenuItem = {
		val item = new CheckboxMenuItem("Pass Mode")
		item.addItemListener(setPassMode _)
		item
	}
	
	private def createVersionMenuItem = {
		val item = new MenuItem("Version")
		item.addActionListener(showVersion _)
		item
	}
	
	private def createExitMenuItem = {
		val item = new MenuItem("Exit")
		item.addActionListener(setUnhook _)
		item
	}
	
	def setSystemTray {
		val stream = getClass.getClassLoader.getResourceAsStream("icon_016.png")
		val icon = new TrayIcon(ImageIO.read(stream), PROGRAM_NAME)
		//icon.setImageAutoSize(true)
		
		val menu = new PopupMenu()
		
		menu.add(createTriggerMenu)
		menu.add(createSetNumberMenu)
		menu.add(createReloadPropertiesMenuItem)
		menu.addSeparator()
		
		menu.add(createCursorChangeMenuItem)
		menu.add(createHorizontalScrollMenuItem)
		menu.add(createPassModeMenuItem)
		menu.add(createVersionMenuItem)
		menu.add(createExitMenuItem)
		
		icon.setPopupMenu(menu)
		icon.addActionListener(setUnhook _)
		
		resetMenuItem
		
		SystemTray.getSystemTray.add(icon)
	}
	
	private def getPropertiesName =
		s".$PROGRAM_NAME.properties"
		
	private def getUserDir =
		System.getProperty("user.home")	
		
	private def getPropertiesFile =
		new File(getUserDir, getPropertiesName)
	
	private def getPropertiesInput =
		new FileInputStream(getPropertiesFile)
	
	private def getPropertiesOutput =
		new FileOutputStream(getPropertiesFile)
	
	private def getNumberOfProperty(name: String): Int =
		prop.getProperty(name).toInt
	
	private def setNumberOfProperty(name: String, lowlimit: Int, uplimit: Int): Unit = {
		val n = getNumberOfProperty(name)
		if (n < lowlimit || n > uplimit)
			throw new IllegalArgumentException(n.toString())
		
		setNumberOfName(name, n)
	}
	
	private def setBooleanOfProperty(name: String) {
		val b = prop.getProperty(name).toBoolean
		
		name match {
			case "cursorChange" => cursorChange = b
			case "horizontalScroll" => Horizontal.scroll = b
			case _ => throw new IllegalArgumentException()
		}
	}
	
	private def setTrigger(s: String): Unit = {
		val res = Mouse.getTrigger(s)
		logger.debug("setTrigger: " + res.getClass.getSimpleName);
		firstTrigger = res
		//EventHandler.changeTrigger
	}
	
	private def setTriggerOfProperty: Unit =
		setTrigger(prop.getProperty("firstTrigger"))
	
	private val prop = new Properties
	
	def loadProperties = {
		var input: FileInputStream = null
		try {
			input = getPropertiesInput
			prop.load(input)
			
			setTriggerOfProperty
			
			setBooleanOfProperty("cursorChange")
			setBooleanOfProperty("horizontalScroll")
			
			setNumberOfProperty("pollTimeout", 50, 500)
			setNumberOfProperty("scrollLocktime", 150, 500)
			setNumberOfProperty("verticalAccel", 0, 500)
			setNumberOfProperty("verticalThreshold" , 0, 500)
			setNumberOfProperty("horizontalAccel", 0, 500)
			setNumberOfProperty("horizontalThreshold", 0, 500)
		}
		catch {
			case e: Exception => logger.warn(e.toString())
		}
		finally {
			if (input != null)
				input.close()
		}
	}
	
	private def isChangedProperties: Boolean = {
		try {
			prop.load(getPropertiesInput)
			
			val trigger = prop.getProperty("firstTrigger")
			val r1 = trigger != firstTrigger.getClass.getSimpleName
			
			val cc = prop.getProperty("cursorChange")
			val r2 = cc.toBoolean != cursorChange
			
			val hs = prop.getProperty("horizontalScroll")
			val r3 = hs.toBoolean != Horizontal.scroll
			
			Array(r1, r2, r3).contains(true) ||
				getNumberNames.map(n => getNumberOfProperty(n) != getNumberOfName(n)).contains(true)
		}
		catch {
			case e: Exception => logger.warn(e.toString())
			true
		}
	}
	
	private def getNumberNames: Array[String] = {
		Array("pollTimeout", "scrollLocktime", 
			  "verticalAccel", "verticalThreshold",
			  "horizontalAccel", "horizontalThreshold")
	}
	
	private def setNumberOfName(name: String, n: Int): Unit = {
	    logger.debug(s"setNumber: $name = $n")
	    
	    name match {
    		case "pollTimeout" => pollTimeout = n
    		case "scrollLocktime" => Scroll.locktime = n
    		case "verticalAccel" => Vertical.accel = n
    		case "verticalThreshold" => Vertical.threshold = n
    		case "horizontalAccel" => Horizontal.accel = n
    		case "horizontalThreshold" => Horizontal.threshold = n
	    }
	}
	
	private def getNumberOfName(name: String): Int = name match {
		case "pollTimeout" => pollTimeout
		case "scrollLocktime" => Scroll.locktime
		case "verticalAccel" => Vertical.accel
		case "verticalThreshold" => Vertical.threshold
		case "horizontalAccel" => Horizontal.accel
		case "horizontalThreshold" => Horizontal.threshold
		case e => throw new IllegalArgumentException(e)
	}
	
	def storeProperties: Unit = {
		if (!isChangedProperties)
			return
		
		try {			
			prop.setProperty("firstTrigger", firstTrigger.getClass.getSimpleName)
			prop.setProperty("cursorChange", cursorChange.toString())
			prop.setProperty("horizontalScroll", Horizontal.scroll.toString())
			
			val names = getNumberNames					
			names.foreach(n => prop.setProperty(n, getNumberOfName(n).toString))
			prop.store(getPropertiesOutput, PROGRAM_NAME)
		}
		catch {
			case e: Exception => logger.warn(e.toString())
		}
	}
}