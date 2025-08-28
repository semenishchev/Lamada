package cc.olek.lamada.context;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.DistributedObject;
import cc.olek.lamada.func.ExecutableInterface;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Represents execution context which is being transferred
 */
public final class ExecutionContext {
    private final DistributedObject<?, ?, ?> objectRequesting;
    private final Object target;
    private final Object key;
    private final byte mode;
    private final ExecutableInterface lambda;
    private final int opNumber;
    private Throwable deserializationError;

    /**
     * @param objectRequesting Object which involves the transfer
     * @param target           Target receiver when we send or sender when we receive
     * @param key              key by which primary object should be identified
     * @param mode             method mode (runnable, consumer, supplier or function)
     * @param lambda           lambda object to execute
     * @param opNumber         operation number to later send the response
     */
    public ExecutionContext(DistributedObject<?, ?, ?> objectRequesting, Object target, Object key, byte mode, ExecutableInterface lambda,
        int opNumber) {
        this.objectRequesting = objectRequesting;
        this.target = target;
        this.key = key;
        this.mode = mode;
        this.lambda = lambda;
        this.opNumber = opNumber;
    }

    public void setDeserializationError(Throwable deserializationError) {
        this.deserializationError = deserializationError;
    }

    public DistributedObject<?, ?, ?> objectRequesting() {
        return objectRequesting;
    }

    public Object target() {
        return target;
    }

    public Object key() {
        return key;
    }

    public boolean isVoid() {
        return mode == ExecutableInterface.RUNNABLE || mode == ExecutableInterface.CONSUMER;
    }

    public boolean isStatic() {
        return mode == ExecutableInterface.RUNNABLE || mode == ExecutableInterface.SUPPLIER;
    }

    public ExecutableInterface lambda() {
        return lambda;
    }

    public int opNumber() {
        return opNumber;
    }

    @Nullable
    public Throwable deserializationError() {
        return this.deserializationError;
    }

    @Override
    public boolean equals(Object obj) {
        if(obj == this) return true;
        if(obj == null || obj.getClass() != this.getClass()) return false;
        var that = (ExecutionContext) obj;
        return Objects.equals(this.objectRequesting, that.objectRequesting) &&
            Objects.equals(this.target, that.target) &&
            Objects.equals(this.key, that.key) &&
            this.mode == that.mode &&
            Objects.equals(this.lambda, that.lambda) &&
            this.opNumber == that.opNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(objectRequesting, target, key, mode, lambda, opNumber);
    }

    @Override
    public String toString() {
        return "ExecutionContext[" +
            "objectRequesting=" + objectRequesting + ", " +
            "target=" + target + ", " +
            "key=" + key + ", " +
            "mode=" + mode + ", " +
            "lambda=" + lambda + ", " +
            "opNumber=" + opNumber + ']';
    }

    public byte mode() {
        return this.mode;
    }


    public static class ContextSerializer<Target> extends Serializer<ExecutionContext> {
        private final DistributedExecutor<Target> executor;
        private final Target sender;

        public ContextSerializer(DistributedExecutor<Target> executor) {
            this.executor = executor;
            this.sender = executor.getOwnTarget();
        }

        public ContextSerializer(DistributedExecutor<Target> executor, Target sender) {
            this.executor = executor;
            this.sender = sender;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void write(Kryo kryo, Output output, ExecutionContext object) {
            kryo.getContext().put("sender", executor.getOwnTarget());
            kryo.getContext().put("receiver", object.target);
            output.writeVarInt(object.opNumber, true);
            kryo.writeObject(output, object.objectRequesting, executor.getOwnObjectSerializer());
            output.writeByte(object.mode);
            if(!object.isStatic()) {
                kryo.writeObject(output, object.key);
            }
            if(object.target != null) {
                kryo.writeObject(output, object.lambda, executor.getLambdaSerializer());
            } else {
                kryo.writeClassAndObject(output, object.lambda);
            }
        }

        @Override
        public ExecutionContext read(Kryo kryo, Input input, Class<? extends ExecutionContext> type) {
            kryo.getContext().put("sender", sender);
            kryo.getContext().put("receiver", executor.getOwnTarget());
            int opNumber = input.readVarInt(true);
            DistributedObject<?, ?, ?> object;
            try {
                object = kryo.readObject(input, DistributedObject.class, executor.getOwnObjectSerializer());
            } catch(Throwable t) {
                ExecutionContext result = new ExecutionContext(null, sender, null, ExecutableInterface.MODE_ERR, null, opNumber);
                result.setDeserializationError(t);
                return result;
            }
            byte mode = input.readByte();
            Object key = null;
            if(!ExecutableInterface.isStatic(mode)) {
                try {
                    key = kryo.readObject(input, object.getSerializeFrom());
                } catch(Throwable t) {
                    ExecutionContext result = new ExecutionContext(null, sender, null, ExecutableInterface.MODE_ERR, null, opNumber);
                    result.setDeserializationError(new RuntimeException("Failed to read key for " + object.getClass().getName() + ", number: " + opNumber + ", mode: " + mode, t));
                    return result;
                }
            }
            try {
                ExecutableInterface lambda = kryo.readObject(input, ExecutableInterface.class, executor.getLambdaSerializer());
                if(lambda == null) {
                    throw new NullPointerException("Failed to reconstruct lambda");
                }
                return new ExecutionContext(object, sender, key, mode, lambda, opNumber);
            } catch(Throwable t) {
                ExecutionContext result = new ExecutionContext(null, sender, null, ExecutableInterface.MODE_ERR, null, opNumber);
                result.setDeserializationError(new RuntimeException("Failed to read or reconstruct lambda for " + object.getClass().getName() + ", number: " + opNumber, t));
                return result;
            }
        }
    }
}
