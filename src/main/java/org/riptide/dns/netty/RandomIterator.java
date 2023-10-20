package org.riptide.dns.netty;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * An infinite iterator that will return random items
 * from the given list.
 *
 * @param <T> the type of elements in the list
 */
public class RandomIterator<T> implements Iterable<T> {
    private final List<T> items;
    private final Random random;

    public RandomIterator(final List<T> coll) {
        items = new ArrayList<>(coll);
        random = new Random();
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public synchronized T next() {
                return items.get(random.nextInt(items.size()));
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Cannot remove from " + RandomIterator.class);
            }
        };
    }
}
