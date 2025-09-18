package cc.olek.lamada.serialization;

import cc.olek.lamada.DistributedExecutor;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.SerializerFactory;

public class ProjectSerializerFactory implements SerializerFactory<Serializer<?>> {
    private final DistributedExecutor<?> obj;
    private final SerializerFactory<?> std;

    public ProjectSerializerFactory(DistributedExecutor<?> obj, SerializerFactory<?> std) {
        this.obj = obj;
        this.std = std;
    }

    @Override
    public Serializer<?> newSerializer(Kryo kryo, Class type) {
        Registration registration = obj.searchSuperclassSerializer(kryo, type);
        if(registration != null) return registration.getSerializer();
        return this.std.newSerializer(kryo, type);
    }

    @Override
    public boolean isSupported(Class type) {
        return true;
    }
}
