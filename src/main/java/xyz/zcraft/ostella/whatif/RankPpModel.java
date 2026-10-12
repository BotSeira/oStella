package xyz.zcraft.ostella.whatif;

import xyz.zcraft.ostella.db.data.UserStatRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public record RankPpModel(List<UserStatRecord> samples) {
    public static RankPpModel from(Collection<UserStatRecord> input) {
        if (input == null) throw new IllegalArgumentException("缺少排名样本。");
        var sorted = input.stream()
                .filter(s -> s != null && s.valid())
                .sorted(Comparator.comparingLong(UserStatRecord::globalRank)
                        .thenComparing(Comparator.comparingDouble(UserStatRecord::pp).reversed()))
                .toList();
        var monotone = new ArrayList<UserStatRecord>();
        for (UserStatRecord sample : sorted) {
            if (monotone.isEmpty() || (sample.globalRank() > monotone.getLast().globalRank()
                    && sample.pp() < monotone.getLast().pp())) {
                monotone.add(sample);
            }
        }
        if (monotone.size() < 2) throw new IllegalArgumentException("有效排名样本不足。");
        return new RankPpModel(List.copyOf(monotone));
    }

    private static double interpolate(double x, double x1, double x2, double y1, double y2) {
        if (x == x1) return y1;
        if (x == x2) return y2;
        double weight = (Math.log(x) - Math.log(x1)) / (Math.log(x2) - Math.log(x1));
        return Math.exp(Math.log(y1) + weight * (Math.log(y2) - Math.log(y1)));
    }

    public UserStatRecord first() {
        return samples.getFirst();
    }

    public UserStatRecord last() {
        return samples.getLast();
    }

    public double ppAtRank(long rank) {
        if (rank < first().globalRank() || rank > last().globalRank()) {
            throw new IllegalArgumentException("排名超出样本范围。");
        }
        int low = 1, high = samples.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (samples.get(mid).globalRank() < rank) low = mid + 1;
            else high = mid;
        }
        UserStatRecord a = samples.get(low - 1), b = samples.get(low);
        return interpolate(rank, a.globalRank(), b.globalRank(), a.pp(), b.pp());
    }

    public double rankAtPp(double pp) {
        if (!Double.isFinite(pp) || pp < last().pp() || pp > first().pp()) {
            throw new IllegalArgumentException("PP 超出样本范围。");
        }
        int low = 1, high = samples.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (samples.get(mid).pp() > pp) low = mid + 1;
            else high = mid;
        }
        UserStatRecord a = samples.get(low - 1), b = samples.get(low);
        return interpolate(pp, a.pp(), b.pp(), a.globalRank(), b.globalRank());
    }
}
