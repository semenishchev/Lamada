package cc.olek.lamada.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

public class SimpleSerializers {
    public abstract static class ToStringSerializer<T> extends Serializer<T> {
        @Override
        public void write(Kryo kryo, Output output, T object) {
            output.writeString(toString(object));
        }

        @Override
        public T read(Kryo kryo, Input input, Class<? extends T> type) {
            return fromString(input.readString());
        }

        public String toString(T obj) {
            return obj.toString();
        }

        public abstract T fromString(String input);
    }

    public abstract static class ToByteArraySerializer<T> extends Serializer<T> {
        @Override
        public void write(Kryo kryo, Output output, T object) {
            byte[] bytes = toByteArray(object);
            output.writeVarInt(bytes.length, true);
            output.writeBytes(bytes);
        }

        @Override
        public T read(Kryo kryo, Input input, Class<? extends T> type) {
            int length = input.readVarInt(true);
            return fromByteArray(input.readBytes(length));
        }

        public abstract byte[] toByteArray(T obj);

        public abstract T fromByteArray(byte[] input);
    }
}
