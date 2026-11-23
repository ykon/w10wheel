package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.awt.{ Font, Image, MouseInfo, SystemTray, TrayIcon }
import java.awt.event.{ MouseAdapter, MouseEvent => AwtMouseEvent }
import java.awt.image.BaseMultiResolutionImage
import javax.imageio.ImageIO
import javax.swing._
import javax.swing.event.{ PopupMenuEvent, PopupMenuListener }

// The icon in the tray (AWT) and its menu (Swing, in the look and feel of the settings dialog):
// Settings..., Pause (also switched by a double click on the icon, as in W10Wheel.NET), About and
// Exit. On the Swing thread; update and retext can be called from any thread.
object Tray {
    private val logger = Logger.getLogger()
    private lazy val convLang: String => String = Context.convLang

    private def readImage(name: String): Image = {
        val stream = getClass.getClassLoader.getResourceAsStream(s"icons/$name")
        try {
            ImageIO.read(stream)
        }
        finally {
            stream.close()
        }
    }

    // The sizes of the icon files: the tray takes the one for the scale of the display (16 at 100%,
    // 24 at 150%...), not 16 scaled up
    private val TRAY_SIZES = Seq(16, 20, 24, 28, 32, 36, 40, 48)
    private val APP_SIZES = Seq(16, 20, 24, 32, 40, 48, 64, 128, 256)

    private def multiResolution(prefix: String, sizes: Seq[Int]): Image = {
        val images = sizes.map(s => readImage(s"$prefix-$s.png"))
        new BaseMultiResolutionImage(images*)
    }

    // Made once: a new image for every switch of Pause would not be released
    private lazy val iconRun = multiResolution("TrayIcon-Run", TRAY_SIZES)
    private lazy val iconStop = multiResolution("TrayIcon-Stop", TRAY_SIZES)

    // The icon of the windows (the taskbar and Alt+Tab take the size they need)
    lazy val appIcons: java.util.List[Image] = {
        val list = new java.util.ArrayList[Image]()
        APP_SIZES.foreach(s => list.add(readImage(s"AppIcon-$s.png")))
        list
    }

    def appIcon(size: Int): Image =
        readImage(s"AppIcon-${APP_SIZES.find(_ >= size).getOrElse(APP_SIZES.last)}.png")

    private var trayIcon: TrayIcon = null
    private var menu: JPopupMenu = null
    // The menu is shown from a window of its own (the tray icon is not a component): hidden with it
    private var invoker: JDialog = null
    private var pauseItem: JCheckBoxMenuItem = null

    // The English texts of the items (retext)
    private val items = scala.collection.mutable.ArrayBuffer.empty[(JMenuItem, String)]

    // With the name of the selected properties if not Default (e.g. "W10Wheel - 実行中 (Left)"), at
    // most 127 characters (as W10Wheel.NET: the tip of the tray is cut above)
    private def getTrayText(pause: Boolean): String = {
        val text = s"${Context.PROGRAM_NAME} - ${convLang(if (pause) "Stopped" else "Runnable")}"
        val name = Context.getSelectedProperties
        val full = if (name == Properties.DEFAULT_DEF) text else s"$text ($name)"
        if (full.length > 127) full.substring(0, 124) + "..." else full
    }

    private def menuItem(engText: String, action: () => Unit): JMenuItem = {
        val item = new JMenuItem(convLang(engText))
        item.addActionListener(_ => action())
        items += ((item, engText))
        item
    }

    private def createMenu(): JPopupMenu = {
        val m = new JPopupMenu()

        // In bold: the main item of the menu
        val settings = menuItem("Settings...", () => Context.showSettingsDialog())
        settings.setFont(settings.getFont.deriveFont(Font.BOLD))
        m.add(settings)

        // Pass Mode: not stored (as in W10Wheel.NET, "Pause")
        pauseItem = new JCheckBoxMenuItem(convLang("Pause"))
        pauseItem.addActionListener(_ => {
            val b = pauseItem.isSelected
            HookThread.post(() => Context.setPassMode(b))
        })
        items += ((pauseItem, "Pause"))
        m.add(pauseItem)
        m.addSeparator()

        m.add(menuItem("About", () => Dialog.showAbout()))
        m.add(menuItem("Exit", () => Context.exitAction()))

        m.addPopupMenuListener(new PopupMenuListener {
            def popupMenuWillBecomeVisible(e: PopupMenuEvent): Unit = ()
            def popupMenuWillBecomeInvisible(e: PopupMenuEvent): Unit = invoker.setVisible(false)
            def popupMenuCanceled(e: PopupMenuEvent): Unit = ()
        })
        m
    }

