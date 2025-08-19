package cc.olek.lamada.redis;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.DistributedObject;
import cc.olek.lamada.InstructionCommunicator;
import cc.olek.lamada.RemoteTargetManager;
import cc.olek.lamada.asm.LambdaImpl;
import cc.olek.lamada.asm.LambdaReconstructor;
import cc.olek.lamada.asm.MethodImpl;
import cc.olek.lamada.context.ExecutionContext;
import cc.olek.lamada.context.InvocationResult;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.SetParams;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class RedisImplementation extends RemoteTargetManager<String> implements InstructionCommunicator<String> {
    private final Logger logger;
    private final Int2ObjectMap<CompletableFuture<byte[]>> submittedFutures = Int2ObjectMaps.synchronize(new Int2ObjectOpenHashMap<>());
    private final JedisPool pool;
    private final Object2IntMap<LambdaImpl> ownImpls = new Object2IntOpenHashMap<>();
    private final Map<String, Int2ObjectMap<LambdaImpl>> lookup = new HashMap<>();
    private final Function<String, Boolean> activityCheck;

    public RedisImplementation(DistributedExecutor<String> executor, JedisPool pool) {
        this(executor, pool, null);
    }

    public RedisImplementation(DistributedExecutor<String> executor, JedisPool pool, Function<String, Boolean> activityCheck) {
        super(executor);
        this.pool = pool;
        this.logger = LoggerFactory.getLogger("RedisExecutor-" + executor.getPurpose());
        final String ownChannel = "op:" + executor.getOwnTarget();
        Thread accepting = new Thread(() -> {
            JedisPubSub pubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    if(LambdaReconstructor.DEBUG) {
                        logger.info("Message {} on {}", message, channel);
                    }
                    try {
                        onMessageReceived(channel, message, pool, executor);
                    } catch(Throwable t) {
                        logger.error("Failed to receive message {} on {}", message, channel, t);
                    }
                }
            };
            try(Jedis jedis = pool.getResource()) {
                jedis.subscribe(pubSub, ownChannel);
            }
        });
        accepting.setDaemon(true);
        accepting.setName("lamada-redis-thread");
        accepting.start();
        this.activityCheck = activityCheck;
        if(activityCheck == null) {
            // todo: not used now
        }
    }

    private void onMessageReceived(String channel, String message, JedisPool pool, DistributedExecutor<String> executor) {
        String[] data = message.split(":");
        if(data.length != 3) {
            throw new RuntimeException("Rejecting malformed message on " + channel + ": " + message);
        }
        String action = data[0];
        String sender = data[1];
        int opNumber = Integer.parseInt(data[2]);
        switch(action) {
            case "w", "n" -> {
                boolean waitForReply = action.equals("w");
                // not using try(var) because of too much tabs
                Jedis jedis = pool.getResource();
                byte[] key = (channel + ":" + opNumber).getBytes(StandardCharsets.UTF_8);
                byte[] operation = jedis.getDel(key);
                if(operation == null) {
                    logger.error("Failed to find operation with number {} (tried to read key {}", opNumber, new String(key));
                    return;
                }
                ExecutionContext context;
                try {
                    context = executor.receiveContext(operation, sender);
                } catch(Throwable t) {
                    logger.error("Failed to read context {} from {}", operation, sender, t);
                    if(!waitForReply) return;
                    sendResponseBack(sender, InvocationResult.ofError(opNumber, new RuntimeException("Failed to serialize context with number: " + opNumber, t)));
                    return;
                }

                if(context.deserializationError() != null) {
                    logger.error("Failed to deserialize context {} ({}) from {}", context.opNumber(), operation, sender, context.deserializationError());
                    if(!waitForReply) return;
                    sendResponseBack(sender, InvocationResult.ofError(context, context.deserializationError()));
                    return;
                }

                Thread.ofVirtual().start(() -> {
                    try {
                        InvocationResult invocation = executor.executeContext(context);
                        if(!waitForReply) return;
                        sendResponseBack(sender, invocation);
                    } catch(Throwable t) {
                        logger.error("Failed sending response back", t);
                        sendResponseBack(sender, InvocationResult.ofError(context, t));
                    }
                });
                jedis.close();
            }
            // complete submitted futures
            case "r" -> {
                try (Jedis jedis = pool.getResource()) {
                    byte[] response = jedis.getDel(("resp:" + executor.getOwnTarget() + ":" + opNumber).getBytes(StandardCharsets.UTF_8));
                    submittedFutures.get(opNumber).complete(response);
                }
            }
        }
    }

    private void sendResponseBack(String target, InvocationResult result) {
        byte[] toSend;
        try {
            toSend = this.executor.serializeResponse(result);
        } catch(Throwable t) {
            logger.error("Failed to serialize response {}", result.of().opNumber(), t);
            toSend = this.executor.serializeResponse(InvocationResult.ofError(result.of(), t));
        }
        try (Jedis jedis = this.pool.getResource()) {
            jedis.set(("resp:" + target + ":" + result.opNumber()).getBytes(StandardCharsets.UTF_8), toSend, SetParams.setParams().ex(30));
            jedis.publish("op:" + target, "r:" + executor.getOwnTarget() + ":" + result.opNumber());
        }
    }

    @Override
    public CompletableFuture<byte[]> send(DistributedObject<?, ?, String> obj, String to, int opNumber, byte[] data, boolean waitForReply) {
        DistributedExecutor<String> executor = obj.getExecutor();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        if(waitForReply) {
            submittedFutures.put(opNumber, result);
        }
        CompletableFuture<byte[]> finalResult = result;
        executor.getAsync().execute(() -> {
            try (Jedis jedis = this.pool.getResource()) {
                String key = "op:" + to;
                jedis.set((key + ":" + opNumber).getBytes(StandardCharsets.UTF_8), data, SetParams.setParams().ex(30));
                jedis.publish(key,  (waitForReply ? "w:" : "n:") + executor.getOwnTarget() + ":" + opNumber);
                if(!waitForReply) {
                    finalResult.complete(null);
                }
            }
        });
        if(waitForReply) {
            result = result.orTimeout(10, TimeUnit.SECONDS);
        }
        return result;
    }

    @Override
    public LambdaImpl reconstruct(String sender, int number) {
        Int2ObjectMap<LambdaImpl> lookups = lookup.get(sender);
        if(lookups == null) return null;
        return lookups.get(number);
    }

    @Override
    public void registerImplementation(String sender, short lambdaNum, LambdaImpl impl) {
        this.lookup.computeIfAbsent(sender, _ -> new Int2ObjectOpenHashMap<>()).put(lambdaNum, impl);
    }

    @Override
    public short getNewObjNumber(DistributedObject<?, ?, String> impl) {
        try(Jedis jedis = this.pool.getResource()) {
            String objectKey = "obj_runtime_num_" + impl.getObjectType().getName();
            String existing = jedis.get(objectKey);
            if(existing == null) {
                int num = super.getNewObjNumber(impl); // increment counter and register
                jedis.set(objectKey, String.valueOf(num));
                return (short) num;
            }
            return Short.parseShort(existing);
        }
    }

    @Override
    public SubmissionResult getOrSubmitOwn(String sendTo, LambdaImpl impl) {
        boolean[] existedBefore = {true};
        short num = (short) ownImpls.computeIfAbsent(impl, _ -> {
            existedBefore[0] = false;
            return getNewImplNumber(impl);
        });
        return new SubmissionResult(existedBefore[0], num);
    }

    @Override
    protected short getNewImplNumber(LambdaImpl impl) {
        short implNum = (short) this.counter.getAndIncrement();
        try(Jedis jedis = this.pool.getResource()) {
            jedis.set("lambda_impl_" + executor.getOwnTarget() + "_" + implNum, "%s:%d:%s:%s:%s:%s".formatted(
                impl.functionalInterface(),
                impl.implMethodKind(),
                impl.primarySignature(),
                impl.implementation().className(),
                impl.implementation().methodName(),
                impl.implementation().signature()
            ));
        }
        return implNum;
    }

    @Override
    public LambdaImpl requestMissingImplementation(String sender, short lambdaNum) {
        try(Jedis jedis = this.pool.getResource()) {
            String implValue = jedis.get("lambda_impl_" + sender + "_" + lambdaNum);
            if(implValue == null) return null;
            String[] data = implValue.split(":");
            return new LambdaImpl(data[0], Integer.parseInt(data[1]), data[2], new MethodImpl(
                data[3],
                data[4],
                data[5]
            ));
        }
    }

    @Override
    public boolean isTargetAvailable(String s) {
        Function<String, Boolean> activityCheck = this.activityCheck;
        if(activityCheck != null) {
            return activityCheck.apply(s);
        }
        return true; // todo: implement check if target exists
    }

    @Override
    public void resync() {
        throw new UnsupportedOperationException("Adding new objects after syncing is not supported on Redis");
    }

    @Override
    public void shutdown() {
        if(this.activityCheck == null) return;
        // todo: implement own shutdown
    }
}
