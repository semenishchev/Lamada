package cc.olek.lamada.tests;

import java.util.UUID;
import java.util.function.Supplier;

public interface AnUniqueObject {
    long sayHi(String text, long time);
    void doSomething();
    String getName();
    UUID getUUID();
    void setName(String name);
    <T> T doInside(Supplier<T> run);
}
