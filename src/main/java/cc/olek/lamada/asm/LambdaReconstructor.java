package cc.olek.lamada.asm;

import cc.olek.lamada.ObjectStub;
import cc.olek.lamada.ObjectStubFactory;
import cc.olek.lamada.util.Deencapsulation;
import cc.olek.lamada.util.Exceptions;
import com.esotericsoftware.minlog.Log;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static cc.olek.lamada.util.Deencapsulation.defineClass;

public class LambdaReconstructor {
    private static final Map<String, Function<Object[], Object>> generatedSuppliers = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<?>> generationInProcess = new ConcurrentHashMap<>();
    private static final Map<Class<?>, ObjectStubFactory<?, ?>> generatedStubsGenerators = new ConcurrentHashMap<>();
    private static final Map<Class<?>, CompletableFuture<?>> stubGenerationInProcess = new ConcurrentHashMap<>();
    private static final Map<Object, Object> completelyGenerated = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> implementations = new ConcurrentHashMap<>();
    private static final Object exists = new Object();
    private static String debugVal = System.getenv("SYNC_DEBUG");
    public static final boolean DEBUG;
    static {
        if(debugVal == null) {
            debugVal = System.getProperty("sync.debug");
        }
        DEBUG = debugVal != null;
        if(DEBUG) {
            try {
                Log.set(Integer.parseInt(debugVal));
            } catch(Exception e) {
                System.err.println("Failed to parse sync.debug, defaulting to debug");
                Log.DEBUG();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Object reconstructLambda(LambdaImpl lambda, Object[] args, boolean firstEver, ClassLoader classLoader) {
        MethodImpl implementation = lambda.implementation();
        String key = implementation.className() + "." + implementation.methodName() + implementation.signature();
        CompletableFuture<Void> serializeFinished = completelyGenerated.containsKey(key)
            ? null
            : waitUntilClassgenDone(key, generationInProcess);
        return generatedSuppliers.computeIfAbsent(key, c -> {
            try {
                return (Function<Object[], Object>) generateLambdaFactory(lambda, firstEver, classLoader);
            } catch(Throwable e) {
                throw Exceptions.wrap(e);
            } finally {
                if(serializeFinished != null) {
                    completelyGenerated.put(c, exists);
                    serializeFinished.complete(null);
                }
            }
        }).apply(args);
    }

    public static ObjectStubFactory<?, ?> getStubGenerator(Class<?> stubOf) {
        CompletableFuture<Void> serializeFinished = completelyGenerated.containsKey(stubOf)
            ? null
            : waitUntilClassgenDone(stubOf, stubGenerationInProcess);
        return generatedStubsGenerators.computeIfAbsent(stubOf, c -> {
            try {
                return generateStubFactory(c);
            } catch(Throwable e) {
                throw Exceptions.wrap(e);
            } finally {
                if(serializeFinished != null) {
                    completelyGenerated.put(c, exists);
                    serializeFinished.complete(null);
                }
            }
        });
    }

    private static <T> CompletableFuture<Void> waitUntilClassgenDone(T object, Map<T, CompletableFuture<?>> source) {
        CompletableFuture<?> existing = source.get(object);
        CompletableFuture<Void> serializeFinished = new CompletableFuture<>();
        if(existing == null) {
            source.put(object, serializeFinished);
        } else if(!existing.isDone()) {
            existing.join();
        }
        return serializeFinished;
    }

    public static void checkBeforeSending(SerializedLambda lambda, Object lambdaObj) throws Throwable {
        String className = lambda.getImplClass().replace('/', '.');
        ClassLoader classLoader = lambdaObj.getClass().getClassLoader();
        Class<?> lambdaClass = Class.forName(className, true, classLoader);
        Method method = getLambdaImplMethod(lambdaClass, lambda.getImplMethodName());
        if(method != null && Modifier.isPublic(method.getModifiers())) return;

        LambdaImpl lambdaImpl = getLambdaImpl(lambda);
        generateAccessorClass(null, lambdaClass, lambda.getImplClass(), lambdaImpl, classLoader);
    }

    public static Object generateLambdaFactory(LambdaImpl lambda, boolean firstEver, ClassLoader classLoader) throws Throwable {
        MethodImpl originalLambdaImpl = lambda.implementation();
        String lambdaSuffix = originalLambdaImpl.methodName().replace('$', '_') + lambda.implMethodKind();
        Class<?> functionalInterface = Class.forName(
            lambda.functionalInterface().replace('/', '.'),
            true,
            classLoader
        );
        String implClassBinaryName = originalLambdaImpl.className().replace('/', '.');
        Class<?> implementationClazz = Class.forName(implClassBinaryName, true, classLoader);
        String generatedClassName;
        if(firstEver) {
            generatedClassName = "generated." + implClassBinaryName + "$" + lambdaSuffix;
        } else {
            String[] mainPart = implClassBinaryName.split("\\$", 1);
            generatedClassName = "generated." + mainPart[0] + "$I$" + lambdaSuffix;
        }

        byte[] lambdaClassBytes = generateLambdaClass(implementationClazz, generatedClassName, functionalInterface, lambda);
        saveClass(generatedClassName, lambdaClassBytes, true);
        defineClass(implementationClazz.getClassLoader(), generatedClassName, lambdaClassBytes);

        byte[] factoryClassBytes = generateFactoryClass(generatedClassName + "$Generator", generatedClassName);
        saveClass(generatedClassName + "$Generator", factoryClassBytes, false);
        Class<?> factoryClass = defineClass(implementationClazz.getClassLoader(), generatedClassName + "$Generator", factoryClassBytes);

        return factoryClass.getConstructor().newInstance();
    }

    private static byte[] generateLambdaClass(Class<?> originalClass, String className, Class<?> functionalInterface, LambdaImpl lambda) throws Throwable {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String internalName = className.replace('.', '/');

        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null,
            "java/lang/Object", new String[]{Type.getInternalName(functionalInterface)});

        MethodImpl implementation = lambda.implementation();
        Type[] allArgs = Type.getArgumentTypes(implementation.signature());
        Type[] originalSignature = Type.getArgumentTypes(lambda.primarySignature());

        int fieldsToGenerate = calculateFieldsToGenerate(lambda.implMethodKind(), allArgs, originalSignature);
        List<FieldDesc> fields = generateFields(cw, fieldsToGenerate > allArgs.length ? lambda : null, fieldsToGenerate, allArgs);

        generateConstructor(cw, internalName, fields);

        Method functionalMethod = findFunctionalMethod(functionalInterface);
        if(functionalMethod == null) {
            throw new IllegalStateException("Could not find lambda function");
        }
        MethodImpl prev = lambda.implementation();
        if(lambda.implMethodKind() == AsmUtil.H_INVOKESTATIC) {
            lambda.setImplementation(new MethodImpl(internalName, prev.methodName(), prev.signature()));
            generateAccessorClass(cw, originalClass, internalName, lambda, originalClass.getClassLoader());
        }
        generateFunctionalMethod(cw, internalName, functionalMethod, lambda, fields);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateFactoryClass(String className, String targetClassName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String internalName = className.replace('.', '/');
        String targetInternalName = targetClassName.replace('.', '/');

        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null,
            "java/lang/Object", new String[]{"java/util/function/Function"});

        AsmUtil.defineDefaultConstructor(cw, "java/lang/Object");

        // Apply method
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, targetInternalName);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, targetInternalName, "<init>", "([Ljava/lang/Object;)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void generateAccessorClass(ClassVisitor writer, Class<?> originalClass, String newClassName, LambdaImpl lambda, ClassLoader loader) throws Throwable {
        String newInternalName = newClassName.replace('.', '/');
        byte[] originalBytes = null;
        if(originalClass.isSynthetic()) {
            originalBytes = implementations.get(originalClass.getName());
        }
        if(originalBytes == null) {
            originalBytes = getClassBytes(originalClass);
        }

        ClassReader cr = new ClassReader(originalBytes);
        boolean define = true;
        if(writer == null) {
            define = false;
            writer = new ClassVisitor(Opcodes.ASM9, null) {};
        }
        var editor = new ClassVisitor(Opcodes.ASM9, writer) {
            LambdaCorrector primaryAnalyzer;
            final Map<String, LambdaCorrector> otherAnalyzers = new HashMap<>();
            String originalSource;
            String className;
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, newInternalName, null, "java/lang/Object", null);
                className = name;
            }

            @Override
            public void visitSource(String source, String debug) {
                super.visitSource(source, debug);
                originalSource = source;
            }

            // skip garbage from the class we're reading from
            @Override
            public void visitNestHost(String nestHost) {}

            @Override
            public void visitNestMember(String nestMember) {}

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {}

            @Override
            public void visitOuterClass(String owner, String name, String descriptor) {}

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) { return null; }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if(name.equals(lambda.implementation().methodName())) {
                    if(primaryAnalyzer != null) return null;
                    return primaryAnalyzer = new LambdaCorrector(
                        className,
                        newClassName,
                        originalSource,
                        new MethodNode(
                            Opcodes.ASM9,
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            name,
                            descriptor,
                            signature,
                            exceptions
                        ),
                        loader
                    );
                }
                if(!name.startsWith("lambda") || (access & Opcodes.ACC_SYNTHETIC) == 0) return null;
                LambdaCorrector other = new LambdaCorrector(className, newInternalName, originalSource, new MethodNode(
                    access & ~(Opcodes.ACC_SYNTHETIC | Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC,
                    name,
                    descriptor,
                    signature,
                    exceptions
                ), loader);
                otherAnalyzers.put(name, other);
                return other;
            }
        };
        cr.accept(editor, ClassReader.SKIP_FRAMES);
        if(!define) return;
        LambdaCorrector primaryAnalyzer = editor.primaryAnalyzer;
        if(primaryAnalyzer != null) {
            primaryAnalyzer.correct();
            acceptReferences(writer, primaryAnalyzer, editor.otherAnalyzers);
        }
    }

    private static void acceptReferences(ClassVisitor into, LambdaCorrector root, Map<String, LambdaCorrector> correctors) {
        root.getNode().accept(into);
        for(String ref : root.getOtherLambdaReferences()) {
            LambdaCorrector lambdaCorrector = correctors.get(ref);
            if(lambdaCorrector == null) continue;
            acceptReferences(into, lambdaCorrector,correctors);
        }
    }

    private static byte[] getClassBytes(Class<?> clazz) throws IOException {
        String resourceName = "/" + clazz.getName().replace('.', '/') + ".class";
        try(InputStream is = clazz.getResourceAsStream(resourceName)) {
            if(is == null) throw new IOException("Could not find class resource: " + resourceName);
            return is.readAllBytes();
        }
    }

    private static int calculateFieldsToGenerate(int methodKind, Type[] allArgs, Type[] originalSignature) {
        if(originalSignature.length == allArgs.length && methodKind != AsmUtil.H_INVOKESTATIC) {
            return allArgs.length + 1;
        }
        if(originalSignature.length == 0) return allArgs.length;
        if(allArgs.length == 0) return 0;
        return allArgs.length - 1;
    }

    private static List<FieldDesc> generateFields(ClassWriter cw, LambdaImpl instance, int fieldsToGenerate, Type[] allArgs) {
        if(fieldsToGenerate == 0) return List.of();

        List<FieldDesc> fields = new ArrayList<>(fieldsToGenerate);
        for(int i = 0; i < fieldsToGenerate; i++) {
            Type param;
            if(instance != null) {
                if(i == 0) {
                    param = Type.getType("Ljava/lang/Object;");
                } else {
                    param = allArgs[i - 1];
                }
            } else {
                param = allArgs[i];
            }
            String fieldName = "arg$" + (i + 1);
            cw.visitField(Opcodes.ACC_PUBLIC, fieldName, param.getDescriptor(), null, null).visitEnd();
            fields.add(new FieldDesc(param, fieldName));
        }
        return fields;
    }

    private static void generateConstructor(ClassWriter cw, String internalName, List<FieldDesc> fields) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null);
        mv.visitCode();

        // super
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        for(int i = 0; i < fields.size(); i++) {
            FieldDesc field = fields.get(i);
            mv.visitVarInsn(Opcodes.ALOAD, 0); // this
            mv.visitVarInsn(Opcodes.ALOAD, 1); // array
            AsmUtil.pushInt(mv, i); // index
            mv.visitInsn(Opcodes.AALOAD); // array[index]
            AsmUtil.unboxIfNeeded(mv, field.type());
            mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, field.name(), field.type().getDescriptor());
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void generateFunctionalMethod(ClassWriter cw, String internalName, Method functionalMethod, LambdaImpl lambda, List<FieldDesc> fields) {
        String methodDesc = Type.getMethodDescriptor(functionalMethod);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, functionalMethod.getName(), methodDesc, null, null);
        mv.visitCode();

        Type[] argsToOriginal = Type.getArgumentTypes(lambda.primarySignature());

        for(FieldDesc field : fields) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, internalName, field.name(), field.type().getDescriptor());
        }

        // Load method arguments
        int argIndex = 1;
        int argsToOriginalLength = argsToOriginal.length;
        for(Type argType : argsToOriginal) {
            int varIndex = argIndex++;
            mv.visitVarInsn(Opcodes.ALOAD, varIndex);
            Label continuation = new Label();
            if(lambda.implMethodKind() != AsmUtil.H_INVOKESTATIC && varIndex == argsToOriginalLength) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitJumpInsn(Opcodes.IFNONNULL, continuation);
                mv.visitLdcInsn(lambda.implementation().className().replace('/', '.') + " is null to call " + lambda.implementation().methodName());
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(Exceptions.class), "runtime", "(Ljava/lang/String;)Ljava/lang/RuntimeException;", false);
                mv.visitInsn(Opcodes.ATHROW);
                mv.visitLabel(continuation);
            }
            if(argType.getSort() == Type.ARRAY) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, argType.getDescriptor());
            } else if(argType.getSort() == Type.OBJECT) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, argType.getInternalName());
            }
        }

        MethodImpl implementation = lambda.implementation();
        int invokeOpcode = AsmUtil.getInvokeOpcode(lambda.implMethodKind());
        boolean isInterface = (lambda.implMethodKind() == AsmUtil.H_INVOKEINTERFACE);
        mv.visitMethodInsn(invokeOpcode, implementation.className(), implementation.methodName(),
            implementation.signature(), isInterface);

        // Handle return
        Class<?> returnType = functionalMethod.getReturnType();
        if(returnType == void.class) {
            mv.visitInsn(Opcodes.RETURN);
        } else if(returnType.isPrimitive()) {
            AsmUtil.generatePrimitiveReturn(mv, returnType);
        } else {
            mv.visitInsn(Opcodes.ARETURN);
        }

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static Method findFunctionalMethod(Class<?> functionalInterface) {
        for(Method method : functionalInterface.getMethods()) {
            if(!method.isDefault()) return method;
        }
        return null;
    }

    private static Method getLambdaImplMethod(Class<?> implementationClazz, String name) {
        for(Method method : implementationClazz.getDeclaredMethods()) {
            if(method.isSynthetic() || method.getName().equals(name)) return method;
        }
        return null;
    }

    private static void saveClass(String name, byte[] array, boolean resolve) throws IOException {
        if(DEBUG) {
            File dir = new File(".debug");
            if(!dir.exists()) {
                dir.mkdirs();
            }
            Files.write(Path.of(".debug", name + ".class"), array);
        }
        if(resolve) {
            implementations.put(name, array);
        }
    }

    public static LambdaImpl getLambdaImpl(SerializedLambda lambda) {
        return new LambdaImpl(lambda.getFunctionalInterfaceClass(), lambda.getImplMethodKind(),
            lambda.getInstantiatedMethodType(),
            new MethodImpl(lambda.getImplClass(), lambda.getImplMethodName(), lambda.getImplMethodSignature()));
    }

    public static ObjectStubFactory<?, ?> generateStubFactory(Class<?> objectType) throws Throwable {
        String objectTypeNameBinary = objectType.getName();
        String objectTypeName = objectTypeNameBinary.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

        String superclass = ObjectStub.class.getName().replace('.', '/');
        String stubClass = objectTypeName + "$Stub";
        String stubBinaryName = objectTypeNameBinary + "$Stub";
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, stubClass, null, superclass, new String[] {objectTypeName});
        AsmUtil.defineDefaultConstructor(cw, superclass);
        for(Method method : objectType.getDeclaredMethods()) {
            if(Modifier.isPrivate(method.getModifiers())) continue;
            String methodDescriptor = Type.getMethodDescriptor(method);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method.getName(), methodDescriptor, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            Type[] args = Type.getArgumentTypes(methodDescriptor);
            if(args.length != 0) {
                AsmUtil.pushInt(mv, args.length);
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                int varIndex = 1;
                for(int i = 0; i < args.length; i++) {
                    mv.visitInsn(Opcodes.DUP);
                    Type arg = args[i];
                    AsmUtil.pushInt(mv, i);
                    mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), varIndex);
                    varIndex += arg.getSize();
                    AsmUtil.boxPrimitive(mv, arg);
                    mv.visitInsn(Opcodes.AASTORE);
                }
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }

            mv.visitLdcInsn(method.getName() + Type.getMethodDescriptor(method));
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superclass, "sendSingleMethod", "([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            Type rType = Type.getReturnType(methodDescriptor);
            int returnSort = rType.getSort();
            if(returnSort == Type.VOID) {
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.RETURN);
            } else {
                AsmUtil.unboxIfNeeded(mv, rType);
                mv.visitInsn(rType.getOpcode(Opcodes.IRETURN));
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        byte[] bytes = cw.toByteArray();

        saveClass(stubBinaryName, bytes, false);

        Deencapsulation.defineClass(LambdaReconstructor.class.getClassLoader(), stubBinaryName, bytes);
        String stubFactoryName = objectTypeName + "$StubFactory";
        String stubFactoryNameBinary = objectTypeNameBinary + "$StubFactory";
        String factorySuperclass = ObjectStubFactory.class.getName().replace('.', '/');
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, stubFactoryName, null, factorySuperclass, null);
        AsmUtil.defineDefaultConstructor(cw, factorySuperclass);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "createNewStub", "()Ljava/lang/Object;", null, null);
        mv.visitTypeInsn(Opcodes.NEW, stubClass);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, stubClass, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        bytes = cw.toByteArray();
        saveClass(stubFactoryNameBinary, bytes, false);
        Class<?> factoryClass = defineClass(LambdaReconstructor.class.getClassLoader(), stubFactoryNameBinary, bytes);
        return (ObjectStubFactory<?, ?>) factoryClass.getConstructor().newInstance();
    }
}