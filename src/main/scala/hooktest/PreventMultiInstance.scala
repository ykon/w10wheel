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
    
    private val logger = Context.logger
    
    @volatile private var channel: FileChannel = null
    @volatile private var lock: FileLock = null
    
    def isLocked: Boolean =
        (lock != null)
    
    def tryLock: Boolean = {
        logger.debug("tryLock")
        
        if (isLocked)
            throw new IllegalStateException()
        
        try {
            val file = new File(LOCK_FILE_DIR, LOCK_FILE_NAME)
            channel = new RandomAccessFile(file, "rw").getChannel()    
            lock = channel.tryLock
            
            isLocked
        }
        catch {
            case e: Exception => logger.warn(s"tryLock: $e"); false
        }
    }
    
    def unlock {
        logger.debug("unlock")
        
        if (!isLocked)
            return
                
        try {            
            lock.release
            channel.close
            
            lock = null
            channel = null
        }
        catch {
            case e: Exception => logger.warn(s"unlock: $e")
        }
    }
}