package cc.olek.lamada.util;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

public class WeakSet<T> implements Set<T> {
    private static final Object EXISTS = new Object();
    private final WeakHashMap<T, Object> map = new WeakHashMap<>();

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    @Override
    public @NotNull Iterator<T> iterator() {
        return map.keySet().iterator();
    }

    @Override
    public @NotNull Object[] toArray() {
        return map.keySet().toArray();
    }

    @Override
    public @NotNull <T1> T1[] toArray(@NotNull T1[] a) {
        return map.keySet().toArray(a);
    }

    @Override
    public boolean add(T t) {
        return map.put(t, EXISTS) == null;
    }

    @Override
    public boolean remove(Object o) {
        return map.remove(o) != null;
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        return map.keySet().containsAll(c);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends T> c) {
        boolean success = false;
        for(T t : c) {
            if(this.add(t)) {
                success = true;
            }
        }
        return success;
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        return map.keySet().retainAll(c);
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        return map.keySet().removeAll(c);
    }

    @Override
    public void clear() {
        map.clear();
    }
}
