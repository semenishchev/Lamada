package cc.olek.lamada.asm;

import cc.olek.lamada.util.CheckFailedException;
import cc.olek.lamada.util.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LambdaCorrector extends MethodVisitor {
    private final MethodNode node;
    private final String originalSource;
    private final String className;
    private final String classNameBinary;
    private final String newClassName;
    private final Set<String> otherLambdaReferences = new HashSet<>();
    private int lastLineNumber;
    private int firstLineNumber = -1;

    protected LambdaCorrector(String className, String newClassName, String originalSource, LambdaImpl impl, MethodVisitor delegate) {
        super(Opcodes.ASM9, delegate);
        if(delegate instanceof MethodNode node) {
            this.node = node;
        } else {
            this.node = null;
        }
        this.originalSource = originalSource;
        this.className = className;
        this.newClassName = newClassName;
        this.classNameBinary = className.replace('/', '.');
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        super.visitLineNumber(line, start);
        lastLineNumber = line;
        if(firstLineNumber == -1) {
            firstLineNumber = line;
        }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        if(opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
            throw context("Setting field is illegal");
        }
        try {
            Field field = Class.forName(owner.replace('/', '.')).getDeclaredField(name);
            int modifiers = field.getModifiers();
            if(!Modifier.isPublic(modifiers)) {
                throw context("You may not access private fields: " + Modifier.toString(modifiers) + " " + Type.getType(descriptor).getClassName() + " " + owner);
            }
        } catch(NoSuchFieldException ignored) {
            throw context("You may not access private fields: " + Type.getType(descriptor).getClassName() + " " + owner);
        } catch(Throwable ignored) {}
        super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    private final Set<String> checked = new HashSet<>();
    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        if(owner.equals(className) && name.startsWith("lambda$")) {
            owner = newClassName;
        }
        checker: try {
            if(!checked.add(name + descriptor)) break checker;
            Type[] args = Type.getArgumentTypes(name);
            Class<?>[] argTypesRuntime = new Class<?>[args.length];
            for(int i = 0; i < args.length; i++) {
                try {
                    argTypesRuntime[i] = Class.forName(args[i].getClassName());
                } catch(Exception e) {
                    break checker;
                }
            }
            Method method = Class.forName(owner.replace('/', '.')).getDeclaredMethod(name, argTypesRuntime);
            if(!Modifier.isPrivate(method.getModifiers())) {
                StringBuilder argsStr = new StringBuilder();
                argsStr.append("(");
                for(int i = 0, argTypesRuntimeLength = argTypesRuntime.length; i < argTypesRuntimeLength; i++) {
                    argsStr.append(argTypesRuntime[i].getName());
                    if(i + 1 != argTypesRuntimeLength) {
                        argsStr.append(", ");
                    }
                }
                argsStr.append(")");
                throw context("You may not access " + Modifier.toString(method.getModifiers()) + " " + method.getName() + argsStr);
            }
        } catch(Throwable ignored) {}
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        if(bootstrapMethodHandle.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
            Handle lambdaInvoke = (Handle) bootstrapMethodArguments[1];
            if(lambdaInvoke.getOwner().equals(className)) {
                otherLambdaReferences.add(lambdaInvoke.getName());
                bootstrapMethodArguments[1] = new Handle(lambdaInvoke.getTag(), newClassName, lambdaInvoke.getName(), lambdaInvoke.getDesc(), lambdaInvoke.isInterface());
            }
            String method = bootstrapMethodHandle.getName();
            if(method.equals("metafactory")) {
                bootstrapMethodHandle = new Handle(
                    bootstrapMethodHandle.getTag(),
                    bootstrapMethodHandle.getOwner(),
                    "altMetafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                    false
                );

                // rebuild arguments for altMetafactory
                bootstrapMethodArguments = new Object[] {
                    bootstrapMethodArguments[0],
                    bootstrapMethodArguments[1],
                    bootstrapMethodArguments[2],
                    LambdaMetafactory.FLAG_SERIALIZABLE
                };
            } else if (method.equals("altMetafactory")) {
                int originalFlags = (Integer) bootstrapMethodArguments[3];
                bootstrapMethodArguments[3] = originalFlags | LambdaMetafactory.FLAG_SERIALIZABLE;
            }
        }
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    public MethodNode getNode() {
        return node;
    }

    // we need to check if any of the invokes can be computed BEFORE sending it to the target
    // alongside with inserting a null check at the target method which is going to be invoked
    // to prevent unpredictable behaviour. If you don't check for null, then you don't expect it
    // so if value IS null, throw exception before anything is executed
    public void correct() {
        if(node == null) {
            throw new IllegalStateException("Cannot use correction on secondary lambda methods");
        }
        Type[] ownTypes = Type.getArgumentTypes(node.desc);
        boolean doesNullCheck = ownTypes.length == 0; // if it's empty, we don't need null check
        if(!doesNullCheck) {
            int last = ownTypes[ownTypes.length - 1].getSort();
            doesNullCheck = last != Type.OBJECT && last != Type.ARRAY;
        }
        if(doesNullCheck) return;
        int maxVarIndex = 0;
        // not the most optimal way, but asm does some weird shenanigans in getArgumentAndReturnSizes
        for(Type ownType : ownTypes) {
            maxVarIndex += ownType.getSize();
        }
        if(Modifier.isStatic(node.access)) {
            maxVarIndex--;
        }
        for(AbstractInsnNode instruction : node.instructions) {
            if(!(instruction instanceof JumpInsnNode jump)) continue;
            if(jump.getOpcode() != Opcodes.IFNULL && jump.getOpcode() != Opcodes.IFNONNULL) continue;
            AbstractInsnNode prev = jump.getPrevious();
            while(prev instanceof LineNumberNode || prev instanceof LabelNode) {
                prev = prev.getPrevious();
            }
            if(prev == null) {
                throw new CheckFailedException("Invalid bytecode, expected stack mod for null comparison, got no insn");
            }

            if(!(prev instanceof VarInsnNode varInsn)) continue;
            if(varInsn.getOpcode() != Opcodes.ALOAD || varInsn.var != maxVarIndex) continue;
            doesNullCheck = true;
            break;
        }
        if(!doesNullCheck) {
            InsnList nullCheck = new InsnList();
            LabelNode originalCode = new LabelNode();
            nullCheck.add(new VarInsnNode(Opcodes.ALOAD, maxVarIndex));
            nullCheck.add(new JumpInsnNode(Opcodes.IFNONNULL, originalCode));
            Type owner = Type.getType(Exceptions.class);
            nullCheck.add(new LdcInsnNode(makeContextMessage(node.localVariables.getLast().name + " is null", firstLineNumber)));
            nullCheck.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner.getInternalName(), "runtime", "(Ljava/lang/String;)Ljava/lang/RuntimeException;"));
            nullCheck.add(new InsnNode(Opcodes.ATHROW));
            nullCheck.add(originalCode); // original code starts executing after our if check
            node.instructions.insert(nullCheck);
        }

        // todo: maybe turn it back into lambda analyzer and merge consecutive void calls or calls which pop off the result into a single stub call
    }

    private RuntimeException context(String message) {
        return new RuntimeException(makeContextMessage(message, lastLineNumber));
    }

    private @NotNull String makeContextMessage(String message, int line) {
        return message + "\n\t" + "at " + classNameBinary + "(" + originalSource + ":" + line + ")";
    }

    public Set<String> getOtherLambdaReferences() {
        return otherLambdaReferences;
    }
}
