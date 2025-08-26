package cc.olek.lamada.tests;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.LoopbackRemoteTargetManager;
import cc.olek.lamada.defaults.FunctionalDistributedObject;
import cc.olek.lamada.sender.LoopbackSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class LamadaTests {
    private static DistributedExecutor<String> a;
    private static DistributedExecutor<String> b;
    private static FunctionalDistributedObject<UUID, AnUniqueObject, String> uniqueObjectsA;
    private static FunctionalDistributedObject<UUID, AnUniqueObject, String> uniqueObjectsB;
    public static AnUniqueObject implA;
    public static AnUniqueObject implB;

    @BeforeAll
    public static void createExecutors() {
        MixedLoopbackSender<String> sender = new MixedLoopbackSender<>();
        Executor executor = Executors.newSingleThreadExecutor();
        a = new DistributedExecutor<>("a");
        b = new DistributedExecutor<>("b");
        sender.register(a);
        sender.register(b);
        a.setSender(sender);
        b.setSender(sender);
        a.setExecutor(executor);
        b.setExecutor(executor);
        a.setTargetManager(new LoopbackRemoteTargetManager<>(a));
        b.setTargetManager(new LoopbackRemoteTargetManager<>(b));
        uniqueObjectsA = new FunctionalDistributedObject<>(a, AnUniqueObject.class, UUID.class, true);
        a.sync();
        uniqueObjectsB = new FunctionalDistributedObject<>(b, AnUniqueObject.class, UUID.class, true);
        b.sync();
        implA = new UniqueImpl(UUID.randomUUID(), "SubjectA");
        implB = new UniqueImpl(UUID.randomUUID(), "SubjectB");
        System.out.println("Impl A UUID: " + implA.getUUID());
        System.out.println("Impl B UUID: " + implB.getUUID());
        uniqueObjectsA.setSerialization(AnUniqueObject::getUUID, uuid -> {
            System.out.println("Running A: " + uuid);
            if(uuid.equals(implA.getUUID())) return implA;
            return null;
        });
        uniqueObjectsB.setSerialization(AnUniqueObject::getUUID, uuid -> {
            System.out.println("Running B: " + uuid);
            if(uuid.equals(implB.getUUID())) return implB;
            return null;
        });
    }

    @AfterAll
    public static void shutdown() {
        a.shutdown();
        b.shutdown();
    }

    @Test
    public void testBasicCreation() {
        DistributedExecutor<String> aNew = getNew();
        assertEquals("1", aNew.getOwnTarget());
        aNew.shutdown();
    }

    @Test
    public void testStaticExecution() {
        DistributedExecutor<String> aNew = getNew();
        assertDoesNotThrow(() -> {
            aNew.run("2", () -> {
                System.out.println("Static call");
            }).join();
        });
        long time = System.nanoTime();
        assertTrue(aNew.runMethod("2", () -> System.nanoTime() > time).join());
        assertEquals("Hello, World!", aNew.runMethod("2", () -> "Hello, World!").join());
        String sent = "This is a sent value";
        assertEquals(sent, aNew.runMethod("2", () -> sent).join());
        aNew.shutdown();
    }

    @Test
    public void testUniqueMethodCall() {
        assertEquals(
            implB.getName(),
            uniqueObjectsA.runMethod("b", implB.getUUID(), AnUniqueObject::getName).join()
        );
        assertEquals(
            implA.getName(),
            uniqueObjectsB.runMethod("a", implA.getUUID(), AnUniqueObject::getName).join()
        );
    }

    @Test
    public void testUniqueStaticCall() {
        assertEquals(
            "Hi " + implA.getName(),
            uniqueObjectsB.runMethod(
                "a", implA.getUUID(),
                LamadaTests::doSomething
            ).join()
        );
    }

    @Test
    public void testUniqueComputedValue() {
        assertEquals(
            implA.getName() + ":" + implB.getName(),
            uniqueObjectsA.runMethod(
                "b", implB.getUUID(),
                playerB -> implA.getName() + ":" + playerB.getName()
            ).join()
        );

        assertEquals(
            implB.getName() + ":" + implA.getName(),
            uniqueObjectsB.runMethod(
                "a", implA.getUUID(),
                playerA -> implB.getName() + ":" + playerA.getName()
            ).join()
        );
    }

    @Test
    public void testUniqueInnerLambdaSimple() {
        assertEquals("Test", uniqueObjectsB.runMethod(
            "a", implA.getUUID(),
            playerA -> implB.doInside(() -> "Test")
        ).join());
    }

    @Test
    public void testUniqueInnerLambdaSimpler() {
        String test = "Test";
        assertEquals(test + ":" + implB.getUUID(), uniqueObjectsB.runMethod(
            "a", implA.getUUID(),
            playerA -> implB.doInside(() -> test) + ":" + implB.getUUID()
        ).join());
    }

    public record Something(String str, AnUniqueObject other) {

    }

    @Test
    public void testObjTransfer() {
        Something something = new Something("Test", implA);
        Something other = a.runMethod("b", () -> {
            return new Something(something.str() + "!", something.other());
        }).join();
        assertEquals(something.other.getUUID(), other.other.getUUID());
    }

    @Test
    public void testComplexObjTransfer() {
        Map<UUID, AnUniqueObject> objects = new HashMap<>();
        objects.put(implA.getUUID(), implA);
        objects.put(implB.getUUID(), implB);
        var other = a.runMethod("b", objects::values).join();
        assertEquals(objects.size(), other.size());
        for(AnUniqueObject anUniqueObject : other) {
            assertTrue(objects.containsKey(anUniqueObject.getUUID()));
        }
    }

    @Test
    public void testUniqueInnerLambda() {
        String fromHere = "d";
        assertEquals("Test:" + fromHere + ":" + implA.getUUID(), uniqueObjectsB.runMethod(
            "a", implA.getUUID(),
            playerA -> {
                System.out.println("Inside");
                String addition = "st";
                return implB.doInside(() -> "Te" + addition + ":" + fromHere) + ":" + playerA.getUUID();
            }
        ).join());
    }

    @Test
    public void testAsync() {
        DistributedExecutor<String> aNew = getNew();
        Map<String, Object> results = new ConcurrentHashMap<>();
        Set<String> checkAgainst = new HashSet<>();
        for(int i = 0; i < 10; i++) {
            int val = i;
            checkAgainst.add(String.valueOf(val));
            new Thread(() -> {
                aNew.runMethod("1", () -> String.valueOf(val)).thenAccept(res -> {
                    results.put(res, new Object());
                });
            }).start();
        }
        aNew.shutdown();
        System.out.println("Async test: " + checkAgainst.size() + ":" + results.size());
        System.out.println("\t" + results.keySet() + " -> " + checkAgainst);
        assertTrue(results.keySet().containsAll(checkAgainst));
    }

    @Test
    public void testShutdown() {
        DistributedExecutor<String> aNew = getNew();
        String expected = "Hi!";
        AtomicReference<String> got = new AtomicReference<>(null);
        aNew.runMethod("1", () -> expected).thenAccept(got::set);
        aNew.shutdown();
        assertEquals(expected, got.get());
    }

    public static String doSomething(AnUniqueObject obj) {
        return "Hi " + obj.getName();
    }

    public static DistributedExecutor<String> getNew() {
        DistributedExecutor<String> executor = new DistributedExecutor<>("1");
        executor.setTargetManager(new LoopbackRemoteTargetManager<>(executor));
        executor.setSender(new LoopbackSender<>());
        executor.sync();
        return executor;
    }
}
