package hooktest

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.{Logger => SLogger}

object Logger {
    private lazy val logger = SLogger(LoggerFactory.getLogger(Context.PROGRAM_NAME))
    
    def getLogger(): SLogger =
        logger
}