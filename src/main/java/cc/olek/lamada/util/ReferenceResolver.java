package cc.olek.lamada.util;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.DistributedObject;
import cc.olek.lamada.context.ExecutionContext;
import cc.olek.lamada.func.ExecutableInterface;
import com.esotericsoftware.kryo.util.MapReferenceResolver;

import java.util.UUID;

public class ReferenceResolver extends MapReferenceResolver {
    @Override
    public boolean useReferences(Class type) {
        if(type == UUID.class) return false;
        if(DistributedObject.class.isAssignableFrom(type)) return false;
        if(ExecutableInterface.class.isAssignableFrom(type)) return false;
        if(DistributedExecutor.class.isAssignableFrom(type)) return false;
        if(type == ExecutionContext.class) return false;
        if(type.isSynthetic()) return false;
        return super.useReferences(type);
    }
}