    private def createInvoker(): JDialog = {
        val d = new JDialog()
        d.setUndecorated(true)
        d.setType(java.awt.Window.Type.UTILITY)
        d.setAlwaysOnTop(true)
        d.setSize(1, 1)
        d
    }

    // At the cursor (where the icon was clicked), kept in the screen by the menu (above the taskbar)
    private def showMenu(): Unit = {
        val pt = Option(MouseInfo.getPointerInfo).map(_.getLocation).getOrElse(new java.awt.Point(0, 0))
        pauseItem.setSelected(Context.isPassMode)
        invoker.setLocation(pt.x, pt.y)
        invoker.setVisible(true)
        // Activated: the menu is closed by a click outside it (the dialog deactivated)
        invoker.toFront()
        menu.show(invoker, 0, 0)
    }

    // At startup, on the Swing thread
    def setup(): Unit = {
        if (!SystemTray.isSupported) {
            logger.warn("SystemTray is not supported")
            return
        }

        menu = createMenu()
        invoker = createInvoker()

        // Closed when another window is activated (e.g. a click on the taskbar)
        invoker.addWindowFocusListener(new java.awt.event.WindowFocusListener {
            def windowGainedFocus(e: java.awt.event.WindowEvent): Unit = ()
            def windowLostFocus(e: java.awt.event.WindowEvent): Unit = menu.setVisible(false)
        })

        val pause = Context.isPassMode
        trayIcon = new TrayIcon(if (pause) iconStop else iconRun, getTrayText(pause))
        trayIcon.setImageAutoSize(true)
        trayIcon.addMouseListener(new MouseAdapter {
            // The menu where Windows shows the menus of the tray icons: at the release of the right
            // button (the popup trigger on Windows)
            override def mouseReleased(e: AwtMouseEvent): Unit = {
                if (e.isPopupTrigger) showMenu()
            }

            // A double click of the left button switches Pause (ActionEvent is also raised by the
            // keyboard: Enter on the icon, without a button)
            override def mouseClicked(e: AwtMouseEvent): Unit = {
                if (e.getButton == AwtMouseEvent.BUTTON1 && e.getClickCount == 2)
                    HookThread.post(() => Context.setPassMode(!Context.isPassMode))
            }
        })

        try {
            SystemTray.getSystemTray.add(trayIcon)
        }
        catch {
            case e: Exception => logger.warn(s"TrayIcon: $e")
        }

        // The menu is not in a window while it is hidden: FlatLaf.updateUI does not update it
        Theme.addListener(() => {
            SwingUtilities.updateComponentTreeUI(menu)
            items.find(_._2 == "Settings...").foreach { case (item, _) =>
                item.setFont(UIManager.getFont("MenuItem.font").deriveFont(Font.BOLD))
            }
        })
    }

    // At exit (on the Swing thread): the icon would remain in the tray
    def remove(): Unit = {
        if (trayIcon != null) {
            SystemTray.getSystemTray.remove(trayIcon)
            trayIcon = null
        }
        if (menu != null) menu.setVisible(false)
        if (invoker != null) invoker.dispose()
    }

    private def later(f: () => Unit): Unit =
        SwingUtilities.invokeLater(() => if (trayIcon != null) f())

    // Pause has been switched, or the properties (their name in the tip): from any thread
    def update(): Unit = later(() => {
        val pause = Context.isPassMode
        trayIcon.setImage(if (pause) iconStop else iconRun)
        trayIcon.setToolTip(getTrayText(pause))
        pauseItem.setSelected(pause)
    })

    // The language has changed: from any thread
    def retext(): Unit = later(() => {
        items.foreach { case (item, engText) => item.setText(convLang(engText)) }
        trayIcon.setToolTip(getTrayText(Context.isPassMode))
    })
}
