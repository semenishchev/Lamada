package cc.olek.lamada.util;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Objects;

@SuppressWarnings("all")
public class Deencapsulation {
    public static final MethodHandles.Lookup LOOKUP;
    public static final MethodHandle defineClass;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            MethodHandles.publicLookup();
            MethodHandles.Lookup lookup = (MethodHandles.Lookup)
                unsafe.getObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field));
            MethodType type = MethodType.methodType(Module.class);
            LOOKUP = lookup;
            defineClass = LOOKUP.findVirtual(
                ClassLoader.class,
                "defineClass",
                MethodType.methodType(Class.class, String.class, byte[].class, int.class, int.class)
            );
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public static Class<?> defineClass(Class<?> parent, byte[] classfile) throws Throwable {
        return LOOKUP.in(parent).defineHiddenClass(classfile, true, MethodHandles.Lookup.ClassOption.NESTMATE, MethodHandles.Lookup.ClassOption.STRONG).lookupClass();
    }
    public static Class<?> defineClass(ClassLoader in, String name, byte[] classfile) {
        if(in == null) {
            in = ClassLoader.getSystemClassLoader();
        }
        try {
            return (Class<?>) defineClass.invokeWithArguments(in, name, classfile, 0, classfile.length);
        } catch(Throwable t) {
            throw new RuntimeException("Failed to define " + name + " in " + in, t);
        }
    }
}
