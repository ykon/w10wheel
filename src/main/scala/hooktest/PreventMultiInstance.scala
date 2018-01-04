package hooktest

/*
 * Copyright (c) 2016 Yuki Ono
 * Licensed under the MIT License.
 */

import java.io._
import java.nio.channels._

// https://gist.github.com/seraphy/5502048
// https://www.daniweb.com/programming/software-development/threads/83331/how-to-stop-opening-multiple-instance-for-a-jar-file

object PreventMultiInstance {
    val LOCK_FILE_DIR = System.getProperty("java.io.tmpdir")
    val LOCK_FILE_NAME = Context.PROGRAM_NAME + ".lock"
    
    private val logger = Logger.getLogger()
    
    @volatile private var channel: Option[FileChannel] = None
    @volatile private var lock: Option[FileLock] = None
    
    def tryLock: Boolean = {
        logger.debug("tryLock")
        
        if (lock.isDefined)
            throw new IllegalStateException()
        
        try {
            val file = new File(LOCK_FILE_DIR, LOCK_FILE_NAME)
            channel = Option(new RandomAccessFile(file, "rw").getChannel())    
            lock = Option(channel.get.tryLock)
            
            lock.isDefined
        }
        catch {
            case e: Exception => logger.warn(s"tryLock: $e"); false
        }
    }
    
    def unlock {
        logger.debug("unlock")
        
        if (lock.isEmpty)
            return
                
        try {            
            lock.get.release()
            channel.get.close
            
            lock = None
            channel = None
        }
        catch {
            case e: Exception => logger.warn(s"unlock: $e")
        }
    }
}