package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

//implicit
import scala.language.implicitConversions

import java.util.Properties
import java.io.{ File, FileInputStream, FileOutputStream }
import java.awt._
import java.awt.event._
import javax.swing._
import javax.swing.event._
import javax.imageio.ImageIO

import com.sun.jna.platform.win32.WinDef.HCURSOR
import win32ex.WinUserX.{ MSLLHOOKSTRUCT => HookInfo }

object Context {
	val PROGRAM_NAME = "W10Wheel"
	val PROGRAM_VERSION = "0.5"
	val ICON_NAME = "icon_016.png"
	val logger = Logger(LoggerFactory.getLogger(PROGRAM_NAME))
	
	@volatile private var firstTrigger: Trigger = LRTrigger() // default
	@volatile private var pollTimeout = 300 // default
	@volatile private var passMode = false
	@volatile private var processPriority: Windows.Priority = Windows.AboveNormal() //default
	
	private object Vertical {
		@volatile var accel = 0  // default
		@volatile var threshold = 0 // default
	}
	
	def getVerticalAccel = Vertical.accel
	def getVerticalThreshold = Vertical.threshold
	
	private object Horizontal {
		@volatile var scroll = true // default
		@volatile var accel = 0 // default
		@volatile var threshold = 50 // default
	}
	
	def isHorizontalScroll = Horizontal.scroll
	def getHorizontalAccel = Horizontal.accel	
	def getHorizontalThreshold = Horizontal.threshold
	
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
		
		def start(info: HookInfo) {
			stime = info.time
			sx = info.pt.x
			sy = info.pt.y
			mode = true
			
			if (cursorChange && !firstTrigger.isDrag)
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
	def exitScrollMode = Scroll.exit
	def isScrollMode = Scroll.isMode
	def getScrollStartPoint = Scroll.getStartPoint
	def checkExitScroll(time: Int) = Scroll.checkExit(time)
	def isReverseScroll = Scroll.reverse 
	
	private object ResetMenu {
		var cursorChange: JCheckBoxMenuItem = null
		var horizontalScroll: JCheckBoxMenuItem = null
		var reverseScroll: JCheckBoxMenuItem = null
		var trigger: JMenu = null
		var priority: JMenu = null
		var number: JMenu = null
	}
	
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
		
		def setResent(down: MouseEvent) = down match {
			case LeftDown(_) => ldR = true
			case RightDown(_) => rdR = true
		}
		
		def isDownResent(up: MouseEvent) = up match {
			case LeftUp(_) => ldR
			case RightUp(_) => rdR
		}
		
		def setSuppressed(down: MouseEvent) = down match {
			case LeftDown(_) => ldS = true 
			case RightDown(_) => rdS = true 
			case MiddleDown(_) | X1Down(_) | X2Down(_) => sdS = true
		}
		
		def isDownSuppressed(up: MouseEvent) = up match {
			case LeftUp(_) => ldS
			case RightUp(_) => rdS
			case MiddleUp(_) | X1Up(_) | X2Up(_) => sdS
		}
		
		def reset(down: MouseEvent) = down match {
			case LeftDown(_) => ldR = false; ldS = false
			case RightDown(_) => rdR = false; rdS = false
			case MiddleDown(_) | X1Down(_) | X2Down(_) => sdS = false
		}
	}
	
	//def getFirstTrigger = firstTrigger
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
	implicit def toActionListener(f: ActionEvent => Unit) = new ActionListener {
		override def actionPerformed(e: ActionEvent) { f(e) }
	}
	
	implicit def toItemListener(f: ItemEvent => Unit) = new ItemListener {
		override def itemStateChanged(e: ItemEvent) { f(e) }
	}
	
	private def isSelected(e: ItemEvent) =
		e.getStateChange == ItemEvent.SELECTED
		
	private def setBooleanOfEvent(name: String, e: ItemEvent) = {
		val b = isSelected(e)
		setBooleanOfName(name, b)
	}
		
	private def resetTriggerItem: Unit = {
		val items = getRadioButtonMenuItems(ResetMenu.trigger)
		items.find(i => isTrigger(Mouse.getTrigger(textToName(i.getText)))).get.setSelected(true)
	}
	
	private def resetPriorityItem: Unit = {
		val items = getRadioButtonMenuItems(ResetMenu.priority)
		items.find(i => getPriority(i.getText) == processPriority).get.setSelected(true)
	}
	
	private def getNumberItems =
	    getMenuItems(ResetMenu.number).map(_.asInstanceOf[JMenuItem])
	
