package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.io._
import java.nio.file._

object Properties {
    class SProperties extends java.util.Properties {
        private var loaded = false
        
        // http://stackoverflow.com/questions/17011108/how-can-i-write-java-properties-in-a-defined-order
        override def keys(): java.util.Enumeration[Object] = synchronized {
            java.util.Collections.enumeration(new java.util.TreeSet[Object](super.keySet))
        }
        
        override def load(inStream: java.io.InputStream) = synchronized {
            super.load(inStream)
            inStream.close
            loaded = true
        }
        
        override def load(reader: java.io.Reader) = synchronized {
            super.load(reader)
            reader.close
            loaded = true
        }
        
        def load(path: Path): Unit = synchronized {
            load(new FileInputStream(path.toFile))
        }
        
        def isLoaded = loaded
        
        def getPropertyE(key: String): String = synchronized {
            val res = super.getProperty(key)
            if (res != null) res else throw new NoSuchElementException(key)
        }
        
        def getString(key: String) = synchronized {
            getPropertyE(key)
        }
        
        def getInt(key: String) = synchronized {
            getString(key).toInt
        }
        
        def getBoolean(key: String) = synchronized {
            getString(key).toBoolean
        }
        
        def getArray(key: String): Array[String] = synchronized {
            getString(key).split(",").map(_.trim).filter(!_.isEmpty)
        }
        
        def getIntArray(key: String): Array[Int] = synchronized {
            getArray(key).map(_.toInt)
        }
        
        def getDoubleArray(key: String): Array[Double] = synchronized {
            getArray(key).map(_.toDouble)
        }
        
        def store(out: java.io.OutputStream) = synchronized {
            super.store(out, null)
            out.close
        }
        
        def store(writer: java.io.Writer) = synchronized {
            super.store(writer, null)
            writer.close
        }
        
        def store(path: Path): Unit = synchronized {
            store(new FileOutputStream(path.toFile))
        }
        
        def setInt(key: String, n: Int) = synchronized {
            super.setProperty(key, n.toString)
        }
        
        def setBoolean(key: String, b: Boolean) = synchronized {
            super.setProperty(key, b.toString)
        }
    }
    
    val PROP_NAME = s".${Context.PROGRAM_NAME}"
    val PROP_EXT = "properties"
    val DEFAULT_PROP_NAME = s"$PROP_NAME.$PROP_EXT" 
    val USER_DIR = System.getProperty("user.home")
    
    val DEFAULT_DEF = "Default"
    
    private val BAD_DEFAULT_NAME = s"$PROP_NAME.$DEFAULT_DEF.$PROP_EXT"
    private val userDefPat = s"^$PROP_NAME.(.+).$PROP_EXT$$".r
    
    private def isPropFile(f: File): Boolean = {
        val name = f.getName
        name != BAD_DEFAULT_NAME && userDefPat.findFirstIn(name).isDefined
    }
    
    def getUserDefName(file: File) = {
        val userDefPat(uname) = file.getName
        uname
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
    
    def copyProperties(srcName: String, destName: String) = {
        val srcPath = getPath(srcName)
        val destPath = getPath(destName)
        
        Files.copy(srcPath, destPath)
    }
    
    def deleteProperties(name: String) = {        
        Files.delete(getPath(name))
    }
}