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
    
    private var channel: FileChannel = null
    private var lock: FileLock = null
    
    def tryLock: Boolean = {
        logger.debug("tryLock")
        try {
            if (lock != null)
                throw new IllegalStateException()
        
            val file = new File(LOCK_FILE_DIR, LOCK_FILE_NAME)
            channel = new RandomAccessFile(file, "rw").getChannel()    
            lock = channel.tryLock
            lock != null
        }
        catch {
            case e: Exception => logger.warn(s"tryLock: $e")
            false
        }
    }
    
    def unlock {
        logger.debug("unlock")
        try {
            if (lock == null)
                throw new IllegalStateException()
            
            lock.release
            channel.close
        }
        catch {
            case e: Exception => logger.warn(s"unlock: $e")
        }
    }
}