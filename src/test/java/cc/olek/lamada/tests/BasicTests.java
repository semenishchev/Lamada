package cc.olek.lamada.tests;

import cc.olek.lamada.func.ExecutionSupplier;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.io.Serializable;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.function.Supplier;

public class BasicTests {
    @Test
    public void testNonExistentWriteReplace() throws Exception {
        Supplier<String> thing = (Supplier<String> & Serializable) () -> {
            return "HI";
        };
        for(Method method : thing.getClass().getDeclaredMethods()) {
            System.out.println(method);
        }
        SomeObject obj = new SomeObject();
        ExecutionSupplier<String> something = obj::supply;
        Method method = something.getClass().getDeclaredMethod("writeReplace");
        method.setAccessible(true);
        SerializedLambda lambda = (SerializedLambda) method.invoke(something);
        System.out.println(lambda.getImplMethodKind());
        System.out.println(lambda.getCapturedArgCount());
        System.out.println(lambda.getImplClass() + "." + lambda.getImplMethodName() + lambda.getImplMethodSignature());
    }

    public static class SomeObject {
        public String supply() {
            return "Hi!";
        }
    }
}
