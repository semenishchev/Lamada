package cc.olek.lamada.serialization;

import cc.olek.lamada.context.ExecutionContext;

public record SerializationResult(ExecutionContext context, byte[] bytes) {
}
