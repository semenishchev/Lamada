package cc.olek.lamada.redis;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

public class RedisExecutor {
    private static final Logger log = LoggerFactory.getLogger(RedisExecutor.class);
    public static final RedisExecutor INSTANCE;
    public static final Executor JAVA_EXECUTOR;
    static {
        RedisExecutor toSet;
        Executor executor;
        try {
            Thread.class.getMethod("ofVirtual");
            toSet = (RedisExecutor) Class.forName("cc.olek.lamada.redis.VirtualExecutor").getConstructor().newInstance();
            //noinspection Since15
            executor = Executors.newVirtualThreadPerTaskExecutor();
        } catch(Throwable ignored) {
            log.warn("Lamada would benefit of running the application under JVM21+ because of virtual threads. Consider it");
            toSet = new RedisExecutor();
            executor = new ThreadPoolExecutor(1, Runtime.getRuntime().availableProcessors(), 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), r -> new Thread(r));
        }
        JAVA_EXECUTOR = executor;
        INSTANCE = toSet;
    }

    public Thread unstarted(Runnable runnable) {
        return new Thread(runnable);
    }
}
