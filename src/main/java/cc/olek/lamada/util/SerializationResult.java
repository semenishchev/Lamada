package cc.olek.lamada.util;

import cc.olek.lamada.context.ExecutionContext;

public record SerializationResult(ExecutionContext context, byte[] bytes) {
}
