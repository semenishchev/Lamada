package cc.olek.lamada.util;

public class Exceptions {
    public static RuntimeException wrap(Throwable t) {
        if(t instanceof RuntimeException r) {
            return r;
        }
        return new RuntimeException(t);
    }

    public static RuntimeException runtime(String message) {
        return new NullPointerException(message);
    }
}
