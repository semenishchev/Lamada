package cc.olek.lamada.func;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.DistributedObject;
import cc.olek.lamada.ObjectStub;
import cc.olek.lamada.asm.LambdaImpl;
import cc.olek.lamada.asm.LambdaReconstructor;
import cc.olek.lamada.asm.MethodImpl;
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

    class LambdaSerializer<Target> extends Serializer<Object> {
        public static final byte FIRST_TIME_NUM = 0x0; // when there's a number and full implementation info
        public static final byte NUM_NO_IMPL = 0x1; // when there's just a number
        public static final byte NO_NUM = 0x2; // when there's (always) full implementation info and no number

        public static final byte NIL = 0x0; // when the argument is null
        public static final byte OTHER_LAMBDA = 0x1; // when argument is another lambda
        public static final byte OBJ = 0x2; // regular side object
        public static final byte OBJ_REFERENCED = 0x3; // regular side object which might reference other objects (unused for now)
        public static final byte STUB = 0x4; // another object handled by the DistributedExecutor
        public static final byte ARR = 0x51; // array of objects

        private static final Map<Class<?>, MethodHandle> handleMap = new HashMap<>();

        private final DistributedExecutor<Target> executor;
        private final Target sender;
        private final Target sendTo;

        // This constructor is invoked during read
        public LambdaSerializer(Target sender, DistributedExecutor<Target> executor) {
            this.sender = sender;
            this.executor = executor;
            this.sendTo = null;
        }

        // This constructor is invoked during write
        public LambdaSerializer(DistributedExecutor<Target> executor, Target sendTo) {
            this.executor = executor;
            this.sender = executor.getOwnTarget();
            this.sendTo = sendTo;
        }

        @Override
        public void write(Kryo kryo, Output output, Object object) {
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
                checker: if(sender != null) {
                    var submissionResult = executor
                        .getTargetManager()
                        .onSerialize(sendTo, LambdaReconstructor.getLambdaImpl(lambda));
                    if(submissionResult == null) break checker;
                    if(submissionResult.existedBefore()) {
                        output.writeByte(NUM_NO_IMPL);
                        output.writeShort(submissionResult.lambdaNum());
                        break writeFull;
                    }
                    try {
                        LambdaReconstructor.checkBeforeSending(lambda);
                    } catch(Throwable e) {
                        throw Exceptions.wrap(e);
                    }
                    writeFullLambda(output, lambda, submissionResult.lambdaNum(), FIRST_TIME_NUM);
                    break writeFull;
                }
                writeFullLambda(output, lambda, (short)0, NO_NUM);
            }

            int capturedCount = lambda.getCapturedArgCount();
            output.writeVarInt(capturedCount, true);
            for(int i = 0; i < capturedCount; i++) {
                writeObj(kryo, output, lambda.getCapturedArg(i));
            }
        }

        private void writeObj(Kryo kryo, Output output, Object capturedArg) {
            if(capturedArg == null) {
                output.writeByte(NIL);
                return;
            }
            if(capturedArg instanceof Object[] arr) {
                writeObjArray(kryo, output, arr);
                return;
            }
            boolean writeReplace = false;
            try {
                capturedArg.getClass().getDeclaredMethod("writeReplace");
                writeReplace = true;
            } catch(NoSuchMethodException ignored) {}
            if(writeReplace) {
                output.writeByte(OTHER_LAMBDA);
                kryo.writeObject(output, capturedArg, this);
                return;
            }
            if(capturedArg instanceof ObjectStub stub) {
                output.writeByte(STUB);
                DistributedObject<Object, ?, Object> objWriting = stub.getObject();
                output.writeShort(objWriting.getNumber());
                kryo.writeObject(output, capturedArg, objWriting);
                return;
            }
            Class<?> capturedClass = capturedArg.getClass();
            DistributedObject<?, ?, ?> objWriting = executor.getSerializerByValueClass(capturedClass);
            if(objWriting == null) {
                for(Class<?> anInterface : capturedClass.getInterfaces()) {
                    objWriting = executor.getSerializerByValueClass(anInterface);
                    if(objWriting != null) {
                        break;
                    }
                }
            }
            if(objWriting != null) {
                output.writeByte(STUB);
                output.writeShort(objWriting.getNumber());
                kryo.writeObject(output, capturedArg, objWriting);
                return;
            }
            if(capturedClass.isSynthetic()) {
                throw new RuntimeException("Cannot serialize synthetic classes. You are probably trying to use a different Lambda interface. You can only use ExecutionConsumer or ExecutionFunction");
            }
            output.writeByte(OBJ);
            kryo.writeClassAndObject(output, capturedArg);
        }

        private void writeObjArray(Kryo kryo, Output output, Object[] arr) {
            output.writeByte(ARR);
            output.writeVarInt(arr.length, true);
            for(Object o : arr) {
                writeObj(kryo, output, o);
            }
        }

        private Object readObj(Kryo kryo, Input input) {
            byte paramStatus = input.readByte();
            return switch(paramStatus) {
                case NIL -> null;
                case OTHER_LAMBDA -> {
                    Object obj = kryo.readObject(input, Object.class, this);
                    if(!(obj instanceof ExecutableInterface)) {
                        kryo.reference(obj);
                    }
                    yield obj;
                }
                case OBJ -> {
                    Object obj = kryo.readClassAndObject(input);
                    kryo.reference(obj);
                    yield obj;
                }
                case ARR -> {
                    int len = input.readVarInt(true);
                    Object[] result = new Object[len];
                    for(int i = 0; i < len; i++) {
                        result[i] = readObj(kryo, input);
                    }
                    kryo.reference(result);
                    yield result;
                }
                case STUB -> {
                    short regId = input.readShort();
                    DistributedObject<?, ?, ?> serializer = executor.getSerializerByNumber(regId);
                    if(serializer == null) {
                        throw new RuntimeException("Failed to find serializer with registration ID: " + regId);
                    }
                    if(serializer.isUnique()) {
                        ObjectStub objectStub = kryo.readObject(input, ObjectStub.class, serializer);
                        objectStub.setTarget(this.sender);
                        yield objectStub;
                    }
                    yield kryo.readObject(input, serializer.getObjectType(), serializer);
                }
                default -> throw new UnsupportedOperationException(Integer.toHexString(paramStatus) + " is not supported yet");
            };
        }

        private Object[] readLambdaParams(Kryo kryo, Input input) {
            int objectAmount = input.readVarInt(true);
            Object[] params = new Object[objectAmount];
            for(int i = 0; i < objectAmount; i++) {
                params[i] = readObj(kryo, input);
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
                            itf = (ExecutableInterface) LambdaReconstructor.reconstructLambda(impl, params, false);
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
                    executor.getTargetManager().registerImplementation(this.sender, lambdaNum, impl.clone());
                }
            }
            boolean firstEver = (Boolean) kryo.getContext().get("first", true);
            if(firstEver) {
                kryo.getContext().put("first", false);
            }
            Object[] params = readLambdaParams(kryo, input);
            try {
                return LambdaReconstructor.reconstructLambda(impl, params, firstEver);
            } catch(Exception e) {
                throw Exceptions.wrap(e);
            }
        }
    }
}
