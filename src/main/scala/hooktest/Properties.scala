package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file._

import scala.jdk.CollectionConverters._

object Properties {
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
        def load(path: Path): Unit = {
            val lines = TextFile.read(path).lines
            synchronized {
                lines.foreach(l => parseLine(l).foreach { case (k, v) => map.put(k, v) })
            }
        }

        // A new file with all the keys (e.g. Default written the first time)
        def store(path: Path): Unit = {
            val lines = synchronized { map.asScala.map { case (k, v) => s"$k=$v" }.toList }
            TextFile.write(path, TextFile(lines, System.lineSeparator, false))
        }

        // Writes the values of keys to the file (path) as it is now: the lines of these keys get
        // their values (a comment after a value is kept), the other lines stay as they are (the other
        // keys, the comments, the empty lines, their order, the line breaks), and the keys not in it
        // are added at the end
        def update(path: Path, keys: Seq[String]): Unit = {
            val file = TextFile.read(path)
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
            TextFile.write(path, file.copy(lines = lines ++ added))
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
    // first one in it) and its BOM
    private case class TextFile(lines: List[String], newline: String, bom: Boolean)

    private object TextFile {
        private val BOM = "﻿"

        // UTF-8: an invalid byte (e.g. a comment in another encoding) is replaced, not an error
        def read(path: Path): TextFile = {
            val all = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            val bom = all.startsWith(BOM)
            val text = if (bom) all.substring(1) else all
            val newline =
                if (text.contains("\r\n")) "\r\n"
                else if (text.contains("\n")) "\n"
                else if (text.contains("\r")) "\r"
                else System.lineSeparator
            // The line break at the end does not start another line
            val lines = text.split("\r\n|\n|\r", -1).toList
            TextFile(if (lines.lastOption.contains("")) lines.init else lines, newline, bom)
        }

        // Written to a file next to it, then moved over it: the file is not left half written if
        // the write fails (e.g. the disk is full) or W10Wheel ends meanwhile. A link is followed (the
        // file it points to is replaced, not the link). A read-only file is not replaced (an error).
        def write(path: Path, file: TextFile): Unit = {
            val target = if (Files.exists(path)) path.toRealPath() else path
            if (Files.exists(target) && !Files.isWritable(target))
                throw new AccessDeniedException(target.toString, null, "The file is read-only")

            val text = (if (file.bom) BOM else "") + file.lines.map(_ + file.newline).mkString
            val tmp = target.resolveSibling(target.getFileName.toString + ".tmp")
            try {
                Files.write(tmp, text.getBytes(StandardCharsets.UTF_8))
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

    
    val PROGRAM_NAME = Context.PROGRAM_NAME
    val PROP_NAME = s".$PROGRAM_NAME"
    val PROP_EXT = "properties"
    val DEFAULT_PROP_NAME = s"$PROP_NAME.$PROP_EXT" 
    val USER_DIR = System.getProperty("user.home")
    
    val DEFAULT_DEF = "Default"
    
    private val BAD_DEFAULT_NAME = s"$PROP_NAME.$DEFAULT_DEF.$PROP_EXT"
    private val userDefReg = s"^\\.$PROGRAM_NAME\\.(?!--)(.+)\\.$PROP_EXT$$".r
    
    private def isPropFile(f: File): Boolean = {
        val name = f.getName
        name != BAD_DEFAULT_NAME && userDefReg.findFirstIn(name).isDefined
    }
    
    def getUserDefName(file: File) = {
        file.getName match {
            case userDefReg(uname) => uname
            case s => throw new MatchError(s)
        }
    }
    
    def getPropFiles = {
        new File(USER_DIR).listFiles().filter(isPropFile)
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
    
    def copy(srcName: String, destName: String) = {
        val srcPath = getPath(srcName)
        val destPath = getPath(destName)
        
        Files.copy(srcPath, destPath)
        // Not read-only as the source may be (the copy keeps its attributes): the new properties
        // are written when a setting is changed
        try {
            Files.setAttribute(destPath, "dos:readonly", false)
        }
        catch {
            case _: UnsupportedOperationException | _: IOException => ()
        }
    }
    
    def delete(name: String) = {        
        Files.delete(getPath(name))
    }
}