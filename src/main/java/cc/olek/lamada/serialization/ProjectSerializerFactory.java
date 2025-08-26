package cc.olek.lamada.serialization;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.ObjectStub;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.SerializerFactory;

public class ProjectSerializerFactory implements SerializerFactory<ProjectDefaultSerializer> {
    private final DistributedExecutor<?> executor;

    public ProjectSerializerFactory(DistributedExecutor<?> executor) {
        this.executor = executor;
    }

    @Override
    public ProjectDefaultSerializer newSerializer(Kryo kryo, Class type) {
        return new ProjectDefaultSerializer(executor, kryo, type);
    }

    @Override
    public boolean isSupported(Class type) {
        return !ObjectStub.class.isAssignableFrom(type);
    }
}
