package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file._

import scala.jdk.CollectionConverters._

import com.sun.jna.{ Memory, Native }
import com.sun.jna.platform.win32.{ Kernel32, Kernel32Util, WinBase, WinNT }
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.ptr.IntByReference

object Properties {
    private val logger = Logger.getLogger()

    // The properties files, read and written as W10Wheel.NET does (they are shared with it): a line
    // "key=value" (the value can have spaces, e.g. "1, 2, 3" as in AccelTable.txt, and "="; the key
    // cannot be empty nor have "=" or spaces), a comment from "#" to the end of the line (also after
    // a value), other lines ignored. UTF-8 (a BOM is skipped). Not java.util.Properties, which read
    // "value # comment" as the value, ":" and spaces as separators, and "\" as an escape.
    final class SProperties {
        // Sorted (by ordinal): a new file is written in the order of the keys, as W10Wheel.NET does
        private val map = new java.util.TreeMap[String, String]()

        def clear(): Unit = synchronized {
            map.clear()
        }

        // The keys and the values of p instead of these
        def copyFrom(p: SProperties): Unit = {
            val entries = p.synchronized { new java.util.TreeMap[String, String](p.map) }
            synchronized {
                map.clear()
                map.putAll(entries)
            }
        }

        def keys: List[String] = synchronized {
            map.keySet.asScala.toList
        }

        def containsKey(key: String): Boolean = synchronized {
            map.containsKey(key)
        }

        def getOption(key: String): Option[String] = synchronized {
            Option(map.get(key))
        }

        // A missing file throws NoSuchFileException, and only it: a file locked by another process
        // or not readable throws another IOException. The keys already set are kept (clear first
        // to read only the file).
        def load(path: Path): Unit =
            loadLines(TextFile.read(path).lines)

        // The keys of lines, as load reads them from a file (e.g. the lines of a file just written)
        def loadLines(lines: Seq[String]): Unit = synchronized {
            lines.foreach(l => parseLine(l).foreach { case (k, v) => map.put(k, v) })
        }

        // A new file with all the keys (e.g. Default written the first time)
        def store(path: Path): Unit =
            TextFile.write(path, storedFile)

        // The file of these properties alone (all the keys, in order)
        private[Properties] def storedFile: TextFile = {
            val lines = synchronized { map.asScala.map { case (k, v) => s"$k=$v" }.toList }
            TextFile(lines, System.lineSeparator, false)
        }

        // Writes the values of keys to the file (path) as it is now (see updatedFile)
        def update(path: Path, keys: Seq[String]): Unit =
            TextFile.write(path, updatedFile(TextFile.read(path), keys))

        // A file (file) with the values of keys: the lines of these keys get their values (a comment
        // after a value is kept), the other lines stay as they are (the other keys, the comments, the
        // empty lines, their order, the line breaks), and the keys not in it are added at the end
        private[Properties] def updatedFile(file: TextFile, keys: Seq[String]): TextFile = {
            val toWrite = keys.toSet
            val written = scala.collection.mutable.Set.empty[String]
            val values = synchronized { keys.map(k => k -> getString(k)).toMap }

            val lines = file.lines.map(l =>
                parseLine(l) match {
                    case Some((k, _)) if toWrite.contains(k) =>
                        written += k
                        val comment = l.indexOf('#') match {
                            case -1 => ""
                            case n => " " + l.substring(n)
                        }
                        s"$k=${values(k)}$comment"
                    case _ => l
                })
            val added = keys.filterNot(written.contains).distinct.map(k => s"$k=${values(k)}")
            file.copy(lines = lines ++ added)
        }

        def getProperty(key: String): String = synchronized {
            Option(map.get(key)).getOrElse(throw new NoSuchElementException(key))
        }

        def setProperty(key: String, value: String): Unit = synchronized {
            map.put(key, value)
        }

        def getString(key: String): String =
            getProperty(key)

        def getInt(key: String): Int =
            getString(key).toInt

        def getDouble(key: String): Double =
            getString(key).toDouble

        // "true" or "false" in any case (W10Wheel.NET writes "True")
        def getBoolean(key: String): Boolean =
            getString(key).toBoolean

        def getArray(key: String): Array[String] =
            getString(key).split(",").map(_.strip).filter(!_.isEmpty)

        def getIntArray(key: String): Array[Int] =
            getArray(key).map(_.toInt)

        def getDoubleArray(key: String): Array[Double] =
            getArray(key).map(_.toDouble)

        def setInt(key: String, n: Int): Unit =
            setProperty(key, n.toString)

        def setBoolean(key: String, b: Boolean): Unit =
            setProperty(key, b.toString)

        private def entries = synchronized { new java.util.TreeMap[String, String](map) }

        override def equals(o: Any): Boolean = o match {
            case p: SProperties => (p eq this) || entries == p.entries
            case _ => false
        }

