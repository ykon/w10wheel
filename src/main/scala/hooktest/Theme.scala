package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import javax.swing.{ SwingUtilities, ToolTipManager, UIManager }

import com.formdev.flatlaf.{ FlatDarkLaf, FlatLaf, FlatLightLaf }
import com.sun.jna.platform.win32.{ Advapi32Util, WinReg }

// The look and feel of the UI (FlatLaf): light or dark as the apps of Windows are (Settings >
// Personalization > Colors > "Choose your default app mode"), followed when it is changed
// (Windows: WM_SETTINGCHANGE "ImmersiveColorSet").
object Theme {
    private val logger = Logger.getLogger()

    private val PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

    // Light if it cannot be read (e.g. Windows 10 before 1809)
    private def isWindowsDark: Boolean = {
        try {
            Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "AppsUseLightTheme") &&
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "AppsUseLightTheme") == 0
        }
        catch {
            case e: Exception =>
                logger.debug(s"AppsUseLightTheme: $e")
                false
        }
    }

    @volatile private var initialized = false
    private var dark = false

    // Called after the look and feel has changed (on the Swing thread): for the components that are
    // not in a window (e.g. the menu of the tray icon while it is hidden)
    private var listeners = List.empty[() => Unit]

    def isDark: Boolean = dark

    def addListener(f: () => Unit): Unit = {
        listeners = listeners :+ f
    }

    private def install(d: Boolean): Unit = {
        dark = d
        if (d) FlatDarkLaf.setup() else FlatLightLaf.setup()
    }

    // Once, before any Swing component is made (the first dialog or the tray icon). Any thread.
    def setup(): Unit = synchronized {
        if (!initialized) {
            // The settings shown on the UI are read from other threads (volatile): the components
            // are made on the Swing thread only
            val d = isWindowsDark
            val run: Runnable = () => {
                try {
                    install(d)
                }
                catch {
                    case e: Exception => logger.warn(s"Theme: $e")
                }
                // The explanations of the settings are long: shown longer than the default (4 s)
                ToolTipManager.sharedInstance.setDismissDelay(20000)
            }
            if (SwingUtilities.isEventDispatchThread) run.run() else SwingUtilities.invokeAndWait(run)
            initialized = true
        }
    }

    // The mode of the apps may have changed (from the thread of the message window): the look and
    // feel changed if it has, and the windows updated
    def windowsThemeChanged(): Unit = {
        if (initialized) {
            val d = isWindowsDark
            SwingUtilities.invokeLater(() => {
                if (d != dark) {
                    logger.debug(s"Theme changed: dark = $d")
                    try {
                        install(d)
                        FlatLaf.updateUI()
                        listeners.foreach(f => f())
                    }
                    catch {
                        case e: Exception => logger.warn(s"Theme: $e")
                    }
                }
            })
        }
    }

    // A color of the look and feel, or the fallback if it has none
    def color(key: String, fallback: java.awt.Color): java.awt.Color =
        Option(UIManager.getColor(key)).getOrElse(fallback)
}