	private def resetNumberItems: Unit = {
	    val items = getNumberItems
	    items.foreach(i => {
	        val name = textToName(i.getText)
	        val n = getNumberOfName(name)
	        i.setText(makeNumberText(name, n))
	    })
	}
		
	private def resetMenuItem: Unit = {
		ResetMenu.cursorChange.setState(Scroll.cursorChange)
		ResetMenu.horizontalScroll.setState(Horizontal.scroll)
		ResetMenu.reverseScroll.setState(Scroll.reverse)
		resetTriggerItem
		resetPriorityItem
		resetNumberItems
	}
	
	private def getMenuItems(m: JMenu): Seq[JMenuItem]  =
	    (0 until m.getItemCount).map(m.getItem).filter(_ != null)
		//m.getSubElements
	
	private def getRadioButtonMenuItems(m: JMenu): Seq[JRadioButtonMenuItem] =
	    getMenuItems(m).map(_.asInstanceOf[JRadioButtonMenuItem])
	    
	private def getItemText(e: ItemEvent) = {
		e.getItem.asInstanceOf[JMenuItem].getText	
	}
	
	/*
	private def disableOtherItem(e: ItemEvent): Unit = {
		val text = getItemText(e)
		val items = getCheckBoxMenuItems(ResetMenu.trigger)
	    items.filter(_.getText != text).foreach(_.setState(false))
	}
	*/
	
	private def textToName(s: String): String =
		s.split(" ")(0)
	
	private def setTrigger(e: ItemEvent): Unit = {
		if (isSelected(e)) {
			//disableOtherItem(e)
			val text = getItemText(e) 
			val name = textToName(text)
			setTrigger(name)
		}
	}
	
	private def createTriggerItem(text: String): JRadioButtonMenuItem = {
	    val item = new JRadioButtonMenuItem(text)
	    item.addItemListener(setTrigger _)
	    item
	}
	
