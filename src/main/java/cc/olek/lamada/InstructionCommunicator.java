package cc.olek.lamada;

import java.util.concurrent.CompletableFuture;

public interface InstructionCommunicator<Target> {
    CompletableFuture<byte[]> send(DistributedObject<?, ?, Target> object, Target to, int opNumber, byte[] data, long timeout);
}
