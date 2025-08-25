package cc.olek.lamada;

import cc.olek.lamada.asm.MethodImpl;
import cc.olek.lamada.context.ExecutionContext;
import cc.olek.lamada.context.InvocationResult;
import cc.olek.lamada.func.*;
import cc.olek.lamada.util.ReferenceResolver;
import cc.olek.lamada.util.SlfKryoLogger;
import cc.olek.lamada.util.WeakSet;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.DefaultSerializers;
import com.esotericsoftware.minlog.Log;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

public class DistributedExecutor<Target> {
    protected final Logger logger;
    protected final WeakSet<Kryo> activeKryos = new WeakSet<>();
    protected final String purpose;
    protected final ThreadLocal<Kryo> kryo;

    private final Class<Target> targetType;
    private final Int2ObjectMap<ExecutionContext> contexts = new Int2ObjectOpenHashMap<>();
    private final Map<Class<?>, Serializer<?>> userDefinedSerializers = new HashMap<>();
    private final Set<Class<?>> predefines = new HashSet<>();
    private StaticExecutor<Target> staticExecutor;
    private ExecutionContext.ContextSerializer<Target> contextSerializer;
    private InvocationResult.ResultSerializer responseSerializer;
    private ExecutorSerializer ownSerializer;

    final Map<String, DistributedObject<?, ?, Target>> targetClassToObject = new HashMap<>();
    final AtomicInteger opNumber = new AtomicInteger();
    final Target ownTarget;
    RemoteTargetManager<Target> targetManager;
    InstructionCommunicator<Target> sender;
    Executor executor = ForkJoinPool.commonPool();

    private volatile boolean synced = false;

    @SuppressWarnings("unchecked")
    public DistributedExecutor(Target ownTarget, String purpose) {
        this.logger = LoggerFactory.getLogger("Executor-" + purpose);
        this.purpose = purpose;
        this.targetType = (Class<Target>) ownTarget.getClass();
        this.ownTarget = ownTarget;
        this.staticExecutor = new StaticExecutor<>(this);
        this.kryo = ThreadLocal.withInitial(() -> {
            Kryo kryo = new Kryo();
            setupKryo(kryo);
            for(Map.Entry<Class<?>, Serializer<?>> serializerEntry : userDefinedSerializers.entrySet()) {
                kryo.register(serializerEntry.getKey(), serializerEntry.getValue());
            }
            for(DistributedObject<?, ?, Target> registered : targetClassToObject.values()) {
                kryo.register(registered.getObjectType(), registered);
                kryo.register(registered.getClass(), ownSerializer);
            }
            for(Class<?> predefine : predefines) {
                kryo.register(predefine);
            }
            activeKryos.add(kryo);
            return kryo;
        });
    }

    public DistributedExecutor(Target ownTarget) {
        this(ownTarget, ownTarget.toString());
    }

    public CompletableFuture<Void> run(Target target, ExecutionRunnable runnable) {
        return staticExecutor.run(target, runnable);
    }

    public <T> CompletableFuture<T> runMethod(Target target, ExecutionSupplier<T> runnable) {
        return staticExecutor.runMethod(target, runnable);
    }

    public CompletableFuture<Void> runAndForget(Target target, ExecutionRunnable runnable) {
        return staticExecutor.runAndForget(target, runnable);
    }

    protected void setupKryo(Kryo kryo) {
        Log.setLogger(new SlfKryoLogger());

        kryo.setRegistrationRequired(false);
        kryo.addDefaultSerializer(UUID.class, new DefaultSerializers.UUIDSerializer());
        kryo.addDefaultSerializer(MethodImpl.class, new MethodImpl.MethodSerializer());
        kryo.register(DistributedExecutor.class, new OwnSerializer(this));
        kryo.register(
            ExecutionContext.class,
            contextSerializer = new ExecutionContext.ContextSerializer<>(this)
        );
        kryo.addDefaultSerializer(
            InvocationResult.class,
            responseSerializer = new InvocationResult.ResultSerializer(this)
        );
        kryo.register(
            DistributedObject.class,
            ownSerializer = new ExecutorSerializer(this)
        );

//        kryo.setReferences(true);
//        kryo.setReferenceResolver(new ReferenceResolver());
    }

