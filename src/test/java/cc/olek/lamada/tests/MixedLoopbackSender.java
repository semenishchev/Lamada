package cc.olek.lamada.tests;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.DistributedObject;
import cc.olek.lamada.InstructionCommunicator;
import cc.olek.lamada.context.ExecutionContext;
import cc.olek.lamada.util.Exceptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class MixedLoopbackSender<Target> implements InstructionCommunicator<Target> {
    private final Map<Target, DistributedExecutor<Target>> executors = new HashMap<>();
    public void register(DistributedExecutor<Target> executor) {
        this.executors.put(executor.getOwnTarget(), executor);
    }
    @Override
    public CompletableFuture<byte[]> send(DistributedObject<?, ?, Target> object, Target to, int opNumber, byte[] data, boolean waitForReply) {
        DistributedExecutor<Target> runOn = executors.get(to);
        if(runOn == null) {
            return CompletableFuture.failedFuture(new NullPointerException("Could not find executor on " + to));
        }
        return CompletableFuture.supplyAsync(() -> {
            ExecutionContext context = runOn.receiveContext(data, object.getExecutor().getOwnTarget());
            if(context.deserializationError() != null) {
                throw Exceptions.wrap(context.deserializationError());
            }
            return runOn.serializeResponse(runOn.executeContext(context));
        });
    }
}
