package xyz.zcraft.ostella.addpp;

import java.util.*;

/**
 * Reweights the available BP window, keeping the account's unobserved remainder fixed.
 */
public final class BpProjection {
    private BpProjection() {
    }

    public static Result project(double currentPp, List<Play> best, double pp, int count, Long mapId, int window) {
        if (!Double.isFinite(currentPp) || currentPp < 0 || !Double.isFinite(pp) || pp < 0
                || count < 1 || count > 100 || window < 1 || (mapId != null && count != 1))
            throw new IllegalArgumentException("无效的 PP 估算参数。");
        Map<Long, Double> unique = new HashMap<>();
        for (Play play : best) {
            if (play.beatmapId() <= 0 || !Double.isFinite(play.pp()) || play.pp() < 0)
                throw new IllegalStateException("BP 数据不完整，请稍后重试。");
            unique.merge(play.beatmapId(), play.pp(), Math::max);
        }
        var old = unique.values().stream().sorted(Comparator.reverseOrder()).limit(window).toList();
        double oldWeight = weighted(old, window);
        boolean replaced = mapId != null && unique.containsKey(mapId);
        double effective = mapId == null ? pp : Math.max(pp, unique.getOrDefault(mapId, 0.0));
        if (mapId != null) unique.remove(mapId);
        record Candidate(double pp, boolean added, int order) {
        }
        var candidates = new ArrayList<Candidate>();
        unique.values().forEach(value -> candidates.add(new Candidate(value, false, 0)));
        for (int i = 0; i < count; i++) candidates.add(new Candidate(effective, true, i));
        // Existing ties precede the additions; identical PP has the same weighted total either way.
        candidates.sort(Comparator.comparingDouble(Candidate::pp).reversed()
                .thenComparing(Candidate::added).thenComparingInt(Candidate::order));
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) if (candidates.get(i).added()) positions.add(i + 1);
        double newWeight = weighted(candidates.stream().map(Candidate::pp).toList(), window);
        double change = Math.max(0, newWeight - oldWeight);
        return new Result(currentPp, currentPp + change, change, List.copyOf(positions), replaced);
    }

    private static double weighted(List<Double> values, int window) {
        double result = 0, weight = 1;
        for (int i = 0; i < Math.min(values.size(), window); i++, weight *= .95) result += values.get(i) * weight;
        return result;
    }

    public record Play(long beatmapId, double pp) {
    }

    public record Result(double before, double after, double change, List<Integer> positions, boolean replaced) {
    }
}
