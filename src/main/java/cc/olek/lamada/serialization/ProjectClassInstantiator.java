package cc.olek.lamada.serialization;

import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import org.objenesis.instantiator.ObjectInstantiator;
import org.objenesis.strategy.InstantiatorStrategy;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.util.*;

public class ProjectClassInstantiator implements InstantiatorStrategy {
    private static final Map<Class<?>, Class<?>> collectionImplMap = Map.of(
        Map.class, HashMap.class,
        List.class, ArrayList.class,
        Deque.class, LinkedList.class,
        Set.class, HashSet.class,
        Collection.class, ArrayList.class,
        Iterable.class, ArrayList.class
    );
    private static final Set<Class<?>> noImplCache = new HashSet<>();
    private static final Map<Class<?>, Class<?>> implCache = new HashMap<>();
    private final DefaultInstantiatorStrategy defaultInstantiatorStrategy;
    private final StdInstantiatorStrategy stdInstantiatorStrategy;
    public ProjectClassInstantiator() {
        this.defaultInstantiatorStrategy = new DefaultInstantiatorStrategy();
        this.stdInstantiatorStrategy = new StdInstantiatorStrategy();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> ObjectInstantiator<T> newInstantiatorOf(Class<T> aClass) {
        if(aClass.isRecord()) {
            return stdInstantiatorStrategy.newInstantiatorOf(aClass);
        }
        checker: if(!noImplCache.contains(aClass)) {
            Class<?> implClass = implCache.get(aClass);
            if(implClass == null) {
                implClass = searchInterfaces(aClass, collectionImplMap);
            }

            if(implClass == null) {
                noImplCache.add(aClass);
                break checker;
            }
            return defaultInstantiatorStrategy.newInstantiatorOf(implClass);
        }

        return defaultInstantiatorStrategy.newInstantiatorOf(aClass);
    }

    private static Class<?> searchInterfaces(Class<?> objClass, Map<Class<?>, Class<?>> interfaceToImplMap) {
        if(objClass == Object.class) return null;
        Class<?> implClass = implCache.get(objClass);
        if(implClass != null) return implClass;
        for(Class<?> anInterface : objClass.getInterfaces()) {
            implClass = interfaceToImplMap.get(anInterface);
            if(implClass != null) {
                implCache.put(objClass, implClass);
                return implClass;
            }
        }
        return searchInterfaces(objClass.getSuperclass(), interfaceToImplMap);
    }
}