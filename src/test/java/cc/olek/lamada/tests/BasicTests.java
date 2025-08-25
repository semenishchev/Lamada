package cc.olek.lamada.tests;

import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.io.Serializable;
import java.lang.invoke.LambdaMetafactory;
import java.lang.reflect.Method;
import java.util.function.Supplier;

public class BasicTests {
    @Test
    public void testNonExistentWriteReplace() {
        Supplier<String> thing = (Supplier<String> & Serializable) () -> {
            return "HI";
        };
        for(Method method : thing.getClass().getDeclaredMethods()) {
            System.out.println(method);
        }
    }
}
