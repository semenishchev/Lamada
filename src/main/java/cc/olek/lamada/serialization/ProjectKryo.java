package cc.olek.lamada.serialization;

import cc.olek.lamada.DistributedExecutor;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;

public class ProjectKryo extends Kryo {
    private final DistributedExecutor<?> executor;

    public ProjectKryo(DistributedExecutor<?> executor) {
        this.executor = executor;
    }

    @Override
    public Registration getRegistration(Class type) {
        Registration superclassSerializer = executor.searchSuperclassSerializer(this, type);
        if(superclassSerializer != null) return superclassSerializer;
        return super.getRegistration(type);
    }
}
