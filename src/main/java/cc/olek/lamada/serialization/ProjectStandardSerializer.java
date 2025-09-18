package cc.olek.lamada.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

public class ProjectStandardSerializer extends Serializer<Object> {
    private final Serializer<?> defaultSerializer;

    public ProjectStandardSerializer(Serializer<?> defaultSerializer) {
        this.defaultSerializer = defaultSerializer;
    }

    @Override
    public void write(Kryo kryo, Output output, Object object) {

    }

    @Override
    public Object read(Kryo kryo, Input input, Class<?> type) {
        return null;
    }
}
