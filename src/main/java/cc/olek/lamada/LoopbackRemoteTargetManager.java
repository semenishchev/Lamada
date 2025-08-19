package cc.olek.lamada;

import cc.olek.lamada.asm.LambdaImpl;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ShortMap;
import it.unimi.dsi.fastutil.objects.Object2ShortMaps;
import it.unimi.dsi.fastutil.objects.Object2ShortOpenHashMap;

import java.util.concurrent.atomic.AtomicBoolean;

public class LoopbackRemoteTargetManager<Target> extends RemoteTargetManager<Target> {
    private final Object2ShortMap<String> impl2int = Object2ShortMaps.synchronize(new Object2ShortOpenHashMap<>());
    private final Int2ObjectMap<LambdaImpl> int2impl = Int2ObjectMaps.synchronize(new Int2ObjectOpenHashMap<>());
    public LoopbackRemoteTargetManager(DistributedExecutor<Target> executor) {
        super(executor);
    }

    @Override
    public LambdaImpl reconstruct(Target target, int number) {
        return int2impl.get(number);
    }

    @Override
    public void registerImplementation(Target sender, short lambdaNum, LambdaImpl impl) {
        int2impl.put(lambdaNum, impl);
    }

    @Override
    public SubmissionResult getOrSubmitOwn(Target sendTo, LambdaImpl impl) {
        AtomicBoolean existedBefore = new AtomicBoolean(true);
        short val = impl2int.computeIfAbsent(impl.implementation().className() + impl.implementation().methodName(), d -> {
            existedBefore.set(false);
            return (short) counter.getAndIncrement();
        });
        return new SubmissionResult(existedBefore.get(), val);
    }

    @Override
    protected short getNewImplNumber(LambdaImpl impl) {
        return 0;
    }

    @Override
    public LambdaImpl requestMissingImplementation(Target sender, short lambdaNum) {
        return null;
    }

    @Override
    public boolean isTargetAvailable(Target target) {
        return true;
    }

}
