package cc.olek.lamada;

import cc.olek.lamada.util.Deencapsulation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

public abstract class ObjectStubFactory<Key, Value> {
    private DistributedObject<Key, Value, ?> executor;
    private final Long2ObjectMap<MethodHandle> idToMethod = new Long2ObjectOpenHashMap<>();
    private final Map<String, MethodHandle> collidingMethods = new HashMap<>();
    private final Map<String, Object> descToId = new HashMap<>();

    public void setExecutor(DistributedObject<Key, Value, ?> executor) {
        if(this.executor != null) return;
        this.executor = executor;
        for(Method method : executor.getObjectType().getDeclaredMethods()) {
            String key = getKey(method);
            long hash = hash(key);
            MethodHandle handle;
            try {
                handle = Deencapsulation.LOOKUP.unreflect(method);
            } catch(IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            if(idToMethod.containsKey(hash)) {
                collidingMethods.put(key, handle);
                descToId.put(key, key);
                continue;
            }
            descToId.put(key, hash);
            idToMethod.put(hash, handle);
        }
    }

    public MethodHandle getHandle(Object id) {
        if(id instanceof Long l) return idToMethod.get(l.longValue());
        return collidingMethods.get(id.toString());
    }

    public Object getKey(String desc) {
        return descToId.get(desc);
    }

    protected abstract Value createNewStub();

    @SuppressWarnings("unchecked")
    public Value getStub(Key key) {
        Value raw = createNewStub();
        if(!(raw instanceof ObjectStub stub)) {
            throw new IllegalStateException("Stub object doesn't extend ObjectStub");
        }
        stub.setup((DistributedObject<Object, ?, Object>) executor, key);
        return (Value) stub;
    }

    private static long hash(String str) {
        CRC32 crc = new CRC32();
        crc.update(str.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static String getKey(Method method) {
        return method.getName() + Type.getMethodDescriptor(method);
    }
}