        override def hashCode: Int = entries.hashCode
    }

    // key=value, after the comment is removed and the line trimmed
    private val propLineReg = """^[^\s=]+\s*=\s*\S.*$""".r

    // The key and the value of a line (at the first "=": the value can have "="), None if it is not a
    // property (a comment, an empty line or anything else)
    private def parseLine(line: String): Option[(String, String)] = {
        val l = (line.indexOf('#') match {
            case -1 => line
            case n => line.substring(0, n)
        }).strip
        if (propLineReg.matches(l)) {
            val i = l.indexOf('=')
            Some((l.substring(0, i).strip, l.substring(i + 1).strip))
        }
        else
            None
    }

    // The lines of a text file, with what is kept when it is written again: its line break (the
    // first one in it), its BOM and the encoding it tells
    private case class TextFile(lines: List[String], newline: String, bom: Boolean,
                                charset: java.nio.charset.Charset = StandardCharsets.UTF_8)

    private object TextFile {
        // U+FEFF (not written as the character itself: invisible, it could be removed by mistake)
        private val BOM = 0xFEFF.toChar.toString

        // The encodings a BOM tells, as W10Wheel.NET reads them (File.ReadAllLines): the longer
        // BOMs first (that of UTF-32LE starts with that of UTF-16LE)
        private val BOMS = List(
            Array(0xEF, 0xBB, 0xBF) -> StandardCharsets.UTF_8,
            Array(0xFF, 0xFE, 0x00, 0x00) -> java.nio.charset.Charset.forName("UTF-32LE"),
            Array(0x00, 0x00, 0xFE, 0xFF) -> java.nio.charset.Charset.forName("UTF-32BE"),
            Array(0xFF, 0xFE) -> StandardCharsets.UTF_16LE,
            Array(0xFE, 0xFF) -> StandardCharsets.UTF_16BE)

        // UTF-8 without a BOM: an invalid byte (e.g. a comment in another encoding) is replaced,
        // not an error
        def read(path: Path): TextFile = {
            val bytes = Files.readAllBytes(path)
            val found = BOMS.find { case (b, _) => bytes.length >= b.length && b.indices.forall(i => (bytes(i) & 0xFF) == b(i)) }
            val bom = found.isDefined
            val (skip, charset) = found.map { case (b, cs) => (b.length, cs) }.getOrElse((0, StandardCharsets.UTF_8))
            val text = new String(bytes, skip, bytes.length - skip, charset)
            val newline =
                if (text.contains("\r\n")) "\r\n"
                else if (text.contains("\n")) "\n"
                else if (text.contains("\r")) "\r"
                else System.lineSeparator
            // The line break at the end does not start another line
            val lines = text.split("\r\n|\n|\r", -1).toList
            TextFile(if (lines.lastOption.contains("")) lines.init else lines, newline, bom, charset)
        }

        // The bytes of the file as written: its lines, each with its line break, in its encoding
        def bytesOf(file: TextFile): Array[Byte] =
            ((if (file.bom) BOM else "") + file.lines.map(_ + file.newline).mkString).getBytes(file.charset)

