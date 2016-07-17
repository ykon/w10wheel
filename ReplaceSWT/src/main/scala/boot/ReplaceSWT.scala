package boot

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import javax.swing._
import java.security.MessageDigest
import java.nio.file._

object ReplaceSWT {
    // http://d.hatena.ne.jp/maachang/20101009
    private def getSystemBit = {
        val dm = System.getProperty("sun.arch.data.model")
        if (dm != null && dm.trim().length() > 0) {
            dm.trim() match {
                case "32" => 32
                case "64" => 64
            }
        }
        else {
            val arch = System.getProperty("os.arch")
            if (arch == null || arch.trim().length() == 0) -1
            else if (arch.endsWith("86")) 32
            else if (arch.endsWith("64")) 64
            else 32
        }
    }
    
    private val is32Bit = getSystemBit == 32
    private val is64Bit = getSystemBit == 64
    
    private val SWT_JAR_NAME = "swt.jar"
    private val SWT_JAR_32BIT_SHA1 = "AFDCF6B73F1458CF0EDECC8EAB88749D540DE0CE"
    private val SWT_JAR_64BIT_SHA1 = "1233D24910A66519EC7EDEFC55796943005FB9C9"
    
    private def getSelfPath = {
        Paths.get(getClass.getProtectionDomain().getCodeSource().getLocation().toURI())
    }
    
    private def getSwtJarPath: Path = {
        try {
            getSelfPath.resolveSibling("lib\\" + SWT_JAR_NAME)
        }
        catch {
            case _: Exception => null
        }
    }
    
    private def getSha1(path: Path) = {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = Files.readAllBytes(path)
        md.update(bytes)        
        md.digest().map("%02X" format _).mkString
    }
    
    private def errorMessage(msg: String) {
        JOptionPane.showMessageDialog(null, msg, "Error", JOptionPane.ERROR_MESSAGE)
    }
    
    private def errorMessage(e: Exception) {
        errorMessage(s"${e.getClass.getName}: ${e.getMessage}")
    }
    
    private def infoMessage(msg: String) {
        JOptionPane.showMessageDialog(null, msg, "Info", JOptionPane.INFORMATION_MESSAGE)
    }
    
    private val SWT_JAR_32BIT_PATH = "lib32\\swt.jar"
    private val SWT_JAR_64BIT_PATH = "lib64\\swt.jar"
    
    private def replace(src: Path, dest: Path) {
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
    }
    
    private def replaceSwtJar {
        //infoMessage("replaceSwtJar")
        val swtPath = getSwtJarPath
        if (swtPath == null) {
            errorMessage("Not found 'swt.jar'")
            return
        }
        
        val sha1 = getSha1(swtPath)
        
        if ((is32Bit && sha1 == SWT_JAR_32BIT_SHA1) || (is64Bit && sha1 == SWT_JAR_64BIT_SHA1)) {
            infoMessage("Already replaced")
            return
        }
        
        val srcPath = swtPath.resolveSibling(if (is32Bit) SWT_JAR_32BIT_PATH else SWT_JAR_64BIT_PATH)
        
        try {
            Files.copy(srcPath, swtPath, StandardCopyOption.REPLACE_EXISTING)
            infoMessage("Replacement is complete")
        }
        catch {
            case e: Exception => errorMessage(e)
        }
    }
    
    private def isValidSha1(swtPath: Path) = {
        val digest = if (is32Bit) SWT_JAR_32BIT_SHA1 else SWT_JAR_64BIT_SHA1
        getSha1(swtPath) == digest
    }
    
    private def replaceAndRestart(exePath: String) {
        val swtPath = getSwtJarPath
        val srcPath = swtPath.resolveSibling(if (is32Bit) SWT_JAR_32BIT_PATH else SWT_JAR_64BIT_PATH)

        (1 to 10).foreach { i =>
            try {
                Thread.sleep(100 * i)
                Files.copy(srcPath, swtPath, StandardCopyOption.REPLACE_EXISTING)
                
                if (isValidSha1(swtPath)) {
                    new ProcessBuilder(exePath).start
                    System.exit(0)
                }
            }
            catch {
                case _: Exception => {}
            }
        }
    }
    
    def main(args: Array[String]) {
        //infoMessage("main")
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        
        if (args.isEmpty)
            replaceSwtJar
        else if (args.length == 1)
            replaceAndRestart(args(0))
    }
}