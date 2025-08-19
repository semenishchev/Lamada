package cc.olek.lamada.defaults;


import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.DistributedObject;

import java.util.function.Function;

public class FunctionalDistributedObject<Key, Value, Target> extends DistributedObject<Key, Value, Target> {
    private Function<Value, Key> extractor;
    private Function<Key, Value> resolver;

    public FunctionalDistributedObject(DistributedExecutor<Target> executor, Class<Value> objectType, Class<Key> serializeFrom, boolean unique) {
        super(executor, objectType, serializeFrom, unique);
    }

    public void setSerialization(Function<Value, Key> extractor, Function<Key, Value> resolver) {
        this.extractor = extractor;
        this.resolver = resolver;
    }

    @Override
    public Key extract(Value value) {
        return extractor.apply(value);
    }

    @Override
    protected Value fetch(Key key) {
        return resolver.apply(key);
    }
}
