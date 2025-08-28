package cc.olek.lamada.func;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.DistributedObject;
import cc.olek.lamada.ObjectStub;
import cc.olek.lamada.asm.LambdaImpl;
import cc.olek.lamada.asm.LambdaReconstructor;
import cc.olek.lamada.asm.MethodImpl;
import cc.olek.lamada.serialization.SuperclassSerializer;
import cc.olek.lamada.util.Deencapsulation;
import cc.olek.lamada.util.Exceptions;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.util.HashMap;
import java.util.Map;

public interface ExecutableInterface extends Serializable {
    byte RUNNABLE = 0x0;
    byte SUPPLIER = 0x1;
    byte CONSUMER = 0x2;
    byte FUNCTION = 0x3;
    byte MODE_ERR = 0x4;

    static boolean isStatic(byte val) {
        return val == RUNNABLE || val == SUPPLIER;
    }

    class LambdaSerializer<Target> extends Serializer<Object> implements SuperclassSerializer {
        public static final byte FIRST_TIME_NUM = 0x0; // when there's a number and full implementation info
        public static final byte NUM_NO_IMPL = 0x1; // when there's just a number
        public static final byte NO_NUM = 0x2; // when there's (always) full implementation info and no number

        private static final Map<Class<?>, MethodHandle> handleMap = new HashMap<>();

        private final DistributedExecutor<Target> executor;

        public LambdaSerializer(DistributedExecutor<Target> executor) {
            this.executor = executor;
        }

        @Override
        public void write(Kryo kryo, Output output, Object object) {
            Target sendTo = (Target) kryo.getContext().get("receiver");
            SerializedLambda lambda;
            try {
                lambda = (SerializedLambda) handleMap.computeIfAbsent(object.getClass(), clazz -> {
                    try {
                        return Deencapsulation.LOOKUP.findVirtual(object.getClass(), "writeReplace", MethodType.methodType(Object.class));
                    } catch(Exception e) {
                        throw Exceptions.wrap(e);
                    }
                }).invoke(object);
            } catch(Throwable e) {
                throw Exceptions.wrap(e);
            }
            writeFull: {
                var submissionResult = executor
                    .getTargetManager()
                    .onSerialize(sendTo, LambdaReconstructor.getLambdaImpl(lambda));
                if(submissionResult == null) break writeFull;
                if(submissionResult.existedBefore()) {
                    output.writeByte(NUM_NO_IMPL);
                    output.writeShort(submissionResult.lambdaNum());
                    break writeFull;
                }
                try {
                    LambdaReconstructor.checkBeforeSending(lambda, object);
                } catch(Throwable e) {
                    throw Exceptions.wrap(e);
                }
                writeFullLambda(output, lambda, submissionResult.lambdaNum(), FIRST_TIME_NUM);
            }

            int capturedCount = lambda.getCapturedArgCount();
            output.writeVarInt(capturedCount, true);
            for(int i = 0; i < capturedCount; i++) {
                kryo.writeClassAndObject(output, lambda.getCapturedArg(i));
            }
        }

        private Object[] readLambdaParams(Kryo kryo, Input input) {
            int objectAmount = input.readVarInt(true);
            Object[] params = new Object[objectAmount];
            for(int i = 0; i < objectAmount; i++) {
                params[i] = kryo.readClassAndObject(input);
            }
            return params;
        }

        private static void writeFullLambda(Output output, SerializedLambda lambda, short lambdaNum, byte status) {
            output.writeByte(status);
            if(status == FIRST_TIME_NUM) {
                output.writeShort(lambdaNum);
            }
            output.writeString(lambda.getFunctionalInterfaceClass());
            output.writeString(lambda.getInstantiatedMethodType());
            output.writeString(lambda.getImplClass());
            output.writeString(lambda.getImplMethodName());
            output.writeString(lambda.getImplMethodSignature());
            output.writeByte(lambda.getImplMethodKind());
        }

        @Override
        public Object read(Kryo kryo, Input input, Class<?> type) {
            Target sender = (Target) kryo.getContext().get("sender");
            byte status = input.readByte();
            LambdaImpl impl;
            readFull: {
                if(status == NUM_NO_IMPL) {
                    short lambdaNum = input.readShort();
                    impl = executor.getTargetManager().reconstruct(sender, lambdaNum);
                    if(impl == null) {
                        Object[] params = readLambdaParams(kryo, input);
                        impl = executor.getTargetManager().requestMissingImplementation(sender, lambdaNum);
                        if(impl == null) {
                            throw new RuntimeException("Lambda implementation #" + lambdaNum + " was null, failed to explicitly request it by number");
                        }
                        if(LambdaReconstructor.DEBUG) {
                            executor.getLogger().info("{} didn't exist, explicit request yielded {}", lambdaNum, impl);
                        }

                        ExecutableInterface itf;
                        try {
                            itf = (ExecutableInterface) LambdaReconstructor.reconstructLambda(impl, params, false, executor.getContextClassLoader());
                        } catch(Exception e) {
                            throw new RuntimeException("Failed reconstructing requested lambda with num " + lambdaNum + " on " + sender, e);
                        }
                        if(itf == null) {
                            throw new RuntimeException("Reconstructed lambda " + lambdaNum + " on " + sender + " is null");
                        }
                        return itf;
                    }
                    break readFull;
                }

                short lambdaNum = -1;
                if(status == FIRST_TIME_NUM) {
                    lambdaNum = input.readShort();
                }

                String functionalClass = input.readString();
                String functionalSign = input.readString();
                String implClass = input.readString();
                String implMethod = input.readString();
                String implSign = input.readString();
                int methodKind = input.readByte();

                impl = new LambdaImpl(
                    functionalClass,
                    methodKind,
                    functionalSign,
                    new MethodImpl(implClass, implMethod, implSign)
                );
                if(status == FIRST_TIME_NUM) {
                    executor.getTargetManager().registerImplementation(sender, lambdaNum, impl.clone());
                }
            }
            boolean firstEver = (Boolean) kryo.getContext().get("first", true);
            if(firstEver) {
                kryo.getContext().put("first", false);
            }
            Object[] params = readLambdaParams(kryo, input);
            try {
                return LambdaReconstructor.reconstructLambda(impl, params, firstEver, executor.getContextClassLoader());
            } catch(Exception e) {
                throw Exceptions.wrap(e);
            }
        }
    }
}
