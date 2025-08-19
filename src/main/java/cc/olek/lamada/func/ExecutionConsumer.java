package cc.olek.lamada.func;

import java.io.Serial;

public interface ExecutionConsumer<T> extends ExecutableInterface {
    @Serial
    long serialVersionUID = 1L;

    void apply(T t);
    @SuppressWarnings("unchecked")
    default void applyObj(Object val) {
        apply((T) val);
    }
}
