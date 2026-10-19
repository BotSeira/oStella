package xyz.zcraft.ostella.db;

import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.util.MusicMetaUtil;
import xyz.zcraft.osu.model.Beatmapset;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SetMetaStore {
    private static final int IMPORT_BATCH_SIZE = 250;

    public static void createTablesIfNeeded(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS beatmapsets (
                        id INTEGER PRIMARY KEY,
                        artist TEXT NOT NULL, artist_unicode TEXT,
                        title TEXT NOT NULL, title_unicode TEXT,
                        artist_normalized TEXT NOT NULL, title_normalized TEXT NOT NULL,
                        artist_romanized TEXT NOT NULL, title_romanized TEXT NOT NULL,
                        title_base TEXT NOT NULL, title_base_romanized TEXT NOT NULL,
                        source TEXT, status TEXT NOT NULL, ranked_at TEXT,
                        play_count INTEGER DEFAULT 0, favourite_count INTEGER DEFAULT 0,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_song ON beatmapsets(title_normalized, artist_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_title ON beatmapsets(title_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_song_romanized ON beatmapsets(title_romanized, artist_romanized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_title_base ON beatmapsets(title_base)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_title_base_romanized ON beatmapsets(title_base_romanized)");
        }
    }

    private static void backfillKeys(Connection connection) throws SQLException {
        long lastId = -1;
        while (true) {
            List<Metadata> page = new ArrayList<>();
            try (var select = connection.prepareStatement("SELECT * FROM beatmapsets WHERE id > ? ORDER BY id LIMIT 250")) {
                select.setLong(1, lastId);
                try (var rs = select.executeQuery()) {
                    while (rs.next()) page.add(read(rs));
                }
            }
            if (page.isEmpty()) return;
            try (var update = connection.prepareStatement("""
                    UPDATE beatmapsets SET artist_normalized=?, title_normalized=?, artist_romanized=?,
                    title_romanized=?, title_base=?, title_base_romanized=? WHERE id=?
                    """)) {
                for (Metadata row : page) {
                    update.setString(1, MusicMetaUtil.normalizeOrFallback(row.artistUnicode(), row.artist()));
                    update.setString(2, MusicMetaUtil.normalizeOrFallback(row.titleUnicode(), row.title()));
                    update.setString(3, MusicMetaUtil.normalize(row.artist()));
                    update.setString(4, MusicMetaUtil.normalize(row.title()));
                    update.setString(5, MusicMetaUtil.baseTitle(MusicMetaUtil.normalizeOrFallback(row.titleUnicode(), row.title())));
                    update.setString(6, MusicMetaUtil.baseTitle(row.title()));
                    update.setLong(7, row.id());
                    update.addBatch();
                }
                update.executeBatch();
            }
            lastId = page.getLast().id();
        }
    }

    public static int upsertBeatmapset(List<Beatmapset> sets) {
        return upsertBeatmapset(sets, System.currentTimeMillis());
    }

    public static int upsertBeatmapset(List<Beatmapset> sets, long observedAt) {
        return upsertObserved(sets.stream().map(set -> new ObservedSet(set, observedAt)).toList());
    }

    private static int upsertObserved(List<ObservedSet> sets) {
        if (sets.isEmpty()) return 0;
        try (var connection = SqliteDatabase.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int count = upsertObserved(connection, sets);
                connection.commit();
                return count;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store beatmapset metadata", e);
        }
    }

    static int upsertObserved(Connection connection, List<ObservedSet> sets) throws SQLException {
        String sql = """
                INSERT INTO beatmapsets(id, artist, artist_unicode, title, title_unicode,
                    artist_normalized, title_normalized, source, status, ranked_at,
                    play_count, favourite_count, updated_at, artist_romanized, title_romanized,
                    title_base, title_base_romanized)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    artist=excluded.artist, artist_unicode=excluded.artist_unicode,
                    title=excluded.title, title_unicode=excluded.title_unicode,
                    artist_normalized=excluded.artist_normalized, title_normalized=excluded.title_normalized,
                    source=excluded.source, status=excluded.status, ranked_at=excluded.ranked_at,
                    play_count=excluded.play_count, favourite_count=excluded.favourite_count,
                    updated_at=excluded.updated_at, artist_romanized=excluded.artist_romanized,
                    title_romanized=excluded.title_romanized, title_base=excluded.title_base,
                    title_base_romanized=excluded.title_base_romanized
                WHERE CAST(excluded.updated_at AS INTEGER) >= CAST(beatmapsets.updated_at AS INTEGER)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            for (ObservedSet observed : sets) {
                Beatmapset set = observed.set();
                if (set == null || set.getId() == null || set.getId() <= 0
                        || set.getArtist() == null || set.getTitle() == null || set.getStatus() == null) {
                    throw new IllegalArgumentException("Beatmapset requires a positive id, artist, title and status");
                }
                String artist = MusicMetaUtil.normalizeOrFallback(set.getArtistUnicode(), set.getArtist());
                String title = MusicMetaUtil.normalizeOrFallback(set.getTitleUnicode(), set.getTitle());
                statement.setLong(1, set.getId());
                statement.setString(2, set.getArtist());
                statement.setString(3, set.getArtistUnicode());
                statement.setString(4, set.getTitle());
                statement.setString(5, set.getTitleUnicode());
                statement.setString(6, artist);
                statement.setString(7, title);
                statement.setString(8, set.getSource());
                statement.setString(9, set.getStatus());
                statement.setString(10, set.getRankedDate());
                statement.setLong(11, set.getPlayCount() == null ? 0 : set.getPlayCount());
                statement.setLong(12, set.getFavouriteCount() == null ? 0 : set.getFavouriteCount());
                statement.setLong(13, observed.observedAt());
                statement.setString(14, MusicMetaUtil.normalize(set.getArtist()));
                statement.setString(15, MusicMetaUtil.normalize(set.getTitle()));
                statement.setString(16, MusicMetaUtil.baseTitle(title));
                statement.setString(17, MusicMetaUtil.baseTitle(set.getTitle()));
                statement.addBatch();
            }
            int count = 0;
            for (int result : statement.executeBatch()) {
                if (result == Statement.EXECUTE_FAILED) throw new SQLException("Metadata batch failed");
                count += result == Statement.SUCCESS_NO_INFO ? 1 : result;
            }
            return count;
        }
    }

    public static int importFromCachedJson() throws IOException {
        List<ObservedSet> batch = new ArrayList<>(IMPORT_BATCH_SIZE);
        int[] count = {0};
        CacheService.walkBeatmapsetCache((set, modifiedAt) -> {
            batch.add(new ObservedSet(set, modifiedAt));
            if (batch.size() == IMPORT_BATCH_SIZE) {
                count[0] += upsertObserved(batch);
                batch.clear();
            }
        });
        return count[0] + upsertObserved(batch);
    }

    public static List<Metadata> candidates(String title, String artist, int limit) {
        try (var connection = SqliteDatabase.getConnection()) {
            return candidates(connection, title, artist, limit);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to search beatmapset metadata", e);
        }
    }

    public static List<Metadata> candidates(Connection connection, String title, String artist, int limit) throws SQLException {
        if (limit < 1 || limit > 501) throw new IllegalArgumentException("Candidate limit must be 1..501");
        String key = MusicMetaUtil.normalize(title);
        if (key.isEmpty()) return List.of();
        String base = MusicMetaUtil.baseTitle(title);
        try (var statement = connection.prepareStatement("""
                SELECT * FROM beatmapsets
                WHERE status='ranked' AND (title_normalized=? OR title_romanized=?
                    OR title_base=? OR title_base_romanized=?)
                ORDER BY CASE WHEN title_normalized=? OR title_romanized=? THEN 0 ELSE 1 END,
                    CASE WHEN artist_normalized=? OR artist_romanized=? THEN 0 ELSE 1 END,
                    favourite_count DESC, play_count DESC, id
                LIMIT ?
                """)) {
            statement.setString(1, key);
            statement.setString(2, key);
            statement.setString(3, base);
            statement.setString(4, base);
            statement.setString(5, key);
            statement.setString(6, key);
            statement.setString(7, MusicMetaUtil.normalize(artist));
            statement.setString(8, MusicMetaUtil.normalize(artist));
            statement.setInt(9, limit);
            List<Metadata> result = new ArrayList<>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) result.add(read(rs));
            }
            return result;
        }
    }

    private static Metadata read(ResultSet rs) throws SQLException {
        return new Metadata(rs.getLong("id"), rs.getString("artist"), rs.getString("artist_unicode"),
                rs.getString("title"), rs.getString("title_unicode"), rs.getString("status"),
                rs.getLong("play_count"), rs.getLong("favourite_count"));
    }

    record ObservedSet(Beatmapset set, long observedAt) {
    }

    public record Metadata(long id, String artist, String artistUnicode, String title,
                           String titleUnicode, String status, long playCount, long favouriteCount) {
    }
}
