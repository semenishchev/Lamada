package cc.olek.lamada.serialization;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.DistributedObject;
import cc.olek.lamada.ObjectStub;
import cc.olek.lamada.func.ExecutableInterface;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import com.esotericsoftware.kryo.serializers.RecordSerializer;

public class ProjectDefaultSerializer extends Serializer {

    private final FieldSerializer<Object> defaultSerializer;
    private final RecordSerializer<?> recordSerializer;
    private final DistributedExecutor<?> executor;

    public ProjectDefaultSerializer(DistributedExecutor<?> executor, Kryo kryo, Class<?> type) {
        this.executor = executor;
        this.defaultSerializer = new FieldSerializer<>(kryo, type);
        this.recordSerializer = type.isRecord() ? new RecordSerializer<>(type) : null;
    }

    @Override
    public void write(Kryo kryo, Output output, Object object) {
        Class<?> capturedClass = object.getClass();
        DistributedObject<?, ?, ?> serializer = executor.searchSerializer(capturedClass);
        if(serializer != null) {
            serializer.write(kryo, output, forceCast(object));
            return;
        }
        Serializer<?> maybe = kryo.getSerializer(capturedClass);
        if(maybe != null) {
            maybe.write(kryo, output, forceCast(object));
            return;
        }
        if(capturedClass.isRecord()) {
            recordSerializer.write(kryo, output, forceCast(object));
            return;
        }
        if(object instanceof ExecutableInterface || capturedClass.isSynthetic()) {
            throw new UnsupportedOperationException("Passing lambdas in complex objects is not *yet* supported"); // todo: make this work
        }
        defaultSerializer.write(kryo, output, object);
    }

    @SuppressWarnings("unchecked")
    private static <T> T forceCast(Object obj) {
        return (T) obj;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object read(Kryo kryo, Input input, Class type) {
        DistributedObject<?, ?, ?> serializer = executor.searchSerializer(type);
        if(DistributedObject.class.isAssignableFrom(type) || DistributedExecutor.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Cannot read " + type);
        }
        if(serializer != null) {
            Object read = serializer.read(kryo, input, type);
            if(read instanceof ObjectStub stub) {
                stub.setTarget(kryo.getContext().get("sender"));
            }
            return read;
        }
        Serializer<?> maybe = kryo.getSerializer(type);
        if(maybe != null) {
            return maybe.read(kryo, input, type);
        }
        if(type.isRecord()) {
            return recordSerializer.read(kryo, input, type);
        }
        return defaultSerializer.read(kryo, input, type);
    }
}
