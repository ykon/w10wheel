package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.io._
import java.nio.file._

object Properties {
    class SProperties extends java.util.Properties {        
        // http://stackoverflow.com/questions/17011108/how-can-i-write-java-properties-in-a-defined-order
        override def keys(): java.util.Enumeration[Object] = synchronized {
            java.util.Collections.enumeration(new java.util.TreeSet[Object](super.keySet))
        }
        
        override def load(inStream: java.io.InputStream) = synchronized {
            super.load(inStream)
            inStream.close
        }
        
        override def load(reader: java.io.Reader) = synchronized {
            super.load(reader)
            reader.close
        }
        
        def load(path: Path): Unit = synchronized {
            this.load(new FileInputStream(path.toFile))
        }
        
        private def getPropertyE(key: String): String = synchronized {
            Option(super.getProperty(key)) match {
                case None => throw new NoSuchElementException(key)
                case Some(res) => res
            }
        }
        
        def getString(key: String) = synchronized {
            getPropertyE(key)
        }
        
        def getInt(key: String) = synchronized {
            getString(key).toInt
        }
        
        def getDouble(key: String) = synchronized {
            getString(key).toDouble
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
        
        def setDouble(key: String, d: Double) = synchronized {
            super.setProperty(key, ("%.2f" format d))
        }
        
        def setBoolean(key: String, b: Boolean) = synchronized {
            super.setProperty(key, b.toString)
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
        val userDefReg(uname) = file.getName
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
    
    def exists(name: String): Boolean =
        Files.exists(getPath(name))
    
    def copy(srcName: String, destName: String) = {
        val srcPath = getPath(srcName)
        val destPath = getPath(destName)
        
        Files.copy(srcPath, destPath)
    }
    
    def delete(name: String) = {        
        Files.delete(getPath(name))
    }
}