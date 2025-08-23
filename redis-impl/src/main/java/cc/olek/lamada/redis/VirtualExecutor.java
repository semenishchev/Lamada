package cc.olek.lamada.redis;

public class VirtualExecutor extends RedisExecutor{
    @Override
    public Thread unstarted(Runnable runnable) {
        //noinspection Since15
        return Thread.ofVirtual().unstarted(runnable);
    }
}
