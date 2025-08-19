package cc.olek.lamada;

import java.util.concurrent.CompletableFuture;

public interface InstructionCommunicator<Target> {
    CompletableFuture<byte[]> send(DistributedObject<?, ?, Target> executor, Target to, int opNumber, byte[] data, boolean waitForReply);
}
