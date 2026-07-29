package xyz.zcraft.ostella.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import lombok.SneakyThrows;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.zcraft.ostella.data.TokenData;
import xyz.zcraft.ostella.network.OsuAPI;
import xyz.zcraft.osu.model.Score;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
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

    private static final Path DANSER_SONG_CACHE = CACHE_PATH.resolve("danser", "songs");

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
        Files.createDirectories(DANSER_SONG_CACHE);
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
        if (!Files.exists(BEATMAP_CACHE.resolve(String.valueOf(id))) || update) {
            try {
                LOG.debug("Caching beatmap {}", id);
                cacheBeatmapFile(id);
                LOG.debug("Beatmap {} cached", id);
            } catch (IOException e) {
                LOG.error("Failed to download beatmap!", e);
                throw new RuntimeException("Failed to download beatmap!", e);
            }
        }

        return BEATMAP_CACHE.resolve(String.valueOf(id)).toAbsolutePath();
    }

    public static Path getBeatmapPath(long id) {
        return getBeatmapPath(id, false);
    }

    private static void cacheBeatmapFile(long id) throws IOException {
        Files.deleteIfExists(BEATMAP_CACHE.resolve(String.valueOf(id)));
        Files.write(BEATMAP_CACHE.resolve(String.valueOf(id)), executor.enqueueAsync(() -> OsuAPI.getBeatmapBytes(id)).join());
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
        try (Stream<Path> list = Files.list(DANSER_SONG_CACHE)) {
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

        Path beatmapsetPath = DANSER_SONG_CACHE.resolve(id + ".osz");

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

        final Path oszPath = DANSER_SONG_CACHE.resolve(id + ".osz");

        if (Files.exists(oszPath)) {
            Files.copy(oszPath, out);
            return;
        }

        final Path folderPath = DANSER_SONG_CACHE.resolve(String.valueOf(id));

        if (Files.exists(folderPath) && Files.isDirectory(folderPath)) {
            try (ZipOutputStream zos = new ZipOutputStream(out);
                 Stream<Path> files = Files.walk(folderPath)) {
                zos.setLevel(Deflater.BEST_COMPRESSION);

                files.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        String zipEntryName = folderPath.relativize(path).toString().replace('\\', '/');

                        zos.putNextEntry(new ZipEntry(zipEntryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to zip file: " + path, e);
                    }
                });
            }
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

    public static Path getDanserCache() {
        return DANSER_SONG_CACHE;
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
}