	private def createTriggerMenu: JMenu = {
		val menu = new JMenu("Trigger")
		
		menu.add(createTriggerItem("LRTrigger (Left <<-->> Right)"))
		menu.add(createTriggerItem("Left (Left -->> Right)"))
		menu.add(createTriggerItem("Right (Right -->> Left)"))
		menu.add(createTriggerItem("Middle"))
		menu.add(createTriggerItem("X1"))
		menu.add(createTriggerItem("X2"))
		menu.addSeparator()
		menu.add(createTriggerItem("LeftDrag"))
		menu.add(createTriggerItem("RightDrag"))
		menu.add(createTriggerItem("MiddleDrag"))
		menu.add(createTriggerItem("X1Drag"))
		menu.add(createTriggerItem("X2Drag"))
		
		val group = new ButtonGroup
		getMenuItems(menu).foreach(group.add)
		
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
	
	private def makeNumberText(name: String, n: Int) =
	    s"$name = $n"
	
	private def setNumberOfSpinner(e: ActionEvent, mi: JMenuItem, lowLimit: Int, upLimit: Int): Unit = {
	    val name = textToName(mi.getText)
		val sModel = new SpinnerNumberModel(getNumberOfName(name), lowLimit, upLimit, 1);
		val spinner = new JSpinner(sModel);
		setDigitOnly(spinner)
		
		val title = s"$name ($lowLimit - $upLimit)"
		val option = JOptionPane.showOptionDialog(null, spinner, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, null, null);

		if (option == JOptionPane.OK_OPTION) {
			val n = sModel.getNumber.intValue
			
	    	setNumberOfName(name, n)
	    	mi.setText(makeNumberText(name, n))
		}
	}
	
	private def createNumberMenuItem(name: String, lowLimit: Int, upLimit: Int): JMenuItem = {
	    val n = getNumberOfName(name)
		val mi = new JMenuItem(makeNumberText(name, n))
		val action = setNumberOfSpinner(_: ActionEvent, mi, lowLimit, upLimit)
		mi.addActionListener(action)
		mi
	}
	
	private def getPriority(name: String) = {
		name match {
			case "High" => Windows.High()
			case "AboveNormal" | "Above Normal" => Windows.AboveNormal()
			case "Normal" => Windows.Normal()
		}
	}
	
	private def setPriority(name: String) = {
		val priority = getPriority(name)
		logger.debug(s"setPriority: ${priority.name}")
		processPriority = priority
		Windows.setPriority(priority)
	}
	
	private def setPriority(e: ItemEvent): Unit = {
		if (isSelected(e))
			setPriority(getItemText(e)) 
	}
	
	private def createPriorityItem(name: String): JRadioButtonMenuItem = {
	    val item = new JRadioButtonMenuItem(name)
	    item.addItemListener(setPriority _)
	    item
	}
	
	private def createPriorityMenu: JMenu = {
		val menu = new JMenu("Priority")
		menu.add(createPriorityItem("High"))
		menu.add(createPriorityItem("Above Normal"))
		menu.add(createPriorityItem("Normal"))

		val group = new ButtonGroup
		getMenuItems(menu).foreach(group.add)
		
		ResetMenu.priority = menu
		
		menu
	}
	
	private def createSetNumberMenu: JMenu = {
		val menu = new JMenu("Set Number")
		
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
		val item = new JMenuItem("Reload Properties")
		item.addActionListener(reloadProperties _)
		item
	}
	
	private def createCursorChangeMenuItem = {
		val item = new JCheckBoxMenuItem("Cursor Change", Scroll.cursorChange)
		ResetMenu.cursorChange = item
		item.addItemListener(setBooleanOfEvent("cursorChange", _: ItemEvent))
		item
	}
	
	private def createHorizontalScrollMenuItem = {
		val item = new JCheckBoxMenuItem("Horizontal Scroll", Horizontal.scroll)
		ResetMenu.horizontalScroll = item
		item.addItemListener(setBooleanOfEvent("horizontalScroll", _: ItemEvent))
		item
	}
	
	private def createReverseScrollMenuItem = {
		val item = new JCheckBoxMenuItem("Reverse Scroll", Scroll.reverse)
		ResetMenu.reverseScroll = item
		item.addItemListener(setBooleanOfEvent("reverseScroll", _: ItemEvent))
		item
	}
	
	private def createPassModeMenuItem = {
		val item = new JCheckBoxMenuItem("Pass Mode")
		item.addItemListener(setBooleanOfEvent("passMode", _: ItemEvent))
		item
	}
	
	private def createVersionMenuItem = {
		val item = new JMenuItem("Version")
		item.addActionListener(showVersion _)
		item
	}
	
	private def createExitMenuItem = {
		val item = new JMenuItem("Exit")
		item.addActionListener(setUnhook _)
		item
	}
	
	private def createPopupMenu: JPopupMenu = {
		val menu = new JPopupMenu()
		
		menu.add(createTriggerMenu)
		menu.add(createPriorityMenu)
		menu.add(createSetNumberMenu)
		menu.add(createReloadPropertiesMenuItem)
		menu.addSeparator()
		
		menu.add(createCursorChangeMenuItem)
		menu.add(createHorizontalScrollMenuItem)
		menu.add(createReverseScrollMenuItem)
		menu.add(createPassModeMenuItem)
		menu.add(createVersionMenuItem)
		menu.add(createExitMenuItem)
		
		menu
	}	
	
	private def createHiddenDialog: JDialog = {
		val dialog = new JDialog
		dialog.setUndecorated(true)
		/*
		dialog.addWindowFocusListener(new WindowFocusListener {
			override def windowLostFocus(we: WindowEvent) = dialog.setVisible(false)
			override def windowGainedFocus(we: WindowEvent) {}
		})
		*/
		dialog
	}
	
	// http://ateraimemo.com/Swing/TrayIconPopupMenu.html
	// http://stackoverflow.com/questions/12667526/adding-jpopupmenu-to-the-trayicon
	// http://stackoverflow.com/questions/14670516/how-do-i-get-a-popupmenu-to-show-up-when-i-left-click-on-a-trayicon-in-java
	// http://stackoverflow.com/questions/19868209/cannot-hide-systemtray-jpopupmenu-when-it-loses-focus
	
	def setSystemTray {
		val stream = getClass.getClassLoader.getResourceAsStream(ICON_NAME)
		val popupMenu = createPopupMenu
		val hiddenDialog = createHiddenDialog
		val trayIcon = new TrayIcon(ImageIO.read(stream), PROGRAM_NAME)
		
		popupMenu.addPopupMenuListener(new PopupMenuListener {
			override def popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
			override def popupMenuWillBecomeInvisible(e: PopupMenuEvent) = hiddenDialog.setVisible(false)
			override def popupMenuCanceled(e: PopupMenuEvent) = hiddenDialog.setVisible(false)
		})
	
		trayIcon.addMouseListener(new MouseAdapter {
			override def mouseReleased (jme: java.awt.event.MouseEvent) {
				if (jme.isPopupTrigger) {
					hiddenDialog.setLocation(jme.getPoint)
					hiddenDialog.setVisible(true)
					popupMenu.show(hiddenDialog, 0, 0)
				}
			}
		})

		//icon.setPopupMenu(menu)
		trayIcon.addActionListener(setUnhook _)
		
		resetMenuItem
		
		SystemTray.getSystemTray.add(trayIcon)
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
		try {
			val n = getNumberOfProperty(name)
			if (n < lowlimit || n > uplimit)
				throw new IllegalArgumentException(n.toString())
		
			setNumberOfName(name, n)
		}
		catch {
			case _: scala.MatchError  => logger.warn(s"setNumberOfProperty: $name")
			case _: IllegalArgumentException => logger.warn(s"setNumberOfProperty $name")
		}
	}
	
	private def setBooleanOfProperty(name: String) {
		try {
			val b = prop.getProperty(name).toBoolean
			setBooleanOfName(name, b)
		}
		catch {
			case _: IllegalArgumentException => logger.warn(s"setBooleanOfProperty: $name")
			case _: scala.MatchError => logger.warn(s"setBooleanOfProperty: $name")
		}
	}
	
	private def setBooleanOfName(name: String, b: Boolean) = {
		logger.debug(s"setBoolean: $name = ${b.toString}") 
		name match {
			case "cursorChange" => Scroll.cursorChange = b
			case "horizontalScroll" => Horizontal.scroll = b
			case "reverseScroll" => Scroll.reverse = b
			case "passMode" => passMode = b
		}
	}
	
	private def setTrigger(s: String): Unit = {
		val res = Mouse.getTrigger(s)
		logger.debug("setTrigger: " + res.name);
		firstTrigger = res
		//EventHandler.changeTrigger
	}
	
	private def setTriggerOfProperty: Unit = {
		try {
			setTrigger(prop.getProperty("firstTrigger"))
		}
		catch {
			case e: scala.MatchError => logger.warn(s"setTriggerOfProperty: ${e.getMessage}")
		}
	}
		
	private def setPriorityOfProperty: Unit = {
		try {
			setPriority(prop.getProperty("processPriority"))
		}
		catch {
			case e: scala.MatchError => {
				logger.warn(s"setPriorityOfProperty: ${e.getMessage}")
				Windows.setPriority(processPriority) // default
			}
		}
	}
	
	private val prop = new Properties
	
	def loadProperties = {
		var input: FileInputStream = null
		try {
			input = getPropertiesInput
			prop.load(input)
			
			setTriggerOfProperty
			setPriorityOfProperty
			
			setBooleanOfProperty("cursorChange")
			setBooleanOfProperty("horizontalScroll")
			setBooleanOfProperty("reverseScroll")
			
			setNumberOfProperty("pollTimeout", 50, 500)
			setNumberOfProperty("scrollLocktime", 150, 500)
			setNumberOfProperty("verticalAccel", 0, 500)
			setNumberOfProperty("verticalThreshold" , 0, 500)
			setNumberOfProperty("horizontalAccel", 0, 500)
			setNumberOfProperty("horizontalThreshold", 0, 500)
		}
		catch {
			case e: Exception => logger.warn(e.toString)
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
			val r1 = trigger != firstTrigger.name
			
			val priority = prop.getProperty("processPriority")
			val r2 = priority != processPriority.name
			
			val cc = prop.getProperty("cursorChange")
			val r3 = cc.toBoolean != Scroll.cursorChange
			
			val hs = prop.getProperty("horizontalScroll")
			val r4 = hs.toBoolean != Horizontal.scroll
			
			val rs = prop.getProperty("reverseScroll")
			val r5 = rs.toBoolean != Scroll.reverse
			
			Array(r1, r2, r3, r4, r5).contains(true) ||
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
		if (!isChangedProperties) {
			logger.debug("Not Changed Properties")
			return
		}
		
		try {			
			prop.setProperty("firstTrigger", firstTrigger.name)
			prop.setProperty("processPriority", processPriority.name)
			prop.setProperty("cursorChange", Scroll.cursorChange.toString())
			prop.setProperty("horizontalScroll", Horizontal.scroll.toString())
			prop.setProperty("reverseScroll", Scroll.reverse.toString())
			
			val names = getNumberNames					
			names.foreach(n => prop.setProperty(n, getNumberOfName(n).toString))
			prop.store(getPropertiesOutput, PROGRAM_NAME)
		}
		catch {
			case e: Exception => logger.warn(e.toString())
		}
	}
}