package cc.olek.lamada.sender;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.DistributedObject;
import cc.olek.lamada.InstructionCommunicator;
import cc.olek.lamada.context.ExecutionContext;
import cc.olek.lamada.util.Exceptions;

import java.util.concurrent.CompletableFuture;

public class LoopbackSender<Target> implements InstructionCommunicator<Target> {
    @Override
    public CompletableFuture<byte[]> send(DistributedObject<?, ?, Target> object, Target target, int opNumber, byte[] data, long waitForReply) {
        DistributedExecutor<Target> executor = object.getExecutor();
        return CompletableFuture.supplyAsync(() -> {
            ExecutionContext context = executor.receiveContext(data, executor.getOwnTarget());
            if(context.deserializationError() != null) {
                throw Exceptions.wrap(context.deserializationError());
            }
            return executor.serializeResponse(executor.executeContext(context));
        }, executor.getAsync());
    }
}
