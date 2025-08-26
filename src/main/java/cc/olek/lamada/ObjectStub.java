package cc.olek.lamada;

/**
 * Stubs an interface which is a unique distributed object. When code refers to an object which lives
 * on the sending JVM, receiving JVM will generate this class, where each method will transfer each invoke
 * to the sender
 */
public abstract class ObjectStub {
    protected Object key;
    protected Object target;
    protected DistributedObject<Object, ?, Object> object;
    public ObjectStub() {
        System.out.println("Object stub");
    }

    public void setup(DistributedObject<Object, ?, Object> object, Object key) {
        if(this.target != null) return;
        this.object = object;
        this.key = key;
    }

    public void setTarget(Object target) {
        this.target = target;
    }

    public Object sendSingleMethod(Object[] params, String methodNameDescriptor) {
        return object.runSingleMethod(this.target, this.key, methodNameDescriptor, params).join();
    }

    /**
     * Converts a unique (ObjectStub) object into a local one by fetching by the stub's key locally
     * @param unique Unique object
     * @return Local object
     * @param <T> Type of the object
     */
    @SuppressWarnings("unchecked")
    public static <T> T getLocal(T unique) {
        if(!(unique instanceof ObjectStub stub)) {
            throw new IllegalArgumentException("Object is not unique");
        }

        return (T) stub.object.fetch(stub.key);
    }

    public Object getKey() {
        return key;
    }

    public Object getTarget() {
        return target;
    }

    public DistributedObject<Object,?, Object> getObject() {
        return object;
    }
}
