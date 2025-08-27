package cc.olek.lamada;

import cc.olek.lamada.asm.LambdaImpl;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages own relations with other targets.
 * Stores own implementation numbers and keeps track of others' implementation numbers
 * Used to map lambda implementations and distributed object classes
 * <p>
 * While lambdas are dynamic and may appear during runtime of both targets, objects should be synchronised
 * before any communication happens. That's how sending target knows that receiving target has been restarted/is a new one
 * </p>
 * General idea is - we don't map numbers which we produce, others map our numbers, and we map the numbers to which other targets are producing
 * @param <Target> Type of target which is managed
 */
public abstract class RemoteTargetManager<Target> {
    protected final DistributedExecutor<Target> executor;
    private final Int2ObjectMap<DistributedObject<?, ?, Target>> int2object = Int2ObjectMaps.synchronize(new Int2ObjectOpenHashMap<>());
    protected final AtomicInteger counter = new AtomicInteger(1);

    public RemoteTargetManager(DistributedExecutor<Target> executor) {
        this.executor = executor;
    }

    public abstract LambdaImpl reconstruct(Target target, int number);

    public abstract void registerImplementation(Target sender, short lambdaNum, LambdaImpl impl);

    public DistributedObject<?, ?, Target> getObject(short objectNum) {
        return int2object.get(objectNum);
    }

    public void sync() {
        for(DistributedObject<?, ?, Target> value : executor.targetClassToObject.values()) {
            short num = getNewObjNumber(value);
            int2object.put(num, value);
            value.setNumber(num);
        }
    }

    public short getNewObjNumber(DistributedObject<?, ?, Target> impl) {
        int newNum = counter.getAndIncrement();
        if(newNum == 0) {
            throw new IllegalStateException("0 object number is reserved for StaticExecutor");
        }
        return (short) newNum;
    }

    public abstract SubmissionResult getOrSubmitOwn(Target sendTo, LambdaImpl impl);

    protected abstract short getNewImplNumber(LambdaImpl impl);

    public abstract LambdaImpl requestMissingImplementation(Target sender, short lambdaNum);

    public abstract boolean isTargetAvailable(Target target);

    @SuppressWarnings("unchecked")
    public SubmissionResult onSerialize(Object sendTo, LambdaImpl impl) {
        return getOrSubmitOwn((Target) sendTo, impl);
    }

    public void resync() {
        sync(); // the standard implementation rescrambles numbers
    }

    public void shutdown() {}

    public record SubmissionResult(boolean existedBefore, short lambdaNum) {}
}
