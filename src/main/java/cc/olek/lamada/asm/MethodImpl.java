package cc.olek.lamada.asm;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

public record MethodImpl(String className, String methodName, String signature) {
    public static class MethodSerializer extends com.esotericsoftware.kryo.Serializer<MethodImpl> {

        @Override
        public void write(Kryo kryo, Output output, MethodImpl object) {
            output.writeString(object.className);
            output.writeString(object.methodName);
            output.writeString(object.signature);
        }

        @Override
        public MethodImpl read(Kryo kryo, Input input, Class<? extends MethodImpl> type) {
            return new MethodImpl(input.readString(), input.readString(), input.readString());
        }
    }

    @Override
    public MethodImpl clone() {
        return new MethodImpl(className, methodName, signature);
    }
}
