package cc.olek.lamada.func;

import java.io.Serial;

@FunctionalInterface
public interface ExecutionRunnable extends ExecutableInterface {
    @Serial
    long serialVersionUID = 1L;

    void run();
}
