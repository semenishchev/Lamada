package cc.olek.lamada.asm;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class AsmUtil {
    // Method handle kinds
    static final int H_INVOKEVIRTUAL = 5;
    static final int H_INVOKESTATIC = 6;
    static final int H_INVOKESPECIAL = 7;
    static final int H_NEWINVOKESPECIAL = 8;
    static final int H_INVOKEINTERFACE = 9;

    public static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value); // ICONST_M1 = ICONST_0 + (-1)
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public static void defineDefaultConstructor(ClassVisitor cv, String superclass) {
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superclass, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
    }

    public static void unbox(MethodVisitor mv, Type targetType, String from, String method) {
        mv.visitTypeInsn(Opcodes.CHECKCAST, from);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, from, method, "()" + targetType.getDescriptor(), false);
    }

    public static void unboxIfNeeded(MethodVisitor mv, Type targetType) {
        switch(targetType.getSort()) {
            case Type.INT -> unbox(mv, targetType, "java/lang/Integer", "intValue");
            case Type.LONG -> unbox(mv, targetType, "java/lang/Long", "longValue");
            case Type.DOUBLE -> unbox(mv, targetType, "java/lang/Double", "doubleValue");
            case Type.FLOAT -> unbox(mv, targetType, "java/lang/Float", "floatValue");
            case Type.BOOLEAN -> unbox(mv, targetType, "java/lang/Boolean", "booleanValue");
            case Type.BYTE -> unbox(mv, targetType, "java/lang/Byte", "byteValue");
            case Type.CHAR -> unbox(mv, targetType, "java/lang/Character", "charValue");
            case Type.SHORT -> unbox(mv, targetType, "java/lang/Short", "shortValue");
            default -> mv.visitTypeInsn(Opcodes.CHECKCAST, targetType.getInternalName());
        }
    }

    public static void generatePrimitiveReturn(MethodVisitor mv, Class<?> returnType) {
        if(returnType == boolean.class) mv.visitInsn(Opcodes.IRETURN);
        else if(returnType == byte.class) mv.visitInsn(Opcodes.IRETURN);
        else if(returnType == char.class) mv.visitInsn(Opcodes.IRETURN);
        else if(returnType == short.class) mv.visitInsn(Opcodes.IRETURN);
        else if(returnType == int.class) mv.visitInsn(Opcodes.IRETURN);
        else if(returnType == long.class) mv.visitInsn(Opcodes.LRETURN);
        else if(returnType == float.class) mv.visitInsn(Opcodes.FRETURN);
        else if(returnType == double.class) mv.visitInsn(Opcodes.DRETURN);
    }

    public static void boxPrimitive(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            case Type.CHAR ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
            case Type.BYTE ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            case Type.SHORT ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
            case Type.INT ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            case Type.FLOAT ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            case Type.LONG ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            case Type.DOUBLE ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        }
    }

    public static int getInvokeOpcode(int implMethodKind) {
        return switch(implMethodKind) {
            case H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
            case H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
            case H_INVOKESPECIAL, H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
            case H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
            default ->
                throw new IllegalArgumentException("Unsupported method kind: " + implMethodKind);
        };
    }
}
