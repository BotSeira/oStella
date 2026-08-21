package xyz.zcraft.ostella.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import lombok.SneakyThrows;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.data.TokenData;
import xyz.zcraft.ostella.cache.CacheControlRequest;
import xyz.zcraft.ostella.cache.CacheControlResult;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.osu.model.Score;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class CacheService {
    private static final Logger LOG = LogManager.getLogger(CacheService.class);
    private static final Path CACHE_PATH = Paths.get("data", "cache");

    private static final Path BEATMAP_CACHE = CACHE_PATH.resolve("beatmap");
    private static final Path IMAGE_CACHE = CACHE_PATH.resolve("image");
    private static final Path REPLAY_CACHE = CACHE_PATH.resolve("replay");

    private static final Path JSON_CACHE = CACHE_PATH.resolve("json");
    private static final Path SCORE_JSON_CACHE = JSON_CACHE.resolve("score");
//    private static final Path BEATMAP_JSON_CACHE = JSON_CACHE.resolve("beatmap");
//    private static final Path BEATMAPSET_JSON_CACHE = JSON_CACHE.resolve("beatmapset");

    private static final Path BEATMAPSET_CACHE = CACHE_PATH.resolve("beatmapset");

    private static final Gson GSON = new Gson();
    private static AsyncService executor = null;

    public static void initialize(AsyncService asyncService) throws IOException {
        if (executor != null) {
            throw new IllegalStateException("CacheService is already initialized!");
        }
        executor = asyncService;
        Files.createDirectories(BEATMAP_CACHE);
        Files.createDirectories(IMAGE_CACHE);
        Files.createDirectories(REPLAY_CACHE);
        Files.createDirectories(BEATMAPSET_CACHE);
        Files.createDirectories(SCORE_JSON_CACHE);
//        Files.createDirectories(BEATMAP_JSON_CACHE);
//        Files.createDirectories(BEATMAPSET_JSON_CACHE);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String extractExtension(String url) {
        // Need to deal with these kinds of URLs...
        // https://assets.ppy.sh/beatmaps/320118/covers/cover.jpg?1650632079
        // https://assets.ppy.sh/user-profile-covers/21445688/2934e792b3acd2f1a5d75caa8f1ee1fa06a52e2c6f4c0bd4457368db4770dc80.png
        // https://a.ppy.sh/21445688?1754893780.png

        if (url == null || url.isEmpty()) {
            return "png";
        }

        int lastDotIndex = url.lastIndexOf('.');
        int lastSlashIndex = url.lastIndexOf('/');

        if (lastDotIndex > lastSlashIndex) {

            String extension = url.substring(lastDotIndex + 1);

            int questionMarkIndex = extension.indexOf('?');
            if (questionMarkIndex != -1) {
                extension = extension.substring(0, questionMarkIndex);
            }

            return extension;
        }

        return "png";
    }

    public static Path getBeatmapPath(long id, boolean update) {
        Path beatmapPath = beatmapCachePath(id);
        if (!Files.exists(beatmapPath) || update) {
            try {
                LOG.debug("Caching beatmap {}", id);
                cacheBeatmapFile(id, beatmapPath);
                LOG.debug("Beatmap {} cached", id);
            } catch (IOException e) {
                LOG.error("Failed to download beatmap!", e);
                throw new RuntimeException("Failed to download beatmap!", e);
            }
        }

        return beatmapPath.toAbsolutePath();
    }

    public static Path getBeatmapPath(long id) {
        return getBeatmapPath(id, false);
    }

    private static Path beatmapCachePath(long id) {
        return BEATMAP_CACHE.resolve(id + ".osu");
    }

    private static void cacheBeatmapFile(long id, Path beatmapPath) throws IOException {
        Files.deleteIfExists(beatmapPath);
        Files.write(beatmapPath, executor.enqueueAsync(() -> OsuAPI.getBeatmapBytes(id)).join());
    }

    @NotNull
    @SneakyThrows(NoSuchAlgorithmException.class)
    private static String getFileName(String url) {
        String extension = extractExtension(url);

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));

        return bytesToHex(digest) + "." + extension;
    }

    public static String getImageSrc(String url) {
        final String fileName = getFileName(url);

        if (!Files.exists(IMAGE_CACHE.resolve(fileName))) {
            try {
                cacheImage(fileName, url);
                LOG.debug("Image {} cached", fileName);
            } catch (IOException e) {
                LOG.error("Failed to download image!", e);
                throw new RuntimeException("Failed to download image!", e);
            }
        }

        return "http://ostella-cache/"
                + IMAGE_CACHE.resolve(getFileName(url)).toAbsolutePath().toString().replace("\\", "/");
    }

    private static void cacheImage(String fileName, String url) throws IOException {
        Files.deleteIfExists(IMAGE_CACHE.resolve(fileName));
        Files.write(IMAGE_CACHE.resolve(fileName), Objects.requireNonNull(OsuAPI.getImageBytes(url)));
    }

    public static Path getImagePathFromFilename(String filename) {
        return IMAGE_CACHE.resolve(filename);
    }

    public static boolean cacheBeatmapsetFile(long id) {
        try (Stream<Path> list = Files.list(BEATMAPSET_CACHE)) {
            if (list.map(Path::getFileName)
                    .map(Path::toString)
                    .anyMatch(p -> p.equals(String.valueOf(id)) || p.startsWith(id + " ") || p.equals(id + ".osz"))
            ) {
                LOG.debug("Beatmapset {} is already cached, skipping", id);
                return true;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Path beatmapsetPath = BEATMAPSET_CACHE.resolve(id + ".osz");

        LOG.debug("Downloading beatmapset {} via Sayobot", id);
        if (!downloadSayobot(id, beatmapsetPath)) {
            LOG.warn("Switching to Nekoha");
            if (!downloadNekoha(id, beatmapsetPath)) {
                LOG.error("Failed to download beatmapset {} via both Sayobot and Nekoha!", id);
                return false;
            }
        }

        return true;
    }

    public static void extractBeatmapset(long id, OutputStream out) throws IOException {
        if (!cacheBeatmapsetFile(id)) {
            return;
        }

        Optional<Path> archive = prepareBeatmapsetArchive(id);
        if (archive.isPresent()) {
            Files.copy(archive.get(), out);
        }
    }

    public static Path getBeatmapsetArchivePath(long id) throws IOException {
        if (!cacheBeatmapsetFile(id)) {
            throw new IOException("Failed to cache beatmapset " + id);
        }

        return prepareBeatmapsetArchive(id)
                .orElseThrow(() -> new IOException("Cached beatmapset " + id + " has no archive or directory"));
    }

    private static Optional<Path> prepareBeatmapsetArchive(long id) throws IOException {
        Path archive = BEATMAPSET_CACHE.resolve(id + ".osz");
        if (Files.isRegularFile(archive)) {
            return Optional.of(archive);
        }

        return repackExtractedBeatmapset(id, archive);
    }

    private static synchronized Optional<Path> repackExtractedBeatmapset(long id, Path archive) throws IOException {
        if (Files.isRegularFile(archive)) {
            return Optional.of(archive);
        }

        Optional<Path> extractedBeatmapset = findExtractedBeatmapset(id);
        if (extractedBeatmapset.isEmpty()) {
            return Optional.empty();
        }

        Path folder = extractedBeatmapset.get();
        Path temporary = Files.createTempFile(BEATMAPSET_CACHE, id + "-", ".osz.tmp");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary));
                 Stream<Path> files = Files.walk(folder)) {
                output.setLevel(Deflater.BEST_COMPRESSION);
                Iterator<Path> iterator = files.filter(Files::isRegularFile).iterator();
                while (iterator.hasNext()) {
                    Path file = iterator.next();
                    output.putNextEntry(new ZipEntry(folder.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }

            try {
                Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.debug("Repacked extracted beatmapset {} to {}", folder, archive);
            return Optional.of(archive);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Optional<Path> findExtractedBeatmapset(long id) throws IOException {
        String exactName = String.valueOf(id);
        try (Stream<Path> entries = Files.list(BEATMAPSET_CACHE)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals(exactName) || name.startsWith(exactName + " ");
                    })
                    .findFirst();
        }
    }

    private static boolean downloadNekoha(long id, Path beatmapsetPath) {
        try (final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()) {
            String initialUrl = "https://mirror.nekoha.moe/api4/download/" + id;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(initialUrl))
                    .timeout(Duration.ofMinutes(3))
                    .GET()
                    .build();

            HttpResponse<InputStream> fileResponse = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (fileResponse.statusCode() == 200) {
                Files.copy(fileResponse.body(), beatmapsetPath, StandardCopyOption.REPLACE_EXISTING);
                LOG.debug("Beatmapset {} cached via Nekoha", beatmapsetPath);
                return true;
            } else {
                LOG.error("Failed to download beatmapset! Nekoha responded with status code: {}", fileResponse.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            LOG.error("Failed to download beatmapset!", e);
        }
        return false;
    }

    private static boolean downloadSayobot(long id, Path beatmapsetPath) {
        try (final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            String initialUrl = "https://dl.sayobot.cn/beatmaps/download/novideo/" + id;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(initialUrl))
                    .timeout(Duration.ofMinutes(3))
                    .GET()
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() == 301 || response.statusCode() == 302) {
                String dirtyLocation = response.headers().firstValue("Location")
                        .orElseThrow(() -> new RuntimeException("Sayobot redirected without a Location header"));

                String cleanLocation = dirtyLocation
                        .replace(" ", "%20")
                        .replace("[", "%5B")
                        .replace("]", "%5D")
                        .replace("^", "%5E");

                HttpRequest actualDownloadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(cleanLocation))
                        .GET()
                        .build();

                HttpResponse<InputStream> fileResponse = client.send(actualDownloadRequest, HttpResponse.BodyHandlers.ofInputStream());

                if (fileResponse.statusCode() == 200) {
                    Files.copy(fileResponse.body(), beatmapsetPath, StandardCopyOption.REPLACE_EXISTING);
                    LOG.debug("Beatmapset {} cached via Sayobot", beatmapsetPath);
                    return true;
                } else {
                    LOG.error("Failed to download beatmapset! Sayobot responded with status code: {}", fileResponse.statusCode());
                }
            }
        } catch (IOException | InterruptedException e) {
            LOG.error("Failed to download beatmapset!", e);
        }
        return false;
    }

    public static Path getReplayBlocking(TokenData tokenData, long id) throws IOException {
        Path replayPath = REPLAY_CACHE.resolve(id + ".osr");

        LOG.debug("Getting replay {}", id);

        Files.write(replayPath, OsuAPI.getReplayBytes(tokenData, id));

        LOG.debug("Replay {} is ready", id);

        return replayPath;
    }

    public static Optional<Path> getReplayCache(long id) {
        Path replayPath = REPLAY_CACHE.resolve(id + ".osr");
        if (Files.exists(replayPath)) {
            return Optional.of(replayPath);
        } else {
            return Optional.empty();
        }
    }

    public static Optional<Score> getScoreJsonCache(long id) throws IOException {
        if (!Files.exists(SCORE_JSON_CACHE.resolve(id + ".json"))) {
            return Optional.empty();
        }

        final JsonElement jsonElement = JsonParser.parseString(Files.readString(SCORE_JSON_CACHE.resolve(id + ".json")));
        return Optional.of(GSON.fromJson(jsonElement, Score.class));
    }

    public static void cacheScoreJson(Score score) throws IOException {
        Files.writeString(SCORE_JSON_CACHE.resolve(score.getId() + ".json"), GSON.toJson(score));
    }

    public static void transferReplay(Long id, byte[] bytes) throws IOException {
        Files.write(REPLAY_CACHE.resolve(id + ".osr"), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static boolean hasReplayCache(Long id) {
        return Files.exists(REPLAY_CACHE.resolve(id + ".osr"));
    }

    public static Optional<byte[]> extractFile(Long beatmapsetId, String fileName) {
        if (beatmapsetId == null || fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }

        String entryName = fileName.replace('\\', '/');
        try {
            Optional<Path> archivePath = prepareBeatmapsetArchive(beatmapsetId);
            if (archivePath.isEmpty()) {
                return Optional.empty();
            }

            try (ZipFile archive = new ZipFile(archivePath.get().toFile())) {
                ZipEntry entry = archive.getEntry(entryName);
                if (entry == null || entry.isDirectory()) {
                    return Optional.empty();
                }

                try (InputStream input = archive.getInputStream(entry)) {
                    return Optional.of(input.readAllBytes());
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to extract {} from beatmapset {}", fileName, beatmapsetId, e);
            return Optional.empty();
        }
    }

    public static CacheSummary summary() {
        try {
            AreaStats beatmaps = areaStats(BEATMAP_CACHE);
            AreaStats images = areaStats(IMAGE_CACHE);
            AreaStats replays = areaStats(REPLAY_CACHE);
            AreaStats scoreJson = areaStats(SCORE_JSON_CACHE);
            AreaStats beatmapsets = areaStats(BEATMAPSET_CACHE);
            return new CacheSummary(beatmaps, images, replays, scoreJson, beatmapsets);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect oStella cache", e);
        }
    }

    public static int clear(CacheArea area) {
        try {
            int removed = 0;
            if (area == CacheArea.BEATMAPS || area == CacheArea.ALL) removed += clearChildren(BEATMAP_CACHE);
            if (area == CacheArea.IMAGES || area == CacheArea.ALL) removed += clearChildren(IMAGE_CACHE);
            if (area == CacheArea.REPLAYS || area == CacheArea.ALL) removed += clearChildren(REPLAY_CACHE);
            if (area == CacheArea.SCORE_JSON || area == CacheArea.ALL) removed += clearChildren(SCORE_JSON_CACHE);
            if (area == CacheArea.BEATMAPSETS || area == CacheArea.ALL) removed += clearChildren(BEATMAPSET_CACHE);
            return removed;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clear oStella cache", e);
        }
    }

    public static CacheControlResult control(CacheControlRequest request) {
        if (request == null) throw new IllegalArgumentException("Cache control body is required");
        if (request.id() <= 0) throw new IllegalArgumentException("Cache id must be positive");
        String operation = normalizeOperation(request.operation());
        String type = normalizeType(request.type());
        List<Path> paths;
        try {
            paths = cachePaths(type, request.id());
            if ("DELETE".equals(operation)) {
                int removed = 0;
                for (Path path : paths) removed += deleteCachePath(path);
                return localResult(operation, type, request.id(), removed > 0 ? "DELETED" : "MISSING",
                        paths, null, null, removed > 1 ? "Removed " + removed + " cache entries" : null);
            }
            List<Path> existing = paths.stream().filter(Files::exists).toList();
            if (existing.isEmpty()) return localResult(operation, type, request.id(), "MISSING", paths,
                    null, null, null);
            if ("QUERY".equals(operation)) return localResult(operation, type, request.id(), "PRESENT",
                    existing, null, null, null);
            long bytes = 0;
            Instant latest = Instant.EPOCH;
            for (Path path : existing) {
                AreaStats stats = areaStats(path);
                bytes += stats.bytes();
                Instant modified = Files.getLastModifiedTime(path).toInstant();
                if (modified.isAfter(latest)) latest = modified;
            }
            return localResult(operation, type, request.id(), "PRESENT", existing, bytes,
                    latest.toString(), null);
        } catch (IOException e) {
            return localResult(operation, type, request.id(), "ERROR", List.of(), null, null, e.getMessage());
        }
    }

    public static CacheControlResult fetch(CacheControlRequest request, TokenData tokenData) {
        if (request == null) throw new IllegalArgumentException("Cache control body is required");
        if (request.id() <= 0) throw new IllegalArgumentException("Cache id must be positive");
        String type = normalizeType(request.type());
        try {
            CacheControlResult current = control(new CacheControlRequest("GET", type, request.id()));
            Path existingPath = existingCachePath(type, request.id());
            boolean reusable = existingPath != null
                    && (!"BEATMAPSET".equals(type) || Files.isRegularFile(existingPath));
            if (reusable && !current.nodes().isEmpty()
                    && "PRESENT".equals(current.nodes().getFirst().status())) {
                CacheControlResult.CacheNodeResult node = current.nodes().getFirst();
                return new CacheControlResult("FETCH", type, request.id(), List.of(
                        new CacheControlResult.CacheNodeResult(
                                node.node(), "PRESENT", node.path(), node.sizeBytes(), node.modifiedAt(),
                                "Already cached"
                        )
                ));
            }
            switch (type) {
                case "SCORE" -> cacheScoreJson(OsuAPI.getScore(tokenData, request.id()));
                case "BEATMAP" -> getBeatmapPath(request.id(), true);
                case "BEATMAPSET" -> getBeatmapsetArchivePath(request.id());
                case "REPLAY" -> getReplayBlocking(tokenData, request.id());
                default -> throw new IllegalArgumentException("Unsupported cache type");
            }
            CacheControlResult metadata = control(new CacheControlRequest("GET", type, request.id()));
            CacheControlResult.CacheNodeResult node = metadata.nodes().getFirst();
            return new CacheControlResult("FETCH", type, request.id(), List.of(
                    new CacheControlResult.CacheNodeResult(
                            node.node(), "FETCHED", node.path(), node.sizeBytes(), node.modifiedAt(), null
                    )
            ));
        } catch (RuntimeException | IOException e) {
            return localResult("FETCH", type, request.id(), "ERROR", List.of(), null, null, rootMessage(e));
        }
    }

    public static Path existingCachePath(String typeValue, long id) {
        String type = normalizeType(typeValue);
        try {
            List<Path> existing = cachePaths(type, id).stream().filter(Files::exists).toList();
            return existing.stream().filter(Files::isRegularFile).findFirst()
                    .orElse(existing.isEmpty() ? null : existing.getFirst());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to locate fetched cache entry", e);
        }
    }

    private static List<Path> cachePaths(String type, long id) throws IOException {
        return switch (type) {
            case "SCORE" -> List.of(SCORE_JSON_CACHE.resolve(id + ".json"));
            case "BEATMAP" -> List.of(beatmapCachePath(id));
            case "REPLAY" -> List.of(REPLAY_CACHE.resolve(id + ".osr"));
            case "BEATMAPSET" -> {
                if (!Files.exists(BEATMAPSET_CACHE)) yield List.of(BEATMAPSET_CACHE.resolve(id + ".osz"));
                try (Stream<Path> entries = Files.list(BEATMAPSET_CACHE)) {
                    List<Path> matches = entries.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals(String.valueOf(id)) || name.startsWith(id + " ") || name.equals(id + ".osz");
                    }).toList();
                    yield matches.isEmpty() ? List.of(BEATMAPSET_CACHE.resolve(id + ".osz")) : matches;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported cache type");
        };
    }

    private static int deleteCachePath(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        if (Files.isDirectory(path)) {
            int count = 0;
            try (Stream<Path> paths = Files.walk(path)) {
                for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (Files.deleteIfExists(child)) count++;
                }
            }
            return count;
        }
        return Files.deleteIfExists(path) ? 1 : 0;
    }

    private static CacheControlResult localResult(String operation, String type, long id, String status,
                                                  List<Path> paths, Long bytes, String modifiedAt, String message) {
        String path = paths.isEmpty() ? null : paths.stream().map(value -> {
            Path absolute = value.toAbsolutePath().normalize();
            Path root = CACHE_PATH.toAbsolutePath().normalize();
            return root.relativize(absolute).toString().replace('\\', '/');
        }).reduce((left, right) -> left + "," + right).orElse(null);
        return new CacheControlResult(operation, type, id, List.of(new CacheControlResult.CacheNodeResult(
                "oStella", status, path, bytes, modifiedAt, message
        )));
    }

    private static String normalizeOperation(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!List.of("QUERY", "GET", "DELETE", "FETCH").contains(normalized))
            throw new IllegalArgumentException("Cache operation must be query, get, delete, or fetch");
        return normalized;
    }

    private static String normalizeType(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!List.of("SCORE", "BEATMAP", "BEATMAPSET", "REPLAY").contains(normalized))
            throw new IllegalArgumentException("Cache type must be score, beatmap, beatmapset, or replay");
        return normalized;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static AreaStats areaStats(Path root) throws IOException {
        if (!Files.exists(root)) return new AreaStats(0, 0);
        long files = 0;
        long bytes = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files++;
                bytes += Files.size(path);
            }
        }
        return new AreaStats(files, bytes);
    }

    private static int clearChildren(Path root) throws IOException {
        if (!Files.exists(root)) return 0;
        int removed = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(path -> !path.equals(root)).sorted(Comparator.reverseOrder()).toList()) {
                if (Files.deleteIfExists(path)) removed++;
            }
        }
        Files.createDirectories(root);
        return removed;
    }

    public enum CacheArea {
        BEATMAPS,
        IMAGES,
        REPLAYS,
        SCORE_JSON,
        BEATMAPSETS,
        ALL
    }

    public record AreaStats(long files, long bytes) {
    }

    public record CacheSummary(
            AreaStats beatmaps,
            AreaStats images,
            AreaStats replays,
            AreaStats scoreJson,
            AreaStats beatmapsets
    ) {
        public long totalFiles() {
            return beatmaps.files + images.files + replays.files + scoreJson.files + beatmapsets.files;
        }

        public long totalBytes() {
            return beatmaps.bytes + images.bytes + replays.bytes + scoreJson.bytes + beatmapsets.bytes;
        }
    }
}