    public <T> void registerSerializer(Class<T> serialize, Serializer<T> serializer) {
        userDefinedSerializers.put(serialize, serializer);
        for(Kryo activeKryo : activeKryos) {
            if(activeKryo == null) continue;
            activeKryo.register(serialize, serializer);
        }
    }

    public void predefineId(Class<?> clazz) {
        if(synced) {
            throw new UnsupportedOperationException("Predefinitions should be called before sync");
        }
        predefines.add(clazz);
        for(Kryo activeKryo : activeKryos) {
            if(activeKryo == null) continue;
            activeKryo.register(clazz);
        }
    }

    protected <K, V> void register(DistributedObject<K, V, Target> object) {
        if(object instanceof StaticExecutor<?>) return;
        String className = object.getObjectType().getName();
        targetClassToObject.put(className, object);
        if(synced) {
            try {
                targetManager.resync();
            } catch(Throwable t) {
                targetClassToObject.remove(className);
                throw t;
            }
        }
        for(Kryo activeKryo : activeKryos) {
            if(activeKryo == null) continue;
            activeKryo.register(object.getClass(), ownSerializer);
            activeKryo.register(object.getObjectType(), object);
        }
        logger.info("Registered {} -> {}", object.getObjectType().getName(), object.getSerializeFrom().getName());
    }

