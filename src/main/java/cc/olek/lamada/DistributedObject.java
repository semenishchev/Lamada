package cc.olek.lamada;

import cc.olek.lamada.asm.LambdaReconstructor;
import cc.olek.lamada.context.ExecutionContext;
import cc.olek.lamada.context.InvocationResult;
import cc.olek.lamada.func.ExecutableInterface;
import cc.olek.lamada.func.ExecutionConsumer;
import cc.olek.lamada.func.ExecutionFunction;
import cc.olek.lamada.util.Exceptions;
import cc.olek.lamada.util.SerializationResult;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.ImmutableSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class DistributedObject<Key, Value, Target> extends ImmutableSerializer<Value> {
    protected static boolean SAVE_MESSAGES = System.getProperty("sync.save-msg") != null;
    protected static Logger LOGGER = LoggerFactory.getLogger("DistributedObject");
    private final Class<? extends Value> objectType;
    private final Class<? extends Key> serializeFrom;
    private final ObjectStubFactory<Key, Value> stubFactory;
    private short number;
    final DistributedExecutor<Target> executor;
    private final Map<Class<?>, CompletableFuture<?>> firstSerialization = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public DistributedObject(DistributedExecutor<Target> distributedExecutor, Class<? extends Value> objectType, Class<? extends Key> serializeFrom, boolean unique) {
        this.executor = distributedExecutor;
        this.objectType = objectType;
        this.serializeFrom = serializeFrom;
        distributedExecutor.register(this);
        if(unique) {
            if(!objectType.isInterface()) {
                throw new IllegalStateException("Unique object may be only an interface");
            }
            stubFactory = (ObjectStubFactory<Key, Value>) LambdaReconstructor.getStubGenerator(objectType);
            this.stubFactory.setExecutor(this);
        } else {
            stubFactory = null;
        }
    }

    /**
     * Submits the execution to another target
     * @param target Target
     * @param key key
     * @param toRun what to run
     * @return a future when lambda was completed
     */
    public CompletableFuture<Void> run(Target target, Key key, ExecutionConsumer<Value> toRun) {
        if(target == null || target.equals(executor.ownTarget)) {
            return CompletableFuture.supplyAsync(() -> {
                toRun.apply(fetch(key));
                return null;
            }, executor.executor);
        }
        return doSerialize(target, key, toRun, ExecutableInterface.CONSUMER).thenCompose(
            serialized -> doSend(target, serialized.context().opNumber(), serialized.bytes(), true)
        ).thenApply(bytes -> {
            InvocationResult result = executor.receiveResult(bytes);
            if(result.errorMessage() != null) {
                throw new RuntimeException(result.errorMessage());
            }
            return null; // we are void anyway
        });
    }

    /**
     * Submits the execution to another target
     * @param target Target
     * @param key key
     * @param toRun what to run
     * @return a future when instructions were sent (contrary to methods above, where future completes when we receive response back)
     * You are not required to accept the future, but errors related to sending are going to be reported there
     */
    public CompletableFuture<Void> runAndForget(Target target, Key key, ExecutionConsumer<Value> toRun) {
        if(target == null || target.equals(executor.ownTarget)) {
            executor.executor.execute(() -> toRun.apply(fetch(key)));
            return CompletableFuture.completedFuture(null);
        }
        return doSerialize(target, key, toRun, ExecutableInterface.CONSUMER)
            .thenCompose(serialized ->
                doSend(target, serialized.context().opNumber(), serialized.bytes(), false)
            ) // implementation is required to return right after sending
            .thenApply(d -> null);
    }

    /**
     * Sends a method to execute
     * @param target Target
     * @param key key
     * @param toRun what to run
     * @return a future when function was executed, and we received results from the target
     * @param <T> Data type returned from the function
     */
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> runMethod(Target target, Key key, ExecutionFunction<Value, T> toRun) {
        if(target == null || target.equals(executor.ownTarget)) {
            return CompletableFuture.supplyAsync(() -> toRun.apply(fetch(key)), executor.executor);
        }
        return doSerialize(target, key, toRun, ExecutableInterface.FUNCTION).thenCompose(
            serialized -> doSend(target, serialized.context().opNumber(), serialized.bytes(), true)
        ).thenApply(bytes -> {
            InvocationResult result = executor.receiveResult(bytes);
            if(result.errorMessage() != null) {
                throw new RuntimeException(result.errorMessage());
            }
            return (T) result.result();
        });
    }

    public CompletableFuture<Object> runSingleMethod(Target target, Key key, String methodDesc, Object[] params) {
        Object id = this.stubFactory.getKey(methodDesc);
        DistributedObject<Key, Value, Target> us = this;
        return runMethod(target, key, val -> {
            ObjectStubFactory<Key, Value> stubFactory = us.getStubFactory();
            if(params == null) {
                try {
                    return stubFactory.getHandle(id).invoke(val);
                } catch(Throwable e) {
                    throw Exceptions.wrap(e);
                }
            }
            int length = params.length;
            Object[] toRun = new Object[length + 1];
            toRun[0] = val;
            System.arraycopy(params, 0, toRun, 1, length);
            try {
                return stubFactory.getHandle(id).invoke(toRun);
            } catch(Throwable e) {
                throw Exceptions.wrap(e);
            }
        });
    }

    /**
     * Sends bytes instructions to a target
     * @param target Target to send
     * @param bytes bytes instruction
     * @param waitForReply if we should wait for reply
     * @return Response bytes if waitForReply is true. If it's false, bytes will always be null,
     * and implementations are required to complete the returning future right after sending
     * instead of when reply was received
     */
    protected CompletableFuture<byte[]> doSend(Target target, int opNumber, byte[] bytes, boolean waitForReply) {
        if(LambdaReconstructor.DEBUG) {
            LOGGER.info("Message #{} to {} is {} bytes long", opNumber,target, bytes.length);
            if(SAVE_MESSAGES) {
                try {
                    Files.write(Path.of("out", target + "-" + opNumber + ".bin"), bytes);
                } catch(IOException e) {
                    LOGGER.error("Failed to save message {}-{}", target, opNumber, e);
                }
            }
        }
        return this.executor.sender.send(this, target, opNumber, bytes, waitForReply);
    }

    /**
     * Serializes a lambda to prepare data to be transferred over the network
     */
    protected CompletableFuture<SerializationResult> doSerialize(Target target, Key key, ExecutableInterface toRun, byte mode) {
        CompletableFuture<?> serializedFirst = firstSerialization.get(toRun.getClass());
        CompletableFuture<SerializationResult> serialize = new CompletableFuture<>();
        if(serializedFirst == null) {
            firstSerialization.put(toRun.getClass(), serialize);
        }
        executor.executor.execute(() -> {
            if(!executor.targetManager.isTargetAvailable(target)) {
                serialize.completeExceptionally(new RuntimeException("Target " + target.toString() + " is not available"));
                return;
            }
            if(serializedFirst != null && !serializedFirst.isDone()) {
                serializedFirst.join();
            }
            int opNumber = executor.opNumber.getAndIncrement();
            if(opNumber < 0) {
                opNumber = 1;
                executor.opNumber.set(2);
            }
            ExecutionContext context = executor.getContext(target, key, this, toRun, mode, opNumber);
            executor.registerExecution(opNumber, context);
            serialize.complete(new SerializationResult(context, executor.serialize(context)));
        });
        return serialize;
    }

    protected abstract Key extract(Value value);
    protected abstract Value fetch(Key key);

    @SuppressWarnings("unchecked")
    public final Value get(Object key) {
        return this.fetch((Key) key);
    }
    public Class<? extends Value> getObjectType() {
        return objectType;
    }

    public Class<? extends Key> getSerializeFrom() {
        return serializeFrom;
    }

    @Override
    public void write(Kryo kryo, Output output, Value object) {
        if(object == null) {
            output.writeBoolean(false);
            return;
        }

        Key key = extract(object);
        if(key == null) {
            throw new RuntimeException("Serialized object key might not be null. If you wish not to pass an object, write null for object, not for the key");
        }
        output.writeBoolean(true);
        kryo.writeObject(output, key);
    }

    @Override
    public Value read(Kryo kryo, Input input, Class<? extends Value> type) {
        if(!input.readBoolean()) {
            return null;
        }
        Key key = kryo.readObject(input, this.serializeFrom);
        if(stubFactory != null) {
            return stubFactory.getStub(key);
        }
        return get(key);
    }

    public DistributedExecutor<Target> getExecutor() {
        return executor;
    }

    public void setNumber(short number) {
        this.number = number;
    }

    public short getNumber() {
        return this.number;
    }

    public boolean isUnique() {
        return this.stubFactory != null;
    }

    public ObjectStubFactory<Key, Value> getStubFactory() {
        return stubFactory;
    }
}

