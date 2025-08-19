package cc.olek.lamada.context;

import cc.olek.lamada.DistributedExecutor;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

public final class InvocationResult {
    private final ExecutionContext of;
    private final Object result;
    private final String errorMessage;
    private int opNumber;

    public InvocationResult(ExecutionContext of, Object result, String errorMessage) {
        this.of = of;
        this.result = result;
        this.errorMessage = errorMessage;
        if(of != null) {
            this.opNumber = of.opNumber();
        }
    }

    public static InvocationResult ofError(int opNumber, Throwable t) {
        StringWriter stacktrace = new StringWriter();
        PrintWriter writer = new PrintWriter(stacktrace);
        t.printStackTrace(writer);
        InvocationResult invocationResult = new InvocationResult(null, null, t.getClass().getName() + "\nStack trace: " + stacktrace);
        invocationResult.opNumber = opNumber;
        return invocationResult;
    }

    public static InvocationResult ofError(int opNumber, String error) {
        InvocationResult invocationResult = new InvocationResult(null, null, error);
        invocationResult.opNumber = opNumber;
        return invocationResult;
    }


    public static InvocationResult ofError(ExecutionContext context, Throwable t) {
        StringWriter stacktrace = new StringWriter();
        PrintWriter writer = new PrintWriter(stacktrace);
        t.printStackTrace(writer);
        return new InvocationResult(context, null, t.getClass().getName() + "\nStack trace: " + stacktrace);
    }

    public ExecutionContext of() {
        return of;
    }

    public Object result() {
        return result;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public int opNumber() {
        return this.opNumber;
    }

    @Override
    public boolean equals(Object obj) {
        if(obj == this) return true;
        if(obj == null || obj.getClass() != this.getClass()) return false;
        var that = (InvocationResult) obj;
        return Objects.equals(this.of, that.of) &&
            Objects.equals(this.result, that.result) &&
            Objects.equals(this.errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(of, result, errorMessage);
    }

    @Override
    public String toString() {
        return "InvocationResult[" +
            "of=" + of + ", " +
            "result=" + result + ", " +
            "errorMessage=" + errorMessage + ']';
    }


    public static class ResultSerializer extends com.esotericsoftware.kryo.Serializer<InvocationResult> {
        private final DistributedExecutor<?> executor;

        public ResultSerializer(DistributedExecutor<?> executor) {
            this.executor = executor;
        }

        private static final byte STATE_VOID = 0x0;
        private static final byte STATE_RESULT = 0x1;
        private static final byte STATE_ERR = 0x2;

        @Override
        public void write(Kryo kryo, Output output, InvocationResult object) {
            output.writeVarInt(object.opNumber, true);
            if(object.errorMessage != null) {
                output.writeByte(STATE_ERR);
                output.writeString(object.errorMessage);
                return;
            }
            if(object.of.isVoid()) {
                output.write(STATE_VOID);
                return;
            }
            output.write(STATE_RESULT);
            kryo.writeClassAndObject(output, object.result());
        }

        @Override
        public InvocationResult read(Kryo kryo, Input input, Class<? extends InvocationResult> type) {
            int opNumber = input.readVarInt(true);
            byte status = input.readByte();
            ExecutionContext context = executor.popContext(opNumber);
            if(context == null) {
                return ofError(null, new RuntimeException("Context with number " + opNumber + " not found"));
            }
            return switch(status) {
                case STATE_VOID -> new InvocationResult(context, null, null);
                case STATE_RESULT -> new InvocationResult(context, kryo.readClassAndObject(input), null);
                case STATE_ERR -> new InvocationResult(context, null, input.readString());
                default -> throw new IllegalStateException("Unknown status: " + status);
            };
        }
    }
}
