package cc.olek.lamada.serialization;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.ObjectStub;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.io.Output;

public class ProjectKryo extends Kryo {
    private final DistributedExecutor<?> executor;

    public ProjectKryo(DistributedExecutor<?> executor) {
        this.executor = executor;
    }

    @Override
    public Registration writeClass(Output output, Class type) {
        if(type == null) return super.writeClass(output, null);
        if(ObjectStub.class.isAssignableFrom(type)) {
            return super.writeClass(output, ObjectStub.class); // stubs are objects, they don't need to get wrapped though
        }
        Registration superclassSerializer = executor.searchSuperclassSerializer(this, type);
        if(superclassSerializer != null) return super.writeClass(output, superclassSerializer.getType());
        return super.writeClass(output, type);
    }
}
