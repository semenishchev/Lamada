package cc.olek.lamada.func;

import java.io.Serial;

@FunctionalInterface
public interface ExecutionFunction<K, V> extends ExecutableInterface {
    @Serial
    long serialVersionUID = 1L;

    V apply(K k);

    @SuppressWarnings("unchecked")
    default Object applyObj(Object k) {
        return apply((K)k);
    }
}
