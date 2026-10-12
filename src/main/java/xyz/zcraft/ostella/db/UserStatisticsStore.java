package xyz.zcraft.ostella.db;

import xyz.zcraft.ostella.db.data.UserStatRecord;

import java.sql.*;
import java.time.Duration;
import java.util.*;

public class UserStatisticsStore {
    public static void createTablesIfNeeded(Connection connection) throws Exception {
        String userStatSql = """
                CREATE TABLE IF NOT EXISTS user_statistics_osu (
                    user_id BIGINT NOT NULL,
                    pp REAL NOT NULL,
                    global_rank BIGINT NOT NULL,
                    accuracy REAL NOT NULL,
                    play_count BIGINT NOT NULL,
                    play_time BIGINT NOT NULL,
                    total_score BIGINT NOT NULL,
                    total_hits BIGINT NOT NULL,
                    updated_at INTEGER NOT NULL,
                
                    PRIMARY KEY (user_id)
                );
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(userStatSql);
            statement.executeUpdate("UPDATE user_statistics_osu SET updated_at = COALESCE(unixepoch(updated_at) * 1000, 0) WHERE typeof(updated_at) = 'text'");
            statement.executeUpdate("DELETE FROM user_statistics_osu WHERE user_id <= 0 OR global_rank <= 0 OR pp <= 0");
            statement.execute("CREATE INDEX IF NOT EXISTS user_statistics_pp ON user_statistics_osu(pp)");
            statement.execute("CREATE INDEX IF NOT EXISTS user_statistics_rank ON user_statistics_osu(global_rank)");
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM user_statistics_osu")) {
                if (rows.next() && rows.getLong(1) > 0) return;
            }
        }
        // Import existing snapshots only when initializing an empty database.
        var snapshots = new ArrayList<com.google.gson.JsonObject>();
        try (var stream = UserStatisticsStore.class.getResourceAsStream("/whatif-osu-snapshot.json")) {
            if (stream != null) {
                try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                    snapshots.add(com.google.gson.JsonParser.parseReader(reader).getAsJsonObject());
                }
            }
        }
        var legacy = java.nio.file.Path.of("data", "whatif-osu-snapshot.json");
        if (java.nio.file.Files.isRegularFile(legacy)) {
            snapshots.add(com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(legacy)).getAsJsonObject());
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO user_statistics_osu VALUES (?, ?, ?, 0, 0, 0, 0, 0, ?)
                ON CONFLICT(user_id) DO UPDATE SET pp=excluded.pp, global_rank=excluded.global_rank,
                    updated_at=excluded.updated_at WHERE excluded.updated_at > user_statistics_osu.updated_at
                """)) {
            for (var snapshot : snapshots) {
                if (!"osu".equals(snapshot.get("mode").getAsString())) continue;
                long captured = java.time.Instant.parse(snapshot.get("updatedAt").getAsString()).toEpochMilli();
                for (var element : snapshot.getAsJsonArray("samples")) {
                    var point = element.getAsJsonObject();
                    long time = point.has("observedAt") ? point.get("observedAt").getAsLong() : captured;
                    if (time <= 0) time = captured;
                    long id = point.get("userId").getAsLong(), rank = point.get("rank").getAsLong();
                    double pp = point.get("pp").getAsDouble();
                    if (id <= 0 || rank <= 0 || !Double.isFinite(pp) || pp <= 0 || time > System.currentTimeMillis())
                        continue;
                    insert.setLong(1, id);
                    insert.setDouble(2, pp);
                    insert.setLong(3, rank);
                    insert.setLong(4, time);
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    public static List<Long> getSampleUidsByAge(Duration age) {
        SqliteDatabase.ensureInitialized();
        final long limit = System.currentTimeMillis() - age.toMillis();
        String sql = """
                        SELECT user_id
                        FROM user_statistics_osu
                        WHERE updated_at < ?;
                """;
        List<Long> ids = new LinkedList<>();
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getLong("user_id"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query group members", e);
        }
        return ids;
    }


    public static void updateStat(UserStatRecord record) {
        if (record != null && record.valid()) updateStat(List.of(record));
    }

    public static void updateStat(List<UserStatRecord> records) {
        String sql = """
                INSERT INTO user_statistics_osu(user_id, pp, global_rank, accuracy, play_count, play_time, total_score, total_hits, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    pp = excluded.pp,
                    global_rank = excluded.global_rank,
                    accuracy = excluded.accuracy,
                    play_count = excluded.play_count,
                    play_time = excluded.play_time,
                    total_score = excluded.total_score,
                    total_hits = excluded.total_hits,
                    updated_at = excluded.updated_at
                WHERE excluded.updated_at >= user_statistics_osu.updated_at
                """;

        try (var conn = SqliteDatabase.getConnection();
             var statement = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            try {
                for (var record : records) {
                    if (record == null || !record.valid()) continue;
                    statement.setLong(1, record.userId());
                    statement.setDouble(2, record.pp());
                    statement.setLong(3, record.globalRank());
                    statement.setDouble(4, record.accuracy());
                    statement.setLong(5, record.playCount());
                    statement.setLong(6, record.playTime());
                    statement.setLong(7, record.totalScore());
                    statement.setLong(8, record.totalHits());
                    statement.setLong(9, record.updatedAt());
                    statement.addBatch();
                }

                statement.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Set<UserStatRecord> getRecordsAroundPp(Double pp) {
        if ((pp == null || !Double.isFinite(pp) || pp <= 0))
            throw new IllegalArgumentException("PP must be a positive number");
        String sqlUpper = """
                SELECT * FROM user_statistics_osu
                WHERE pp >= ?
                ORDER BY pp
                LIMIT 2
                """;

        String sqlLower = """
                SELECT * FROM user_statistics_osu
                WHERE pp < ?
                ORDER BY pp DESC
                LIMIT 2
                """;

        final Set<UserStatRecord> records = new HashSet<>();

        try (var conn = SqliteDatabase.getConnection();
             var upper = conn.prepareStatement(sqlUpper);
             var lower = conn.prepareStatement(sqlLower)) {
            conn.setAutoCommit(false);
            upper.setDouble(1, pp);
            lower.setDouble(1, pp);

            var upperResult = upper.executeQuery();
            while (upperResult.next()) {
                records.add(new UserStatRecord(
                        upperResult.getLong("user_id"),
                        upperResult.getDouble("pp"),
                        upperResult.getLong("global_rank"),
                        upperResult.getDouble("accuracy"),
                        upperResult.getLong("play_count"),
                        upperResult.getLong("play_time"),
                        upperResult.getLong("total_score"),
                        upperResult.getLong("total_hits"),
                        upperResult.getLong("updated_at")
                ));
            }

            var lowerResult = lower.executeQuery();
            while (lowerResult.next()) {
                records.add(new UserStatRecord(
                        lowerResult.getLong("user_id"),
                        lowerResult.getDouble("pp"),
                        lowerResult.getLong("global_rank"),
                        lowerResult.getDouble("accuracy"),
                        lowerResult.getLong("play_count"),
                        lowerResult.getLong("play_time"),
                        lowerResult.getLong("total_score"),
                        lowerResult.getLong("total_hits"),
                        lowerResult.getLong("updated_at")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return records;
    }

    public static Set<UserStatRecord> getRecordsAroundRank(Long rank) {
        if ((rank == null || rank <= 0))
            throw new IllegalArgumentException("Rank must be a positive number");
        String sqlUpper = """
                SELECT * FROM user_statistics_osu
                WHERE global_rank <= ?
                GROUP BY global_rank
                HAVING updated_at = MAX(updated_at)
                ORDER BY global_rank DESC
                LIMIT 2
                """;

        String sqlLower = """
                SELECT * FROM user_statistics_osu
                WHERE global_rank >= ?
                GROUP BY global_rank
                HAVING updated_at = MAX(updated_at)
                ORDER BY global_rank
                LIMIT 2
                """;

        final Set<UserStatRecord> records = new HashSet<>();

        try (var conn = SqliteDatabase.getConnection();
             var upper = conn.prepareStatement(sqlUpper);
             var lower = conn.prepareStatement(sqlLower)) {
            conn.setAutoCommit(false);
            upper.setLong(1, rank);
            lower.setLong(1, rank);

            var upperResult = upper.executeQuery();
            while (upperResult.next()) {
                records.add(new UserStatRecord(
                        upperResult.getLong("user_id"),
                        upperResult.getDouble("pp"),
                        upperResult.getLong("global_rank"),
                        upperResult.getDouble("accuracy"),
                        upperResult.getLong("play_count"),
                        upperResult.getLong("play_time"),
                        upperResult.getLong("total_score"),
                        upperResult.getLong("total_hits"),
                        upperResult.getLong("updated_at")
                ));
            }

            var lowerResult = lower.executeQuery();
            while (lowerResult.next()) {
                records.add(new UserStatRecord(
                        lowerResult.getLong("user_id"),
                        lowerResult.getDouble("pp"),
                        lowerResult.getLong("global_rank"),
                        lowerResult.getDouble("accuracy"),
                        lowerResult.getLong("play_count"),
                        lowerResult.getLong("play_time"),
                        lowerResult.getLong("total_score"),
                        lowerResult.getLong("total_hits"),
                        lowerResult.getLong("updated_at")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return records;
    }

}
