package cc.olek.lamada.tests;

import cc.olek.lamada.DistributedExecutor;
import cc.olek.lamada.LoopbackRemoteTargetManager;
import cc.olek.lamada.sender.LoopbackSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BasicTests {
    @Test
    public void testBasicCreation() {
        DistributedExecutor<String> aNew = getNew();
        assertEquals("1", aNew.getOwnTarget());
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
    }

    @Test
    public void testInstanceExecution() {
        // todo: write unit test
    }

    public static DistributedExecutor<String> getNew() {
        DistributedExecutor<String> executor = new DistributedExecutor<>("1");
        executor.setTargetManager(new LoopbackRemoteTargetManager<>(executor));
        executor.setSender(new LoopbackSender<>());
        executor.sync();
        return executor;
    }
}
