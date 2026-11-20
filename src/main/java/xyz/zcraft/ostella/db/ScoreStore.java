package xyz.zcraft.ostella.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import xyz.zcraft.ostella.data.ScoreRecord;
import xyz.zcraft.ostella.network.Router;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.osu.model.Mod;
import xyz.zcraft.osu.model.Score;

import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ScoreStore {
    public static final String METRICS_REVISION = "2026-10";
    private static final Gson GSON = new Gson();
    private static final Type MODS_TYPE =
            new TypeToken<List<Mod>>() {
            }.getType();
    private static final int USER_BATCH_SIZE = 500;

    private static final String SELECT_SCORES = """
            SELECT s.*, m.total_score, m.pp, m.accuracy,
                   m.grade, m.metrics_revision, m.calculated_at
            FROM scores s
            LEFT JOIN score_metrics m ON s.score_id = m.score_id
            """;
    private static final int IMPORT_BATCH_SIZE = 250;
    private static final String SCORE_UPSERT_SQL = """
            INSERT INTO scores (
                score_id, source, user_id, beatmap_id, played_at,
                mods_json, statistics_json, maximum_statistics_json,
                max_combo, passed, ranked, build_id,
                beatmap_md5, replay_hash, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(score_id) DO UPDATE SET
                played_at = COALESCE(excluded.played_at, scores.played_at),
                mods_json = excluded.mods_json,
                statistics_json = excluded.statistics_json,
                maximum_statistics_json = COALESCE(
                    excluded.maximum_statistics_json,
                    scores.maximum_statistics_json
                ),
                max_combo = COALESCE(excluded.max_combo, scores.max_combo),
                passed = COALESCE(excluded.passed, scores.passed),
                ranked = COALESCE(excluded.ranked, scores.ranked),
                build_id = COALESCE(excluded.build_id, scores.build_id),
                beatmap_md5 = COALESCE(
                    excluded.beatmap_md5, scores.beatmap_md5
                ),
                replay_hash = COALESCE(
                    excluded.replay_hash, scores.replay_hash
                ),
                updated_at = excluded.updated_at
            WHERE scores.source = excluded.source
              AND scores.user_id = excluded.user_id
              AND scores.beatmap_id = excluded.beatmap_id
            """;
    private static final String METRICS_UPSERT_SQL = """
            INSERT INTO score_metrics (
                score_id, total_score, pp, accuracy, grade,
                metrics_revision, calculated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(score_id) DO UPDATE SET
                total_score = excluded.total_score,
                pp = excluded.pp,
                accuracy = excluded.accuracy,
                grade = excluded.grade,
                metrics_revision = excluded.metrics_revision,
                calculated_at = excluded.calculated_at
            """;

    private ScoreStore() {
    }

    public static void createTablesIfNeeded(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS scores (
                        score_id INTEGER PRIMARY KEY,
                        source TEXT NOT NULL
                            CHECK (source IN ('online', 'local')),
                        user_id INTEGER NOT NULL,
                        beatmap_id INTEGER NOT NULL,
                        played_at INTEGER,
                    
                        mods_json TEXT NOT NULL DEFAULT '[]',
                        statistics_json TEXT NOT NULL DEFAULT '{}',
                        maximum_statistics_json TEXT,
                    
                        max_combo INTEGER,
                        passed INTEGER
                            CHECK (passed IS NULL OR passed IN (0, 1)),
                        ranked INTEGER CHECK ( ranked IS NULL OR ranked IN (0, 1) ),
                    
                        build_id INTEGER,
                        beatmap_md5 TEXT,
                        replay_hash TEXT,
                    
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                    
                        CHECK (
                            (source = 'online' AND score_id > 0)
                            OR
                            (source = 'local' AND score_id < 0)
                        )
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS score_metrics (
                        score_id INTEGER PRIMARY KEY
                            REFERENCES scores(score_id) ON DELETE CASCADE,
                    
                        total_score INTEGER NOT NULL,
                        pp REAL,
                        accuracy REAL,
                        grade TEXT,
                    
                        metrics_revision TEXT NOT NULL,
                        calculated_at INTEGER NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_scores_map_user
                    ON scores(beatmap_id, user_id)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_scores_user_map
                    ON scores(user_id, beatmap_id)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_score_metrics_pp
                    ON score_metrics(pp DESC)
                    """);
        }
    }

    public static List<ScoreRecord> getScoresForUsers(long beatmapId, List<Long> userIds, SortType sort) {
        Objects.requireNonNull(userIds);
        Objects.requireNonNull(sort);

        List<Long> ids = userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (ids.isEmpty()) return List.of();

        List<ScoreRecord> results = new ArrayList<>();

        try (Connection connection = SqliteDatabase.getConnection()) {
            for (int start = 0; start < ids.size(); start += USER_BATCH_SIZE) {
                List<Long> batch = ids.subList(
                        start, Math.min(start + USER_BATCH_SIZE, ids.size()));

                String placeholders = String.join(
                        ",", Collections.nCopies(batch.size(), "?"));

                String sql = SELECT_SCORES
                        + " WHERE s.beatmap_id = ?"
                        + " AND s.user_id IN (" + placeholders + ")";

                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, beatmapId);

                    for (int i = 0; i < batch.size(); i++) {
                        statement.setLong(i + 2, batch.get(i));
                    }

                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            results.add(read(rs));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query beatmap scores", e);
        }

        Comparator<ScoreRecord> byPp = Comparator.comparing(ScoreRecord::pp, Comparator.nullsLast(Comparator.reverseOrder()));

        Comparator<ScoreRecord> byScore = Comparator.comparing(ScoreRecord::totalScore, Comparator.nullsLast(Comparator.reverseOrder()));

        Comparator<ScoreRecord> comparator = switch (sort) {
            case PP -> byPp.thenComparing(byScore);
            case SCORE -> byScore.thenComparing(byPp);
        };

        results.sort(comparator.thenComparing(ScoreRecord::scoreId, Comparator.reverseOrder()));

        return results;
    }

    public static List<ScoreRecord> getUserBests(long userId, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("Limit cannot be negative");
        }
        if (limit == 0) return List.of();

        String sql = """
                WITH ranked AS (
                    SELECT
                        s.*,
                        m.total_score,
                        m.pp,
                        m.accuracy,
                        m.grade,
                        m.metrics_revision,
                        m.calculated_at,
                
                        ROW_NUMBER() OVER (
                            PARTITION BY s.beatmap_id
                            ORDER BY m.pp DESC,
                                     m.total_score DESC,
                                     s.score_id DESC
                        ) AS rn
                    FROM scores s
                    JOIN score_metrics m ON s.score_id = m.score_id
                    WHERE s.user_id = ?
                      AND s.passed IS NOT 0
                      AND m.pp IS NOT NULL
                )
                SELECT *
                FROM ranked
                WHERE rn = 1
                ORDER BY pp DESC, total_score DESC, score_id DESC
                LIMIT ?
                """;

        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setInt(2, limit);

            List<ScoreRecord> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(read(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query user best scores", e);
        }
    }

    public static void deleteScore(long scoreId, long userId) {
        String sql = """
                DELETE FROM scores
                WHERE score_id = ? AND user_id = ?
                """;

        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, scoreId);
            statement.setLong(2, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete score", e);
        }
    }

    public static ScoreRecord getScore(long scoreId) {
        String sql = SELECT_SCORES + " WHERE s.score_id = ?";

        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, scoreId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get score", e);
        }
    }

    private static ScoreRecord read(ResultSet rs) throws SQLException {
        List<Mod> mods = GSON.fromJson(rs.getString("mods_json"), MODS_TYPE);
        Score.ScoreStatistics statistics = GSON.fromJson(
                rs.getString("statistics_json"), Score.ScoreStatistics.class);

        String maximumJson = rs.getString("maximum_statistics_json");
        Score.ScoreStatistics maximum = maximumJson == null
                ? null
                : GSON.fromJson(maximumJson, Score.ScoreStatistics.class);

        return new ScoreRecord(
                rs.getLong("score_id"),
                rs.getString("source"),
                rs.getLong("user_id"),
                rs.getLong("beatmap_id"),
                nullableLong(rs, "played_at"),
                mods == null ? List.of() : mods,
                statistics == null ? new Score.ScoreStatistics() : statistics,
                maximum,
                nullableLong(rs, "max_combo"),
                nullableBoolean(rs, "passed"),
                nullableBoolean(rs, "ranked"),
                nullableLong(rs, "build_id"),
                rs.getString("beatmap_md5"),
                rs.getString("replay_hash"),
                nullableLong(rs, "total_score"),
                nullableDouble(rs, "pp"),
                nullableDouble(rs, "accuracy"),
                rs.getString("grade"),
                rs.getString("metrics_revision"),
                nullableLong(rs, "calculated_at"),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String name) throws SQLException {
        double value = rs.getDouble(name);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String name)
            throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value != 0;
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT);
        else ps.setLong(index, value);
    }

    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) ps.setNull(index, Types.DOUBLE);
        else ps.setDouble(index, value);
    }

    private static void setBoolean(PreparedStatement ps, int index, Boolean value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value ? 1 : 0);
    }

    public static int importFromCachedJson() throws IOException {
        List<Score> batch = new ArrayList<>(IMPORT_BATCH_SIZE);
        AtomicInteger count = new AtomicInteger(0);
        CacheService.walkScoreCache((score, _) -> {
            Router.ensurePp(score);
            batch.add(score);
            if (batch.size() == IMPORT_BATCH_SIZE) {
                count.addAndGet(upsertScores(batch));
                batch.clear();
            }
        });
        return count.addAndGet(upsertScores(batch));
    }

    public static void upsertScore(Score score) {
        upsertScores(List.of(score));
    }

    public static void upsertScore(Score score, String revision) {
        upsertScores(List.of(score), revision);
    }

    public static int upsertScores(List<Score> scores) {
        return upsertScores(scores, METRICS_REVISION);
    }

    public static int upsertScores(List<Score> scores, String revision) {
        Objects.requireNonNull(scores);
        Objects.requireNonNull(revision);

        if (revision.isBlank()) {
            throw new IllegalArgumentException("Metrics revision is blank");
        }
        if (scores.isEmpty()) return 0;

        List<ScoreRecord> records = scores.stream()
                .map(score -> ScoreRecord.fromScore(score, revision))
                .toList();

        try (Connection connection = SqliteDatabase.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement scoreStmt = connection.prepareStatement(SCORE_UPSERT_SQL);
                 PreparedStatement metricsStmt = connection.prepareStatement(METRICS_UPSERT_SQL)) {

                for (ScoreRecord record : records) {
                    bindScore(scoreStmt, record);
                    scoreStmt.addBatch();
                }

                int sum = 0;
                for (int result : scoreStmt.executeBatch()) {
                    sum += result;
                    if (result == Statement.EXECUTE_FAILED || result == 0) {
                        throw new SQLException("Score upsert failed or ID conflict");
                    }
                }

                for (ScoreRecord record : records) {
                    if (record.totalScore() == null) continue;

                    bindMetrics(metricsStmt, record, revision);
                    metricsStmt.addBatch();
                }

                metricsStmt.executeBatch();
                connection.commit();

                return sum;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert scores", e);
        }
    }

    private static void bindScore(PreparedStatement statement, ScoreRecord record) throws SQLException {
        statement.setLong(1, record.scoreId());
        statement.setString(2, record.source());
        statement.setLong(3, record.userId());
        statement.setLong(4, record.beatmapId());
        setLong(statement, 5, record.playedAt());

        statement.setString(6, GSON.toJson(record.mods()));
        statement.setString(7, GSON.toJson(record.statistics()));
        statement.setString(8, record.maximumStatistics() == null
                ? null : GSON.toJson(record.maximumStatistics()));

        setLong(statement, 9, record.maxCombo());
        setBoolean(statement, 10, record.passed());
        setBoolean(statement, 11, record.ranked());
        setLong(statement, 12, record.buildId());

        statement.setString(13, record.beatmapMd5());
        statement.setString(14, record.replayHash());
        statement.setLong(15, record.createdAt());
        statement.setLong(16, record.updatedAt());
    }

    private static void bindMetrics(
            PreparedStatement statement,
            ScoreRecord record,
            String revision
    ) throws SQLException {
        statement.setLong(1, record.scoreId());
        statement.setLong(2, record.totalScore());
        setDouble(statement, 3, record.pp());
        setDouble(statement, 4, record.accuracy());
        statement.setString(5, record.grade());
        statement.setString(6, revision);
        statement.setLong(7, record.calculatedAt());
    }

    public enum SortType {
        SCORE, PP
    }
}