    public byte[] serialize(ExecutionContext context) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Output output = newOutputContext(out);
        kryo.get().writeObject(
            output,
            context,
            contextSerializer
        );
        output.close();
        return out.toByteArray();
    }

    public byte[] serializeResponse(InvocationResult invocation) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Output output = newOutputResponse(out);
        kryo.get().writeObject(output, invocation, responseSerializer); // force responseSerializer
        output.close();
        return out.toByteArray();
    }

    public ExecutionContext receiveContext(byte[] input, Target sender) {
        Input kInput = newInputContext(input);
        return kryo.get().readObject(
            kInput,
            ExecutionContext.class,
            new ExecutionContext.ContextSerializer<>(this, sender)
        );
    }

    public InvocationResult executeContext(ExecutionContext context) {
        ExecutableInterface executable = context.lambda();
        if(executable == null) {
            return InvocationResult.ofError(context, new RuntimeException("Could not find lambda implementation. This shouldn't happen"));
        }

        String failureMessage = "Unexpected error";
        try {
            Object result = switch(context.mode()) {
                case ExecutableInterface.RUNNABLE -> {
                    failureMessage = "Failed to execute lambda runnable in a static context";
                    ((ExecutionRunnable)executable).run();
                    yield null;
                }
                case ExecutableInterface.SUPPLIER -> {
                    failureMessage = "Failed to execute lambda supplier in a static context";
                    yield ((ExecutionSupplier<?>) executable).supply();
                }
                case ExecutableInterface.FUNCTION -> {
                    Object executeOn = context.objectRequesting().get(context.key());
                    failureMessage =  "Failed to execute lambda on %s\nKey: %s\nObject received: %s".formatted(context.objectRequesting().getClass(), context.key(), executeOn);
                    yield ((ExecutionFunction<?, ?>) executable).applyObj(executeOn);
                }
                case ExecutableInterface.CONSUMER -> {
                    Object executeOn = context.objectRequesting().get(context.key());
                    failureMessage =  "Failed to execute lambda on %s\nKey: %s\nObject received: %s".formatted(context.objectRequesting().getClass(), context.key(), executeOn);
                    ((ExecutionConsumer<?>) executable).applyObj(executeOn);
                    yield null;
                }
                default -> throw new IllegalStateException("Unexpected value: " + context.mode());
            };
            return new InvocationResult(context, result, null);
        } catch(Throwable t) {
            return InvocationResult.ofError(context, new RuntimeException(failureMessage, t));
        }
    }

    public InvocationResult receiveResult(byte[] input) {
        Input kInput = newInputResponse(input);
        return kryo.get().readObject(kInput, InvocationResult.class, responseSerializer);
    }

    /**
     * This method exists to give control of how context is created to the user
     */
    public ExecutionContext getContext(Object target, Object key, DistributedObject<?, ?, ?> objectOn, ExecutableInterface consumerObject, byte mode, int opNumber) {
        return new ExecutionContext(objectOn, target, key, mode, consumerObject, opNumber);
    }

    protected Output newOutputResponse(OutputStream writeInto) {
        return new Output(writeInto);
    }

    protected Output newOutputContext(OutputStream writeInto) {
        return new Output(writeInto);
    }

    protected Input newInputContext(byte[] readFrom) {
        return new Input(readFrom);
    }

    protected Input newInputResponse(byte[] readFrom) {
        return new Input(readFrom);
    }

    @SuppressWarnings("unchecked")
    public <V> DistributedObject<?, V, ?> getSerializerByValueClass(Class<V> vClass) {
        return (DistributedObject<?, V, ?>) targetClassToObject.get(vClass.getName());
    }

    public DistributedObject<?, ?, ?> getSerializerByNumber(short number) {
        if(!synced) {
            throw new RuntimeException("Execution happened when not synchronised");
        }
        if(number == 0) {
            return this.staticExecutor;
        }
        return targetManager.getObject(number);
    }

    public DistributedObject<?, ?, ?> getSerializerByValueClass(String classname) {
        return targetClassToObject.get(classname);
    }

    public void registerExecution(int num, ExecutionContext context) {
        synchronized(this) {
            contexts.put(num, context);
        }
    }

    public ExecutionContext popContext(int num) {
        synchronized(this) {
            return contexts.get(num);
        }
    }

    public Class<Target> getTypeOfTarget() {
        return targetType;
    }

    public RemoteTargetManager<Target> getTargetManager() {
        return targetManager;
    }

    public InstructionCommunicator<Target> getSender() {
        return sender;
    }

    public void sync() {
        if(synced) {
            throw new IllegalStateException("You may sync only once. This method locks in the predefined numbers between targets");
        }
        targetManager.sync();
        synced = true;
    }

    public StaticExecutor<Target> getStaticObject() {
        return staticExecutor;
    }

    public Target getOwnTarget() {
        return ownTarget;
    }

    public Executor getAsync() {
        return executor;
    }

    public ExecutorSerializer getOwnSerializer() {
        return ownSerializer;
    }

    public void setSender(InstructionCommunicator<Target> sender) {
        this.sender = sender;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public void setStaticExecutor(StaticExecutor<Target> staticExecutor) {
        this.staticExecutor = staticExecutor;
    }

    public void setTargetManager(RemoteTargetManager<Target> targetManager) {
        this.targetManager = targetManager;
    }

    public void shutdown() {
        this.targetManager.shutdown();
    }

    public static class ExecutorSerializer extends Serializer<DistributedObject<?, ?, ?>> {
        private final DistributedExecutor<?> executor;

        public ExecutorSerializer(DistributedExecutor<?> executor) {
            this.executor = executor;
        }
        @Override
        public void write(Kryo kryo, Output output, DistributedObject<?, ?, ?> object) {
            output.writeShort(object.getNumber());
        }

        @Override
        public DistributedObject<?, ?, ?> read(Kryo kryo, Input input, Class<? extends DistributedObject<?, ?, ?>> type) {
            short number = input.readShort();
            DistributedObject<?, ?, ?> serializerByNumber = executor.getSerializerByNumber(number);
            if(serializerByNumber == null) {
                throw new RuntimeException("Failed to find object with number " + number);
            }
            return serializerByNumber;
        }
    }

    private static class OwnSerializer extends Serializer<DistributedExecutor<?>> {
        private final DistributedExecutor<?> obj;

        public OwnSerializer(DistributedExecutor<?> obj) {
            this.obj = obj;
        }

        @Override
        public void write(Kryo kryo, Output output, DistributedExecutor<?> object) {
            output.writeBoolean(object != null);
        }

        @Override
        public DistributedExecutor<?> read(Kryo kryo, Input input, Class<? extends DistributedExecutor<?>> type) {
            return input.readBoolean() ? obj : null;
        }
    }

    public String getPurpose() {
        return purpose;
    }

    public Logger getLogger() {
        return logger;
    }
}
