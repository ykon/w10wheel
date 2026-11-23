package hooktest

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.{Logger => SLogger}

object Logger {
    // Not Context.PROGRAM_NAME: the first object to get the logger (e.g. Theme) would initialize
    // Context, which gets the logger while it is being made (the lazy val would wait for itself)
    private lazy val logger = SLogger(LoggerFactory.getLogger("W10Wheel"))
    
    def getLogger(): SLogger =
        logger
}
