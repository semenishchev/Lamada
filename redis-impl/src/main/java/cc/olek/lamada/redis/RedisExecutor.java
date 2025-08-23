package cc.olek.lamada.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisExecutor {
    private static final Logger log = LoggerFactory.getLogger(RedisExecutor.class);
    public static final RedisExecutor INSTANCE;
    static {
        RedisExecutor toSet;
        try {
            Thread.class.getMethod("ofVirtual");
            toSet = (RedisExecutor) Class.forName("cc.olek.lamada.redis.VirtualExecutor").getConstructor().newInstance();
        } catch(Throwable ignored) {
            log.warn("Lamada would benefit of running the application under JVM21+ because of virtual threads. Consider it");
            toSet = new RedisExecutor();
        }
        INSTANCE = toSet;
    }

    public Thread unstarted(Runnable runnable) {
        return new Thread(runnable);
    }
}