        // Written to a file next to it, then moved over it: the file is not left half written if
        // the write fails (e.g. the disk is full) or W10Wheel ends meanwhile. The name of the file
        // written is unique (e.g. W10Wheel.NET may write the same properties at the same time). A
        // link is followed (the file it points to is replaced, not the link). A read-only file is
        // not replaced (an error). Where a file cannot be moved atomically (e.g. some network
        // drives), it is moved over the file as it can be.
        def write(path: Path, file: TextFile): Unit = {
            val target = if (Files.exists(path)) path.toRealPath() else linkTarget(path)
            if (Files.exists(target) && !Files.isWritable(target))
                throw new AccessDeniedException(target.toString, null, "The file is read-only")

            val dir = Option(target.toAbsolutePath.getParent).getOrElse(Paths.get(USER_DIR))
            val tmp = Files.createTempFile(dir, target.getFileName.toString + "-", ".tmp")
            try {
                Files.write(tmp, bytesOf(file))
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
                catch {
                    case _: AtomicMoveNotSupportedException =>
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            finally {
                // Not over the error of the write
                try Files.deleteIfExists(tmp)
                catch { case e: IOException => () }
            }
        }

        // The file a symbolic link to no file points to (made there, as W10Wheel.NET writes it:
        // the link is not replaced by the file), through the links it points to; path if it is
        // not a link
        private def linkTarget(path: Path): Path = {
            var p = path
            var n = 0
            while (Files.isSymbolicLink(p)) {
                n += 1
                if (n > 40)
                    throw new FileSystemException(path.toString, null, "Too many levels of symbolic links")
                val to = Files.readSymbolicLink(p)
                p = Option(p.getParent).map(_.resolve(to)).getOrElse(to)
            }
            p
        }
    }

    val PROGRAM_NAME = Context.PROGRAM_NAME
    val PROP_NAME = s".$PROGRAM_NAME"
    val PROP_EXT = "properties"
    val DEFAULT_PROP_NAME = s"$PROP_NAME.$PROP_EXT" 
    // The folder of the properties files: the user's profile (the tests use a folder of their own)
    @volatile var USER_DIR: String = System.getProperty("user.home")
    
    val DEFAULT_DEF = "Default"
    
    private val BAD_DEFAULT_NAME = s"$PROP_NAME.$DEFAULT_DEF.$PROP_EXT"
    private val userDefReg = s"^\\.$PROGRAM_NAME\\.(?!--)(.+)\\.$PROP_EXT$$".r
    
    // Files only. Not the file of Default whatever its case (Default cannot be a name of
    // properties: Context.whyNotFileName).
    private def isPropFile(f: File): Boolean = {
        val name = f.getName
        f.isFile && !name.equalsIgnoreCase(BAD_DEFAULT_NAME) && userDefReg.findFirstIn(name).isDefined
    }
    
    def getUserDefName(file: File) = {
        file.getName match {
            case userDefReg(uname) => uname
            case s => throw new MatchError(s)
        }
    }
    
    // Empty if the folder cannot be listed
    def getPropFiles: Array[File] = {
        Option(new File(USER_DIR).listFiles()).getOrElse(Array.empty[File]).filter(isPropFile)
    }
     
    def getDefaultPath =
        Paths.get(USER_DIR, DEFAULT_PROP_NAME)
    
    def getPath(name: String): Path = {
        if (name == DEFAULT_DEF)
            getDefaultPath
        else
            Paths.get(USER_DIR, s"$PROP_NAME.$name.$PROP_EXT")
    }
    
    def exists(name: String): Boolean =
        Files.exists(getPath(name))

    // The name of existing properties as in their file name (None if they do not exist, as the file
    // system tells): name may differ in case (e.g. typed as an argument), which Windows ignores, but
    // not the list of the settings dialog, which compares the names (the selected properties would
    // not be shown). The same name if it is listed (only a folder made case-sensitive can have both
    // cases).
    def findName(name: String): Option[String] = {
        if (!exists(name))
            None
        else {
            val names = try getPropFiles.map(getUserDefName) catch { case _: Exception => Array.empty[String] }
            names.find(_ == name)
                .orElse(names.find(_.equalsIgnoreCase(name)))
                .orElse(Some(name))
        }
    }
    
    // The content of the file of new properties (Context.addProperties), made complete in memory
    // before the file is created: a copy of the file of src (its comments, its other keys, e.g. the
    // custom table, its line breaks and its encoding) with the values of all the keys of p, or p
    // alone if src has no file (e.g. Default not stored yet). The new properties are loaded from
    // its lines.
    final class NewText private[Properties] (private[Properties] val file: TextFile) {
        def lines: List[String] = file.lines
    }

    // Another error than a missing file (e.g. locked) is thrown
    def newText(src: Path, p: SProperties): NewText = {
        val read = try Some(TextFile.read(src)) catch { case _: NoSuchFileException => None }
        new NewText(read match {
            case Some(f) => p.updatedFile(f, p.keys)
            case None => p.storedFile
        })
    }

    // The properties file of new properties (Context.addProperties), held open from its creation
    // until the Add is done. Created only if there is none (CREATE_NEW): a file made there by
    // another program since the name was checked is not replaced (FileAlreadyExistsException).
    // Written whole at once with its final content (NewText), through this handle, which shares
    // nothing: while it is held, no other program can open the file at all, so none can write,
    // replace, rename or delete it, and the file written, and the one deleted if the Add fails
    // (discard), is always this one. Not even reading is shared (nothing needs it: the Add loads
    // what it wrote from memory): a program reading it could map it into memory, and Windows does
    // not delete a file mapped so, which would leave the file of a failed Add behind, its name
    // taken. Through Kernel32 (JNA): Java opens its files sharing reading and writing, and cannot
    // delete a file through its handle.
    final class NewFile private (handle: WinNT.HANDLE) {
        private var open = true

        // The Add failed: this file (not whatever is at its path) is deleted when the handle closes,
        // which it is now. An error is not thrown (over the error of the Add): there is none to
        // expect, nothing else can have the file open. The handle is closed whatever happens here:
        // the file is not left held.
        def discard(): Unit = {
            if (open) {
                val delete = new Memory(1)
                try {
                    delete.setByte(0, 1.toByte)
                    if (!NewFile.k32.SetFileInformationByHandle(handle, NewFile.FileDispositionInfo, delete, new DWORD(1)))
                        logger.warn(s"NewFile.discard: ${NewFile.lastError}")
                }
                catch {
                    case e: Exception => logger.warn(s"NewFile.discard: $e")
                }
                finally {
                    delete.close()
                    close()
                }
            }
        }

        // The Add is done: the file is released
        def close(): Unit = {
            if (open) {
                open = false
                try {
                    if (!NewFile.k32.CloseHandle(handle))
                        logger.warn(s"NewFile.close: ${NewFile.lastError}")
                }
                catch {
                    case e: Exception => logger.warn(s"NewFile.close: $e")
                }
            }
        }
    }

    object NewFile {
        private val k32 = Kernel32.INSTANCE
        private val GENERIC_WRITE = 0x40000000
        private val DELETE = 0x00010000
        private val CREATE_NEW = 1
        private val FILE_ATTRIBUTE_NORMAL = 0x80
        private val ERROR_FILE_EXISTS = 80
        private val ERROR_ALREADY_EXISTS = 183
        // FILE_INFO_BY_HANDLE_CLASS: FileDispositionInfo (FILE_DISPOSITION_INFO, a BOOLEAN DeleteFile)
        private val FileDispositionInfo = 4

        private def lastError: String = Kernel32Util.formatMessage(Native.getLastError()).strip

        // The path given to CreateFileW: W10Wheel is not long path aware (no longPathAware in a
        // manifest), so a long full path fails there, which the file APIs of Java open (they add the
        // prefix of an extended-length path themselves, from 248 characters). The same prefix here:
        // \\?\ (\\?\UNC\ for a network path), on the full path normalized (Windows does not normalize
        // an extended-length path).
        private def win32Path(path: Path): String = {
            val full = path.toAbsolutePath.normalize.toString
            if (full.length < 248 || full.startsWith("""\\?\""") || full.startsWith("""\\.\""")) full
            else if (full.startsWith("""\\""")) """\\?\UNC\""" + full.substring(2)
            else """\\?\""" + full
        }

        def create(path: Path, text: NewText): NewFile = {
            val handle = k32.CreateFile(win32Path(path), GENERIC_WRITE | DELETE, 0, null, CREATE_NEW,
                                        FILE_ATTRIBUTE_NORMAL, null)
            if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                val err = Native.getLastError()
                if (err == ERROR_FILE_EXISTS || err == ERROR_ALREADY_EXISTS)
                    throw new FileAlreadyExistsException(path.toString)
                else
                    throw new IOException(s"${Kernel32Util.formatMessage(err).strip}: $path")
            }

            val file = new NewFile(handle)
            try {
                val bytes = TextFile.bytesOf(text.file)
                val written = new IntByReference
                var offset = 0
                while (offset < bytes.length) {
                    val rest = java.util.Arrays.copyOfRange(bytes, offset, bytes.length)
                    if (!k32.WriteFile(handle, rest, rest.length, written, null) || written.getValue <= 0)
                        throw new IOException(s"$lastError: $path")
                    offset += written.getValue
                }
                if (!k32.FlushFileBuffers(handle))
                    throw new IOException(s"$lastError: $path")
            }
            catch {
                case e: Throwable =>
                    // Not left half written
                    file.discard()
                    throw e
            }
            file
        }
    }

    def delete(name: String) = {
        Files.delete(getPath(name))
    }

    // The excluded programs of the properties of name are in a file of their own, beside theirs (as
    // W10Wheel.NET keeps them): .W10Wheel.exclusions (Default), .W10Wheel.<name>.exclusions. Not a
    // key of the properties: a path can have "#", which starts a comment there. Not a .txt file,
    // which could be edited by mistake.
    private val EXCLUSIONS_EXT = "exclusions"

    def getExclusionsPath(name: String): Path = {
        if (name == DEFAULT_DEF)
            Paths.get(USER_DIR, s"$PROP_NAME.$EXCLUSIONS_EXT")
        else
            Paths.get(USER_DIR, s"$PROP_NAME.$name.$EXCLUSIONS_EXT")
    }

    // A path on each line (no comments), the spaces at the ends and the empty lines ignored (UTF-8,
    // a BOM skipped). Not found: none. Another error (e.g. locked) is thrown.
    def readExclusions(path: Path): Array[String] = {
        try {
            TextFile.read(path).lines.map(_.strip).filter(_.nonEmpty).toArray
        }
        catch {
            case _: NoSuchFileException => Array.empty[String]
        }
    }

    // Written whole (as the properties: a write that fails leaves the file as it was), or deleted
    // when there is none (not found: the default, none). A symbolic link is emptied instead: it
    // stays a link, as the write keeps it (the file it points to is written).
    def writeExclusions(path: Path, paths: Array[String]): Unit = {
        if (paths.isEmpty && !Files.isSymbolicLink(path))
            Files.deleteIfExists(path)
        else
            TextFile.write(path, TextFile(paths.toList, System.lineSeparator, false))
    }
}