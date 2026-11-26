package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

// What Context knows of the properties files (loaded, the errors of a load and of a store), as
// W10Wheel.NET tests it (W10Wheel.Tests, StoreTests.fs), ported from the Rust version (w10wheel.rs,
// properties.rs). In a folder of the tests (Harness.USER_DIR), not the user's. The code as it is.

import java.io.File
import java.nio.file.{ AccessDeniedException, Files, Path }

import com.sun.jna.platform.win32.{ Kernel32, WinBase, WinNT }

import Harness._

class StoreSuite extends munit.FunSuite {
    private def propertiesPath: Path = Properties.getPath(Properties.DEFAULT_DEF)
    private def exclusionsPath: Path = Properties.getExclusionsPath(Properties.DEFAULT_DEF)

    // The folder emptied, the Default properties loaded from it (none: the default values)
    private def loadEmpty(): Unit = {
        reset(DataID.RightDrag, DataID.None)
        Option(new File(Properties.USER_DIR).listFiles()).getOrElse(Array.empty[File]).foreach { f =>
            f.setWritable(true)
            f.delete()
        }
        Context.setSelectedProperties(Properties.DEFAULT_DEF)
        Context.loadProperties
    }

    // The file opened by another program that shares nothing (e.g. an editor writing it): it can
    // be neither read nor written (nor replaced). Java opens its files sharing them all.
    private def locked[T](path: Path)(f: => T): T = {
        val k32 = Kernel32.INSTANCE
        val h = k32.CreateFile(path.toString, WinNT.GENERIC_READ, 0, null, WinNT.OPEN_EXISTING,
                               WinNT.FILE_ATTRIBUTE_NORMAL, null)
        assert(h != WinBase.INVALID_HANDLE_VALUE, s"not opened: $path")
        try f finally k32.CloseHandle(h)
    }

    private def readOnly(path: Path, b: Boolean): Unit =
        Files.setAttribute(path, "dos:readonly", b)

    private def status: Option[String] = Context.ForTests.storeStatus

    private val NOT_SAVED = Some("The changes could not be saved to the properties file")
    private val NOT_READ = Some("The properties file could not be read")
    private val EXCLUSIONS_NOT_SAVED = Some("The changes could not be saved to the exclusions file")
    private val EXCLUSIONS_NOT_READ = Some("The exclusions file could not be read: changes are not saved")

    private val OTHER_PROGRAM = """C:\Other\other.exe"""

    test("a Reload that fails keeps the properties loaded, and the stores after it") {
        loadEmpty()
        assertEquals(status, None)
        Context.ForTests.setNumber(DataID.pollTimeout, 300)
        Context.storeProperties
        assert(Files.exists(propertiesPath))
        assertEquals(status, None)

        // The file cannot be read: the settings stay, still stored, with why
        locked(propertiesPath) {
            assert(!Context.ForTests.reloadProperties())
            assertEquals(status, NOT_READ)

            // A change while it cannot be written: not stored, told before the error of the load
            Context.ForTests.setNumber(DataID.pollTimeout, 350)
            Context.storeProperties
            assertEquals(status, NOT_SAVED)
        }

        // Stored when it can be written again; the error of the load is kept until a load succeeds
        Context.storeProperties
        assertEquals(status, NOT_READ)
        assert(Files.readString(propertiesPath).contains("pollTimeout=350"))
        assert(Context.ForTests.reloadProperties())
        assertEquals(status, None)
    }

    test("the properties not read at startup are not stored") {
        loadEmpty()
        Context.ForTests.setNumber(DataID.pollTimeout, 300)
        Context.storeProperties
        val before = Files.readString(propertiesPath)
        locked(propertiesPath) {
            // As at startup: nothing loaded yet
            Context.ForTests.forgetLoaded()
            Context.loadProperties
            assertEquals(status, Some("The properties file could not be read: changes are not saved"))
        }
        Context.ForTests.setNumber(DataID.pollTimeout, 400)
        Context.storeProperties
        assertEquals(Files.readString(propertiesPath), before)
    }

    test("the exclusions' store error is kept through a Reload that cannot read them") {
        loadEmpty()
        Context.ForTests.setExcludedPrograms(Seq(EXCLUDED_PROGRAM))
        Context.storeProperties
        assert(Files.exists(exclusionsPath))

        // Read-only: their change is not stored
        readOnly(exclusionsPath, true)
        try {
            Context.ForTests.setExcludedPrograms(Seq(EXCLUDED_PROGRAM, OTHER_PROGRAM))
            Context.storeProperties
            assertEquals(status, EXCLUSIONS_NOT_SAVED)

            // A Reload that cannot read them keeps the list running and the error of its store
            locked(exclusionsPath) {
                assert(Context.ForTests.reloadProperties())
                assertEquals(status, EXCLUSIONS_NOT_READ)
                assertEquals(Context.getExcludedPrograms, Vector(EXCLUDED_PROGRAM, OTHER_PROGRAM))
                Context.ForTests.unstoredReason(false) match {
                    case Some(_: AccessDeniedException) => ()
                    case other => fail(s"not the error of the store: $other")
                }
            }
        }
        finally {
            readOnly(exclusionsPath, false)
        }

        // Read again: the list of the file, nothing left not stored
        assert(Context.ForTests.reloadProperties())
        assertEquals(status, None)
        assertEquals(Context.getExcludedPrograms, Vector(EXCLUDED_PROGRAM))
    }

    test("the reason of the changes not stored, of both files") {
        loadEmpty()
        Context.ForTests.setNumber(DataID.pollTimeout, 300)
        Context.ForTests.setExcludedPrograms(Seq(EXCLUDED_PROGRAM))
        Context.storeProperties

        readOnly(propertiesPath, true)
        readOnly(exclusionsPath, true)
        try {
            Context.ForTests.setNumber(DataID.pollTimeout, 350)
            Context.ForTests.setExcludedPrograms(Seq(EXCLUDED_PROGRAM, OTHER_PROGRAM))
            Context.storeProperties
            def reason(settings: Boolean): String =
                Context.ForTests.unstoredReason(settings).map(e => String.valueOf(e.getMessage)).getOrElse("")
            // Of the settings if some are left, else of the excluded programs
            assert(reason(true).contains(propertiesPath.getFileName.toString), reason(true))
            assert(reason(false).contains(exclusionsPath.getFileName.toString), reason(false))
        }
        finally {
            readOnly(propertiesPath, false)
            readOnly(exclusionsPath, false)
        }
    }

    test("the defaults are those of a start, not the settings of a test") {
        // Reset to defaults sets them back (resetSettings): kept before the tests changed any
        // setting
        loadEmpty()
        assertEquals(Context.ForTests.defaultSetting(DataID.firstTrigger), Some("RightDragTrigger"))
        assertEquals(Context.ForTests.defaultSetting(DataID.targetVKCode), Some(DataID.VK_NONCONVERT))
        assertEquals(Context.ForTests.defaultSetting(DataID.pollTimeout), Some("200"))
    }
}
