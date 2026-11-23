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
    
    private def lockOnce(): Boolean = {
        var ch: FileChannel = null
        try {
            val file = new File(LOCK_FILE_DIR, LOCK_FILE_NAME)
            ch = new RandomAccessFile(file, "rw").getChannel()
            val l = ch.tryLock()
            if (l != null) {
                channel = Some(ch)
                lock = Some(l)
                true
            }
            else {
                ch.close()
                false
            }
        }
        catch {
            case e: Exception =>
                logger.debug(s"tryLock: $e")
                if (ch != null) ch.close()
                false
        }
    }

    // waitMs: how long to wait for the lock of another instance (after a restart as administrator:
    // the old instance is exiting, it releases its lock after storing the properties)
    def tryLock(waitMs: Long = 0): Boolean = {
        logger.debug("tryLock")
        
        if (lock.isDefined)
            throw new IllegalStateException()

        val end = System.nanoTime + waitMs * 1000000
        var res = lockOnce()
        while (!res && System.nanoTime < end) {
            Thread.sleep(100)
            res = lockOnce()
        }
        res
    }
    
    def unlock: Unit = {
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