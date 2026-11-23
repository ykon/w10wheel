package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import javax.swing.{ SwingUtilities, ToolTipManager, UIManager }

import com.formdev.flatlaf.{ FlatDarkLaf, FlatLaf, FlatLightLaf }
import com.sun.jna.platform.win32.{ Advapi32, Advapi32Util, WinError, WinNT, WinReg }

// The look and feel of the UI (FlatLaf): light or dark as the apps of Windows are (Settings >
// Personalization > Colors > "Choose your default app mode"), followed when it is changed: the
// value is watched in the registry (watch). Not WM_SETTINGCHANGE "ImmersiveColorSet": running as
// administrator, W10Wheel did not get it (sent by programs not running as administrator).
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

    // Watches the key of the mode of the apps on a thread of its own (it waits for each change):
    // started once, after setup. Not if the key cannot be opened (the mode is not followed then).
    def watch(): Unit = {
        val t = new Thread(() => {
            val ref = new WinReg.HKEYByReference()
            val opened = Advapi32.INSTANCE.RegOpenKeyEx(WinReg.HKEY_CURRENT_USER, PERSONALIZE, 0,
                                                         WinNT.KEY_NOTIFY | WinNT.KEY_READ, ref)
            if (opened != WinError.ERROR_SUCCESS)
                logger.warn(s"Theme.watch: RegOpenKeyEx: $opened")
            else {
                try {
                    // Waits for a value of the key set (synchronous), then reads the mode
                    while (Advapi32.INSTANCE.RegNotifyChangeKeyValue(ref.getValue, false,
                               WinNT.REG_NOTIFY_CHANGE_LAST_SET, null, false) == WinError.ERROR_SUCCESS)
                        windowsThemeChanged()
                    logger.warn("Theme.watch: RegNotifyChangeKeyValue failed")
                }
                finally {
                    Advapi32.INSTANCE.RegCloseKey(ref.getValue)
                }
            }
        }, "W10Wheel theme watcher")
        t.setDaemon(true)
        t.start()
    }

    // A value of the key of the mode of the apps has changed (from the watcher, watch): the look
    // and feel changed if the mode has, and the windows updated
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
