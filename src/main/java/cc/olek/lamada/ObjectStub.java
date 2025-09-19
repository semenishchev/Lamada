package cc.olek.lamada;

import cc.olek.lamada.serialization.SuperclassSerializer;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/**
 * Stubs an interface which is a unique distributed object. When code refers to an object which lives
 * on the sending JVM, receiving JVM will generate this class, where each method will transfer each invoke
 * to the sender
 */
public abstract class ObjectStub {
    protected Object key;
    protected Object target;
    protected DistributedObject<Object, ?, Object> object;

    public void setup(DistributedObject<Object, ?, Object> object, Object key) {
        if(this.target != null) return;
        this.object = object;
        this.key = key;
    }

    public void setTarget(Object target) {
        this.target = target;
    }

    public Object sendSingleMethod(Object[] params, String methodNameDescriptor) {
        return object.runSingleMethod(this.target, this.key, methodNameDescriptor, params).join();
    }

    /**
     * Converts a unique (ObjectStub) object into a local one by fetching by the stub's key locally
     * @param unique Unique object
     * @return Local object
     * @param <T> Type of the object
     */
    @SuppressWarnings("unchecked")
    public static <T> T getLocal(T unique) {
        if(!(unique instanceof ObjectStub stub)) {
            throw new IllegalArgumentException("Object is not unique");
        }

        return (T) stub.object.fetch(stub.key);
    }

    public Object getKey() {
        return key;
    }

    public Object getTarget() {
        return target;
    }

    public DistributedObject<Object,?, Object> getObject() {
        return object;
    }

    public static class StubSerializer extends Serializer<ObjectStub> implements SuperclassSerializer {

        private final DistributedExecutor<?> executor;

        public StubSerializer(DistributedExecutor<?> executor) {
            this.executor = executor;
        }

        @Override
        public void write(Kryo kryo, Output output, ObjectStub object) {
            output.writeShort(object.object.getNumber());
            kryo.writeObject(output, object.target);
            kryo.writeObject(output, object.key);
        }

        @Override
        public ObjectStub read(Kryo kryo, Input input, Class<? extends ObjectStub> type) {
            short objectNum = input.readShort();
            DistributedObject<?, ?, ?> obj = executor.getSerializerByNumber(objectNum);
            if(obj == null) {
                throw new IllegalArgumentException("Failed to deserialize an object stub: unknown objectnum: " + objectNum);
            }

            Object target = kryo.readObject(input, executor.getTypeOfTarget());
            Object key = kryo.readObject(input, obj.getSerializeFrom());
            return obj.getStubFactory().getStub(key, target);
        }
    }
}
