package xyz.zcraft.ostella.whatif;

import xyz.zcraft.ostella.db.data.UserStatRecord;

import java.time.Duration;
import java.time.Instant;

public final class WhatIfEstimates {
    public static Result estimate(RankPpModel model, Double pp, Long rank, Instant now) {
        validate(pp, rank);
        String status;
        double resultPp;
        double resultRank;
        if (pp != null) {
            resultPp = pp;
            if (pp > model.first().pp()) {
                status = "HIGH_PP";
                resultRank = model.first().globalRank();
            } else if (pp < model.last().pp()) {
                status = "LOW_PP";
                resultRank = model.last().globalRank();
            } else {
                status = "COVERED";
                resultRank = model.rankAtPp(pp);
            }
        } else {
            resultRank = rank;
            if (rank < model.first().globalRank()) {
                status = "LOW_RANK";
                resultPp = model.first().pp();
            } else if (rank > model.last().globalRank()) {
                status = "HIGH_RANK";
                resultPp = model.last().pp();
            } else {
                status = "COVERED";
                resultPp = model.ppAtRank(rank);
            }
        }
        Instant updatedAt = Instant.ofEpochMilli(model.samples().stream().mapToLong(UserStatRecord::updatedAt).min().orElse(0));
        return new Result("osu", status, resultPp, resultRank, updatedAt.toString(),
                Duration.between(updatedAt, now).compareTo(Duration.ofHours(48)) >= 0, model.samples().size(),
                model.first().globalRank(), model.last().globalRank(),
                model.last().pp(), model.first().pp());
    }

    public static void validate(Double pp, Long rank) {
        if ((pp == null) == (rank == null) || (pp != null && (!Double.isFinite(pp) || pp <= 0))
                || (rank != null && rank <= 0)) {
            throw new IllegalArgumentException("请提供一个正数 pp 或正整数 rank，不能同时指定。");
        }
    }

    public static RankProjection project(RankPpModel model, double before, double after, Long actualRank) {
        Long rank;
        String status;
        if (Math.abs(after - before) < 1e-9) {
            rank = actualRank;
            status = "UNCHANGED";
        } else if (after > model.first().pp()) {
            rank = model.first().globalRank();
            status = "HIGH_PP";
        } else if (after < model.last().pp()) {
            rank = model.last().globalRank();
            status = "LOW_PP";
        } else {
            double predicted = model.rankAtPp(after);
            if (actualRank != null && actualRank > 0) {
                if (before >= model.last().pp() && before <= model.first().pp())
                    predicted *= actualRank / model.rankAtPp(before);
                predicted = Math.min(actualRank, predicted);
            }
            rank = Math.max(1, Math.round(predicted));
            status = "COVERED";
        }
        return new RankProjection(rank, status);
    }

    public record Result(String mode, String status, double pp, double rank, String updatedAt, boolean stale,
                         int sampleCount,
                         long minRank, long maxRank, double minPp, double maxPp) {
    }

    public record RankProjection(Long rank, String status) {
    }
}
