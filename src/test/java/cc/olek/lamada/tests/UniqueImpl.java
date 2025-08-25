package cc.olek.lamada.tests;

import java.util.UUID;
import java.util.function.Supplier;

public class UniqueImpl implements AnUniqueObject {
    private final UUID id;
    private String name;

    public UniqueImpl(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public long sayHi(String text, long time) {
        long diff = System.currentTimeMillis() - time;
        System.out.println("Took " + diff + "ms to say " + text);
        return diff;
    }

    @Override
    public void doSomething() {
        System.out.println("Did something");
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public UUID getUUID() {
        return this.id;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public <T> T doInside(Supplier<T> run) {
        return run.get();
    }
}
