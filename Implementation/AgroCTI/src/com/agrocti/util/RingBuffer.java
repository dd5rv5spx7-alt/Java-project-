package com.agrocti.util;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;

/** A generic fixed-capacity buffer that forgets its oldest item when full. Thread-safe. */
public class RingBuffer<T> {

    private final LinkedList<T> items = new LinkedList<>();
    private final int capacity;

    public RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized void add(T item) {
        items.addLast(item);
        if (items.size() > capacity) {
            items.removeFirst();
        }
    }

    public synchronized List<T> select(Predicate<? super T> test) {
        List<T> out = new ArrayList<>();
        for (T t : items) {
            if (test.test(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public synchronized List<T> latest(int n) {
        int from = Math.max(0, items.size() - n);
        return new ArrayList<>(items.subList(from, items.size()));
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized void clear() {
        items.clear();
    }
}
