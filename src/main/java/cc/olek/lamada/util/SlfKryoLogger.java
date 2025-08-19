package cc.olek.lamada.util;

import com.esotericsoftware.minlog.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

public class SlfKryoLogger extends Log.Logger {
    private static final Logger LOGGER = LoggerFactory.getLogger("Kryo");
    private static final Level[] LEVELS = {Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR};
    @Override
    public void log(int level, String category, String message, Throwable ex) {
        if(ex != null) {
            LOGGER.error("{}: {}", category, message, ex);
            return;
        }

        LOGGER.info("{} {}", LEVELS[level - 1], message);
    }
}
