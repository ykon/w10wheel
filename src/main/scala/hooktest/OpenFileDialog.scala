package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.awt.{ Frame, Toolkit }
import java.nio.file.{ Files, Path, Paths }
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileSystemView

import com.sun.jna.{ Memory, Native }
import com.sun.jna.platform.win32.{ Ole32, W32Errors, WinDef }

import win32ex._
import win32ex.Comdlg32Ex._

// The file dialog of Windows (GetOpenFileNameW) to choose files of a type, as OpenFileDialog of .NET
// (W10Wheel.NET) shows it: with its filter (e.g. "Programs (*.exe)"), which java.awt.FileDialog
// cannot set (it has only "All Files", and setFile("*.exe") is lost once the folder changes).
object OpenFileDialog {
    private val logger = Logger.getLogger()

    // The chars of the files chosen (their folder and their names): very many files
    private val MAX_FILE = 1 << 16

    private val COINIT_APARTMENTTHREADED = 0x2
    private val COINIT_DISABLE_OLE1DDE = 0x4

    // UTF-16 with a NUL at the end (s can have NULs: a filter)
    private def wide(s: String): Memory = {
        val m = new Memory((s.length + 1) * 2L)
        s.indices.foreach(i => m.setChar(i * 2L, s(i)))
        m.setChar(s.length * 2L, 0)
        m
    }

    // The strings up to the empty one (two NULs)
    private def strings(m: Memory): List[String] = {
        val b = List.newBuilder[String]
        var offset = 0L
        var s = m.getWideString(offset)
        while (s.nonEmpty && offset < m.size) {
            b += s
            offset += (s.length + 1) * 2L
            s = if (offset < m.size) m.getWideString(offset) else ""
        }
        b.result()
    }

    // On a thread of its own (COM initialized for the dialog): the files chosen, none if canceled
    private def ask(owner: WinDef.HWND, title: String, filterName: String, pattern: String): Seq[Path] = {
        val hr = Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE)
        try {
            val file = new Memory(MAX_FILE * 2L)
            file.clear()
            val z = 0.toChar
            val ofn = new OPENFILENAME()
            ofn.lStructSize = ofn.size()
            ofn.hwndOwner = owner
            ofn.lpstrFilter = wide(s"$filterName ($pattern)$z$pattern$z")
            ofn.nFilterIndex = 1
            ofn.lpstrFile = file
            ofn.nMaxFile = MAX_FILE
            val t = wide(title)
            ofn.lpstrTitle = t
            // The shortcuts chosen as they are (dereferenced after): the dialog dereferencing them
            // refuses some chosen at once, those to programs in other folders ("all the items must
            // be in the same folder")
            ofn.Flags = OFN_EXPLORER | OFN_ALLOWMULTISELECT | OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST |
                        OFN_HIDEREADONLY | OFN_NOCHANGEDIR | OFN_NODEREFERENCELINKS

            if (Comdlg32Ex.INSTANCE.GetOpenFileNameW(ofn)) {
                // One file: its path. More: their folder, then their names.
                strings(file) match {
                    case Nil => Seq.empty
                    case path :: Nil => Seq(Paths.get(path))
                    case dir :: names => names.map(Paths.get(dir).resolve)
                }
            }
            else {
                val e = Comdlg32Ex.INSTANCE.CommDlgExtendedError()
                if (e != 0)
                    logger.warn(f"GetOpenFileNameW: error 0x$e%04X" +
                                (if (e == FNERR_BUFFERTOOSMALL) " (too many files chosen)" else ""))
                Seq.empty
            }
        }
        finally {
            if (W32Errors.SUCCEEDED(hr))
                Ole32.INSTANCE.CoUninitialize()
        }
    }

    // A shortcut is taken for its target (as DereferenceLinks of .NET), here and not by the dialog
    // (OFN_NODEREFERENCELINKS). A shortcut to nothing (e.g. a Store app) is dropped.
    private def dereference(p: Path): Option[Path] = {
        val fsv = FileSystemView.getFileSystemView
        val f = p.toFile
        try {
            if (fsv.isLink(f)) Option(fsv.getLinkLocation(f)).map(_.toPath) else Some(p)
        }
        catch {
            case e: Exception =>
                logger.warn(s"dereference: $p: $e")
                None
        }
    }

    // On the Swing thread, modal to owner: the full paths of the files chosen (shortcuts taken for
    // their targets), none if canceled. The dialog is shown on a thread of its own while the Swing
    // thread goes on (a secondary loop, as java.awt.FileDialog does): the owner is painted.
    def show(owner: Frame, title: String, filterName: String, pattern: String): Seq[String] = {
        val hwnd = new WinDef.HWND(Native.getComponentPointer(owner))
        @volatile var result: Either[Throwable, Seq[Path]] = Right(Seq.empty)
        val loop = Toolkit.getDefaultToolkit.getSystemEventQueue.createSecondaryLoop()
        val thread = new Thread(() => {
            try result = Right(ask(hwnd, title, filterName, pattern))
            catch { case e: Throwable => result = Left(e) }
            finally loop.exit()
        }, "OpenFileDialog")
        thread.setDaemon(true)
        // Started in the loop: it is not exited before it is entered
        SwingUtilities.invokeLater(() => thread.start())
        loop.enter()
        thread.join()

        result match {
            case Left(e) =>
                logger.warn(s"OpenFileDialog: $e")
                Seq.empty
            case Right(paths) =>
                paths.flatMap(dereference)
                    .filter(p => Files.isRegularFile(p))
                    .map(_.toAbsolutePath.normalize.toString)
                    .distinct
        }
    }
}
