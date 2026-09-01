package xyz.zcraft.ostella.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class WeightedRandom<T> {
    private final List<T> items = new ArrayList<>();
    private final List<Integer> cumulative = new ArrayList<>();
    private final Random random = new Random();

    public void add(T item, int weight) {
        items.add(item);
        int last = cumulative.isEmpty() ? 0 : cumulative.getLast();
        cumulative.add(last + weight);
    }

    public T next() {
        int total = cumulative.getLast();
        int r = random.nextInt(total);

        for (int i = 0; i < cumulative.size(); i++) {
            if (r < cumulative.get(i)) {
                return items.get(i);
            }
        }

        throw new IllegalStateException();
    }

    public int size() {
        return items.size();
    }
}
