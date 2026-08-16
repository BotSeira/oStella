package xyz.zcraft.ostella.service;

import xyz.zcraft.ostella.data.ScoreId;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.ostella.util.TokenManager;
import xyz.zcraft.osu.model.BeatmapExtended;
import xyz.zcraft.osu.model.Beatmapset;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;
import xyz.zcraft.osu.model.User;
import xyz.zcraft.osu.parser.data.replay.OsuReplay;
import xyz.zcraft.osu.parser.data.replay.ReplayInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class LocalScoreService {
    private static final long LOCAL_ID_FLOOR = 100_000_000_000_000L;
    private static final long LOCAL_ID_RANGE = 900_000_000_000_000L;
    private static final long WINDOWS_EPOCH_TICKS = 621_355_968_000_000_000L;

    private static final List<LegacyMod> LEGACY_MODS = List.of(
            new LegacyMod(1, "NF"),
            new LegacyMod(2, "EZ"),
            new LegacyMod(4, "TD"),
            new LegacyMod(8, "HD"),
            new LegacyMod(16, "HR"),
            new LegacyMod(32, "SD"),
            new LegacyMod(64, "DT"),
            new LegacyMod(128, "RX"),
            new LegacyMod(256, "HT"),
            new LegacyMod(512, "NC"),
            new LegacyMod(1024, "FL"),
            new LegacyMod(2048, "AT"),
            new LegacyMod(4096, "SO"),
            new LegacyMod(8192, "AP"),
            new LegacyMod(16384, "PF"),
            new LegacyMod(1 << 29, "V2"),
            new LegacyMod(1 << 30, "MR")
    );

    private final TokenManager tokenManager;

    public LocalScoreService(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public synchronized StoredLocalScore store(byte[] replayBytes, OsuReplay replay) {
        long localId = allocateId(replayBytes);
        long internalId = -localId;

        Optional<Path> existingReplay = CacheService.getReplayCache(internalId);
        if (existingReplay.isPresent() && sameReplay(existingReplay.get(), replayBytes)) {
            try {
                Optional<Score> existingScore = CacheService.getScoreJsonCache(internalId);
                if (existingScore.isPresent()) {
                    return new StoredLocalScore(ScoreId.format(internalId), existingScore.get());
                }
            } catch (IOException e) {
                throw new ApiException(ErrorCode.SCORE_FETCH_FAILED, "Failed to read local score", e);
            }
        }

        Score score = buildScore(internalId, replay);
        try {
            CacheService.transferReplay(internalId, replayBytes);
            CacheService.cacheScoreJson(score);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.REPLAY_UPLOAD_FAILED, "Failed to persist local replay", e);
        }

        return new StoredLocalScore(ScoreId.format(internalId), score);
    }

    private long allocateId(byte[] replayBytes) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(replayBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }

        long seed = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
        long candidate = LOCAL_ID_FLOOR + seed % LOCAL_ID_RANGE;
        for (long offset = 0; offset < 10_000; offset++) {
            long id = LOCAL_ID_FLOOR + (candidate - LOCAL_ID_FLOOR + offset) % LOCAL_ID_RANGE;
            Optional<Path> existing = CacheService.getReplayCache(-id);
            if (existing.isEmpty() || sameReplay(existing.get(), replayBytes)) {
                return id;
            }
        }
        throw new ApiException(ErrorCode.REPLAY_UPLOAD_FAILED, "Unable to allocate a local score ID");
    }

    private boolean sameReplay(Path path, byte[] replayBytes) {
        try {
            return Arrays.equals(Files.readAllBytes(path), replayBytes);
        } catch (IOException e) {
            return false;
        }
    }

    private Score buildScore(long internalId, OsuReplay replay) {
        if (replay.gameMode() != 0) {
            throw new ApiException(ErrorCode.REPLAY_PARSE_FAILED, "Only osu!standard local replays are supported");
        }

        BeatmapExtended beatmap = OsuAPI.getBeatmapByChecksum(tokenManager.getTokenData(), replay.beatmapHash());
        if (beatmap == null) {
            throw new ApiException(ErrorCode.NO_BEATMAP_FOUND, "No online beatmap matches this replay");
        }

        Beatmapset beatmapset = OsuAPI.getBeatmapset(tokenManager.getTokenData(), beatmap.getBeatmapsetId());
        if (beatmapset == null) {
            throw new ApiException(ErrorCode.NO_BEATMAPSET_FOUND, "No beatmapset matches this replay");
        }
        beatmap.setBeatmapset(beatmapset);

        ReplayInfo replayInfo = replay.replayInfo();
        User user = replayInfo != null && replayInfo.userId() != null && replayInfo.userId() > 0
                ? OsuAPI.getUser(tokenManager.getTokenData(), replayInfo.userId())
                : OsuAPI.getUser(tokenManager.getTokenData(), replay.playerName());
        if (user == null) {
            user = fallbackUser(replay, replayInfo);
        }

        Score.ScoreStatistics statistics = replayInfo != null && replayInfo.statistics() != null
                ? replayInfo.statistics()
                : legacyStatistics(replay);
        Score.ScoreStatistics maximumStatistics = replayInfo != null && replayInfo.maximumStatistics() != null
                ? replayInfo.maximumStatistics()
                : new Score.ScoreStatistics();
        List<Mod> mods = replayInfo != null && replayInfo.mods() != null
                ? replayInfo.mods()
                : legacyMods(replay.mods());

        Score score = new Score();
        score.setId(internalId);
        score.setBeatmapId(beatmap.getId());
        score.setBeatmap(beatmap);
        score.setBeatmapset(beatmapset);
        score.setUserId(user.getId());
        score.setUser(user);
        score.setRank(replayInfo != null && replayInfo.rank() != null && !replayInfo.rank().isBlank()
                ? replayInfo.rank()
                : legacyRank(replay));
        score.setMods(mods);
        score.setStatistics(statistics);
        score.setMaximumStatistics(maximumStatistics);
        score.setAccuracy(accuracy(statistics));
        score.setTotalScore(Integer.toUnsignedLong(replay.totalScore()));
        score.setClassicTotalScore(Integer.toUnsignedLong(replay.totalScore()));
        score.setLegacyTotalScore(Integer.toUnsignedLong(replay.totalScore()));
        score.setTotalScoreWithoutMods(replayInfo == null ? null : replayInfo.totalScoreWithoutMods());
        score.setMaxCombo((long) Short.toUnsignedInt(replay.maxCombo()));
        score.setIsPerfectCombo(replay.perfectCombo());
        score.setLegacyPerfect(replay.perfectCombo());
        score.setHasReplay(true);
        score.setReplay(true);
        score.setRanked(false);
        score.setPreserve(false);
        score.setProcessed(false);
        score.setPassed(!"F".equalsIgnoreCase(score.getRank()));
        score.setRulesetId(0L);
        score.setType("solo_score");
        score.setEndedAt(toIsoTimestamp(replay.timestamp()));
        score.setLegacyScoreId(replay.legacyScoreId() > 0 ? replay.legacyScoreId() : null);
        return score;
    }

    private User fallbackUser(OsuReplay replay, ReplayInfo replayInfo) {
        User user = new User();
        long userId = replayInfo != null && replayInfo.userId() != null ? replayInfo.userId() : 0L;
        user.setId(userId);
        user.setUsername(replay.playerName());
        user.setCountryCode("XX");
        user.setAvatarUrl("https://a.ppy.sh/" + userId);
        return user;
    }

    private Score.ScoreStatistics legacyStatistics(OsuReplay replay) {
        Score.ScoreStatistics statistics = new Score.ScoreStatistics();
        statistics.put("great", (long) Short.toUnsignedInt(replay.count300()));
        statistics.put("ok", (long) Short.toUnsignedInt(replay.count100()));
        statistics.put("meh", (long) Short.toUnsignedInt(replay.count50()));
        statistics.put("miss", (long) Short.toUnsignedInt(replay.countMiss()));
        return statistics;
    }

    private List<Mod> legacyMods(int bits) {
        List<Mod> mods = new ArrayList<>();
        LEGACY_MODS.forEach(legacyMod -> {
            if ((bits & legacyMod.flag()) != 0) {
                String acronym = legacyMod.acronym();
                if (("DT".equals(acronym) && (bits & 512) != 0)
                        || ("SD".equals(acronym) && (bits & 16384) != 0)) {
                    return;
                }
                Mod mod = new Mod();
                mod.setAcronym(acronym);
                mods.add(mod);
            }
        });
        return List.copyOf(mods);
    }

    private double accuracy(Score.ScoreStatistics statistics) {
        long great = statistics.getOrDefault("great", 0L);
        long ok = statistics.getOrDefault("ok", 0L);
        long meh = statistics.getOrDefault("meh", 0L);
        long miss = statistics.getOrDefault("miss", 0L);
        long total = great + ok + meh + miss;
        return total == 0 ? 0.0 : (great * 300.0 + ok * 100.0 + meh * 50.0) / (total * 300.0);
    }

    private String legacyRank(OsuReplay replay) {
        double total = Short.toUnsignedInt(replay.count300()) + Short.toUnsignedInt(replay.count100())
                + Short.toUnsignedInt(replay.count50()) + Short.toUnsignedInt(replay.countMiss());
        if (total == 0) return "F";

        double greatRatio = Short.toUnsignedInt(replay.count300()) / total;
        double mehRatio = Short.toUnsignedInt(replay.count50()) / total;
        int misses = Short.toUnsignedInt(replay.countMiss());
        String rank;
        if (greatRatio == 1.0) rank = "X";
        else if (greatRatio > 0.9 && mehRatio <= 0.01 && misses == 0) rank = "S";
        else if ((greatRatio > 0.8 && misses == 0) || greatRatio > 0.9) rank = "A";
        else if ((greatRatio > 0.7 && misses == 0) || greatRatio > 0.8) rank = "B";
        else if (greatRatio > 0.6) rank = "C";
        else rank = "D";

        boolean silver = (replay.mods() & (8 | 1024)) != 0;
        if (silver && "X".equals(rank)) return "XH";
        if (silver && "S".equals(rank)) return "SH";
        return rank;
    }

    private String toIsoTimestamp(long windowsTicks) {
        try {
            long millis = Math.subtractExact(windowsTicks, WINDOWS_EPOCH_TICKS) / 10_000L;
            return Instant.ofEpochMilli(millis).toString();
        } catch (ArithmeticException | DateTimeException e) {
            return Instant.now().toString();
        }
    }

    public record StoredLocalScore(String id, Score score) {
    }

    private record LegacyMod(int flag, String acronym) {
    }
}
