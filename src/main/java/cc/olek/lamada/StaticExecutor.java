package cc.olek.lamada;

import cc.olek.lamada.context.InvocationResult;
import cc.olek.lamada.exception.TargetExecutionException;
import cc.olek.lamada.func.*;

import java.util.concurrent.CompletableFuture;

public class StaticExecutor<Target> extends DistributedObject<Object, Object, Target> {
    public StaticExecutor(DistributedExecutor<Target> distributedExecutor) {
        super(distributedExecutor, null, null, false);
    }

    public <T> CompletableFuture<T> runMethod(Target target, ExecutionSupplier<T> toRun) {
        return runMethod(target, TIMEOUT_MODE_DEFAULT, toRun);
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> runMethod(Target target, long timeout, ExecutionSupplier<T> toRun) {
        if(target == null || target.equals(executor.ownTarget)) {
            return CompletableFuture.supplyAsync(toRun::supply, executor.executor);
        }
        return doSerialize(target, null, toRun, ExecutableInterface.SUPPLIER).thenCompose(
            serialized -> doSend(target, serialized.context().opNumber(), serialized.bytes(), timeout)
        ).thenApply(bytes -> {
            InvocationResult result = executor.receiveResult(target, bytes);
            if(result.errorMessage() != null) {
                throw new TargetExecutionException(result.errorMessage());
            }
            return (T) result.result();
        });
    }

    public <T> CompletableFuture<T> runAsyncMethod(Target target, ExecutionSupplier<CompletableFuture<T>> toRun) {
        return runAsyncMethod(target, TIMEOUT_MODE_DEFAULT, toRun);
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> runAsyncMethod(Target target, long timeout, ExecutionSupplier<CompletableFuture<T>> toRun) {
        if(target == null || target.equals(executor.ownTarget)) {
            return toRun.supply();
        }
        return doSerialize(target, null, toRun, ExecutableInterface.ASYNC_SUPPLIER).thenCompose(
            serialized -> doSend(target, serialized.context().opNumber(), serialized.bytes(), timeout)
        ).thenApply(bytes -> {
            InvocationResult result = executor.receiveResult(target, bytes);
            if(result.errorMessage() != null) {
                throw new TargetExecutionException(result.errorMessage());
            }
            return (T) result.result();
        });
    }

    public CompletableFuture<Void> run(Target target, ExecutionRunnable toRun) {
        return run(target, TIMEOUT_MODE_DEFAULT, toRun);
    }
    public CompletableFuture<Void> run(Target target, long timeout, ExecutionRunnable toRun) {
        if(target == null || target.equals(executor.ownTarget)) {
            return CompletableFuture.runAsync(toRun::run, executor.executor);
        }
        return doSerialize(target, null, toRun, ExecutableInterface.RUNNABLE).thenCompose(
            serialized -> doSend(target, serialized.context().opNumber(), serialized.bytes(), timeout)
        ).thenApply(bytes -> {
            InvocationResult result = executor.receiveResult(target, bytes);
            if(result.errorMessage() != null) {
                throw new TargetExecutionException(result.errorMessage());
            }
            return null;
        });
    }

    public CompletableFuture<Void> runAndForget(Target target, ExecutionRunnable toRun) {
        if(target == null || target.equals(executor.ownTarget)) {
            return CompletableFuture.runAsync(toRun::run, executor.executor);
        }
        return doSerialize(target, null, toRun, ExecutableInterface.RUNNABLE).thenCompose(
            serialized -> doSend(target, serialized.context().opNumber(), serialized.bytes(), TIMEOUT_MODE_FORGET)
        ).thenApply(__ -> null);
    }

    @Override
    public final <T> CompletableFuture<T> runMethod(Target target, Object o, long timeout, ExecutionFunction<Object, T> toRun) {
        throw new UnsupportedOperationException();
    }

    @Override
    public final CompletableFuture<Void> run(Target target, Object o, long timeout, ExecutionConsumer<Object> toRun) {
        throw new UnsupportedOperationException();
    }

    @Override
    public final CompletableFuture<Object> runSingleMethod(Target target, Object o, String methodDesc, Object[] params) {
        throw new UnsupportedOperationException();
    }

    @Override
    public final CompletableFuture<Void> runAndForget(Target target, Object o, ExecutionConsumer<Object> toRun) {
        throw new UnsupportedOperationException();
    }

    @Override
    public final <T> CompletableFuture<T> runAsyncMethod(Target target, Object o, long timeout, ExecutionFunction<Object, CompletableFuture<T>> toRun) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setNumber(short number) {
        // ignore
    }

    @Override
    public short getNumber() {
        return 0;
    }

    @Override
    protected final Object extract(Object o) {
        return null;
    }

    @Override
    protected final Object fetch(Object o) {
        return null;
    }
}
