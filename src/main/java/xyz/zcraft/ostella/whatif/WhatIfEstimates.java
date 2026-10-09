package xyz.zcraft.ostella.whatif;

import java.time.Duration;
import java.time.Instant;

/** Both standalone conversion and BP projections use one immutable backend snapshot. */
public final class WhatIfEstimates {
    private WhatIfEstimates() {}

    public static Result estimate(WhatIfService.State state, Double pp, Long rank, Instant now) {
        validate(pp, rank);
        var model = state.model();
        String status;
        double resultPp;
        double resultRank;
        if (pp != null) {
            resultPp = pp;
            if (pp > model.first().pp()) { status = "HIGH_PP"; resultRank = model.first().rank(); }
            else if (pp < model.last().pp()) { status = "LOW_PP"; resultRank = model.last().rank(); }
            else { status = "COVERED"; resultRank = model.rankAtPp(pp); }
        } else {
            resultRank = rank;
            if (rank < model.first().rank()) { status = "LOW_RANK"; resultPp = model.first().pp(); }
            else if (rank > model.last().rank()) { status = "HIGH_RANK"; resultPp = model.last().pp(); }
            else { status = "COVERED"; resultPp = model.ppAtRank(rank); }
        }
        return new Result("osu", status, resultPp, resultRank, state.snapshot().updatedAt().toString(),
                stale(state, now), model.samples().size(), model.first().rank(), model.last().rank(),
                model.last().pp(), model.first().pp());
    }

    public static void validate(Double pp, Long rank) {
        if ((pp == null) == (rank == null) || (pp != null && (!Double.isFinite(pp) || pp <= 0))
                || (rank != null && rank <= 0)) {
            throw new IllegalArgumentException("请提供一个正数 pp 或正整数 rank，不能同时指定。");
        }
    }

    public static RankProjection project(WhatIfService.State state, double before, double after,
                                         Long actualRank, Instant now) {
        var model = state.model();
        Long rank;
        String status;
        if (Math.abs(after - before) < 1e-9) { rank = actualRank; status = "UNCHANGED"; }
        else if (after > model.first().pp()) { rank = model.first().rank(); status = "HIGH_PP"; }
        else if (after < model.last().pp()) { rank = model.last().rank(); status = "LOW_PP"; }
        else {
            double predicted = model.rankAtPp(after);
            if (actualRank != null && actualRank > 0) {
                if (before >= model.last().pp() && before <= model.first().pp())
                    predicted *= actualRank / model.rankAtPp(before);
                predicted = Math.min(actualRank, predicted);
            }
            rank = Math.max(1, Math.round(predicted));
            status = "COVERED";
        }
        return new RankProjection(rank, status, state.snapshot().updatedAt().toString(), stale(state, now));
    }

    private static boolean stale(WhatIfService.State state, Instant now) {
        return Duration.between(state.snapshot().updatedAt(), now).compareTo(Duration.ofHours(48)) >= 0;
    }

    public record Result(String mode, String status, double pp, double rank, String updatedAt,
                         boolean stale, int sampleCount, long minRank, long maxRank, double minPp, double maxPp) {}
    public record RankProjection(Long rank, String status, String updatedAt, boolean stale) {}
}
