package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.awt.image.BufferedImage

import com.sun.jna.{ Memory, Native }
import com.sun.jna.platform.win32.{ Advapi32Util, GDI32, Shell32, ShellAPI, User32, WinGDI, WinUser }
import com.sun.jna.platform.win32.WinDef.{ HICON, HINSTANCE }
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.{ StdCallLibrary, W32APIOptions }

// Running W10Wheel as administrator (as W10Wheel.NET does): without it, the hooks get no input in
// the windows of the programs that run as administrator (Windows does not pass it to a program with
// less rights), so W10Wheel does not work there.
object Admin {
    private val logger = Logger.getLogger()

    // The argument of the instance started as administrator by startAsAdmin: it waits for this one
    // to exit (the instance lock, see W10Wheel.lockInstance)
    val RESTART_ADMIN_ARG = "--restartAdmin"
    // After RESTART_ADMIN_ARG: the settings dialog is opened again (restarted from it)
    val OPEN_SETTINGS_ARG = "--openSettings"

    enum AdminStart {
        case Started
        case Canceled // UAC answered No
        case Failed(e: Throwable)
    }

    // ERROR_CANCELLED: the user answered No to UAC
    private val ERROR_CANCELLED = 1223

    // Elevated: also true if UAC is off and the user is an administrator
    lazy val isAdmin: Boolean = {
        try {
            Advapi32Util.isCurrentProcessElevated()
        }
        catch {
            case e: Throwable =>
                logger.warn(s"isAdmin: $e")
                false
        }
    }

    // The launcher of W10Wheel (W10Wheel.exe): set by the launcher of jpackage. None if W10Wheel
    // runs without it (e.g. sbt run): it cannot be restarted then.
    def appPath: Option[String] =
        Option(System.getProperty("jpackage.app-path")).filter(p => new java.io.File(p).isFile)

    def canRestart: Boolean =
        appPath.isDefined

    // Starts W10Wheel as administrator (UAC) with the properties name (a name has no quotes: see
    // Context.isValidPropertiesName), and the settings dialog if openSettings. Waits for the answer
    // to UAC (not on the hook thread: the hooks would wait, and time out).
    def startAsAdmin(propertiesName: String, openSettings: Boolean): AdminStart = {
        appPath match {
            case None => AdminStart.Failed(new IllegalStateException("W10Wheel.exe not found"))
            case Some(path) =>
                try {
                    val args = (Seq(RESTART_ADMIN_ARG) ++ (if (openSettings) Seq(OPEN_SETTINGS_ARG) else Nil) ++
                                (if (propertiesName == Properties.DEFAULT_DEF) Nil else Seq("\"" + propertiesName + "\""))).mkString(" ")
                    val info = new ShellAPI.SHELLEXECUTEINFO()
                    info.cbSize = info.size()
                    info.lpVerb = "runas"
                    info.lpFile = path
                    info.lpParameters = args
                    info.lpDirectory = System.getProperty("user.dir")
                    info.nShow = WinUser.SW_SHOWNORMAL
                    if (Shell32.INSTANCE.ShellExecuteEx(info))
                        AdminStart.Started
                    else {
                        val err = Native.getLastError()
                        if (err == ERROR_CANCELLED)
                            AdminStart.Canceled
                        else
                            AdminStart.Failed(new IllegalStateException(com.sun.jna.platform.win32.Kernel32Util.formatMessage(err)))
                    }
                }
                catch {
                    case e: Exception => AdminStart.Failed(e)
                }
        }
    }

    private trait Comctl32 extends StdCallLibrary {
        def LoadIconWithScaleDown(hinst: HINSTANCE, name: com.sun.jna.Pointer, cx: Int, cy: Int, phico: PointerByReference): Int
    }

    private lazy val comctl32: Option[Comctl32] =
        try {
            Some(Native.load("comctl32", classOf[Comctl32], W32APIOptions.DEFAULT_OPTIONS))
        }
        catch {
            case e: Throwable =>
                logger.warn(s"comctl32: $e")
                None
        }

    // IDI_SHIELD
    private val IDI_SHIELD = 32518

    private val shieldCache = scala.collection.mutable.Map.empty[Int, Option[BufferedImage]]

    // The shield of UAC at size pixels (drawn by Windows at that size, not scaled), None if it
    // cannot be had. On the Swing thread.
    def shieldImage(size: Int): Option[BufferedImage] =
        shieldCache.getOrElseUpdate(size, loadShield(size))

    private def loadShield(size: Int): Option[BufferedImage] = {
        try {
            comctl32.flatMap(lib => {
                val ref = new PointerByReference()
                val hr = lib.LoadIconWithScaleDown(null, new com.sun.jna.Pointer(IDI_SHIELD.toLong), size, size, ref)
                if (hr != 0 || ref.getValue == null)
                    None
                else {
                    val hIcon = new HICON(ref.getValue)
                    try iconToImage(hIcon, size)
                    finally User32.INSTANCE.DestroyIcon(hIcon)
                }
            })
        }
        catch {
            case e: Throwable =>
                logger.warn(s"shieldImage: $e")
                None
        }
    }

    // The pixels of the color bitmap of the icon (32 bits with alpha)
    private def iconToImage(hIcon: HICON, size: Int): Option[BufferedImage] = {
        val ii = new WinGDI.ICONINFO()
        if (!User32.INSTANCE.GetIconInfo(hIcon, ii))
            None
        else {
            try {
                if (ii.hbmColor == null)
                    None
                else {
                    val bmi = new WinGDI.BITMAPINFO()
                    bmi.bmiHeader.biSize = bmi.bmiHeader.size()
                    bmi.bmiHeader.biWidth = size
                    bmi.bmiHeader.biHeight = -size // top-down
                    bmi.bmiHeader.biPlanes = 1
                    bmi.bmiHeader.biBitCount = 32
                    bmi.bmiHeader.biCompression = WinGDI.BI_RGB
                    val buf = new Memory(size.toLong * size * 4)
                    val hdc = User32.INSTANCE.GetDC(null)
                    try {
                        if (GDI32.INSTANCE.GetDIBits(hdc, ii.hbmColor, 0, size, buf, bmi, WinGDI.DIB_RGB_COLORS) == 0)
                            None
                        else {
                            val pixels = buf.getIntArray(0, size * size)
                            val img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                            img.setRGB(0, 0, size, size, pixels, 0, size)
                            Some(img)
                        }
                    }
                    finally {
                        User32.INSTANCE.ReleaseDC(null, hdc)
                        buf.close()
                    }
                }
            }
            finally {
                if (ii.hbmColor != null) GDI32.INSTANCE.DeleteObject(ii.hbmColor)
                if (ii.hbmMask != null) GDI32.INSTANCE.DeleteObject(ii.hbmMask)
            }
        }
    }
}
