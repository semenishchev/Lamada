package cc.olek.lamada.func;

import java.io.Serial;

@FunctionalInterface
public interface ExecutionSupplier<T> extends ExecutableInterface {
    @Serial
    long serialVersionUID = 1L;

    T supply();
}
