package xyz.zcraft.ostella.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.ostella.config.AppConfig;
import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.ostella.util.MiscUtil;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ReplayService implements Closeable {
    private static final Logger LOG = LogManager.getLogger(ReplayService.class);
    private final AppConfig config;
    private final HttpClient client;
    private final URI rendererUri;

    public ReplayService(AppConfig config) {
        this.config = config;
        this.rendererUri = URI.create(config.replayRender().rendererUrl() + "/");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public QueuedJob queueRender(Path replay, Path beatmapset, double start, double end, boolean obscured) {
        return upload("single", null, List.of(replay), beatmapset, start, end, obscured);
    }

    public QueuedJob queueRenderShowcase(String beatmapId, List<Path> replays, Path beatmapset) {
        return upload("showcase", beatmapId, replays, beatmapset, Double.NaN, Double.NaN, false);
    }

    public JobProgress getJobProgress(String jobId) {
        HttpResponse<String> response = sendString(request("renders/" + jobId + "/status").GET().build());
        if (response.statusCode() == 404) {
            return new JobProgress(JobStatus.UNKNOWN);
        }
        requireSuccess(response.statusCode(), response.body());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JobStatus status;
        try {
            status = JobStatus.valueOf(json.get("status").getAsString().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw unavailable("osuRenderer returned an invalid job status", e);
        }
        return new JobProgress(
                status,
                stringOrNull(json, "progress"),
                stringOrNull(json, "speed"),
                stringOrNull(json, "eta"),
                stringOrNull(json, "error"));
    }

    public int getQueueSize() {
        HttpResponse<String> response = sendString(request("renders/status").GET().build());
        requireSuccess(response.statusCode(), response.body());
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject().get("queue").getAsInt();
        } catch (RuntimeException e) {
            throw unavailable("osuRenderer returned an invalid queue status", e);
        }
    }

    public InputStream openJobResult(String jobId) {
        try {
            HttpResponse<InputStream> response = client.send(
                    request("renders/" + jobId + "/video")
                            .timeout(Duration.ofMinutes(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 404) {
                response.body().close();
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message;
                try (InputStream body = response.body()) {
                    message = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                }
                requireSuccess(response.statusCode(), message);
            }
            return response.body();
        } catch (IOException e) {
            throw unavailable("Failed to download video from osuRenderer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while downloading video from osuRenderer", e);
        }
    }

    public void deleteJob(String jobId) {
        HttpResponse<String> response = sendString(request("renders/" + jobId).DELETE().build());
        if (response.statusCode() != 404) {
            requireSuccess(response.statusCode(), response.body());
        }
    }

    private QueuedJob upload(String mode, String beatmapId, List<Path> replays, Path beatmapset,
                             double start, double end, boolean obscured) {
        try {
            MultipartBody multipart = new MultipartBody();
            multipart.text("mode", mode);
            if (beatmapId != null) {
                multipart.text("beatmapId", beatmapId);
            }
            if (!Double.isNaN(start)) {
                multipart.text("start", String.valueOf(start));
            }
            if (!Double.isNaN(end)) {
                multipart.text("end", String.valueOf(end));
            }
            multipart.bytes("config", "danser-config.json", "application/json", buildDanserConfig(obscured));
            multipart.file("beatmapset", beatmapset.getFileName().toString(), "application/octet-stream", beatmapset);
            for (Path replay : replays) {
                multipart.file("replays", replay.getFileName().toString(), "application/octet-stream", replay);
            }

            HttpResponse<String> response = sendString(request("renders")
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "multipart/form-data; boundary=" + multipart.boundary())
                    .POST(multipart.publisher())
                    .build());
            if (response.statusCode() == 429) {
                throw new ApiException(ErrorCode.RENDER_QUEUE_FULL, "Replay rendering queue is full");
            }
            requireSuccess(response.statusCode(), response.body());
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            return new QueuedJob(json.get("id").getAsString(), json.get("position").getAsInt());
        } catch (IOException e) {
            throw unavailable("Failed to prepare files for osuRenderer", e);
        } catch (RuntimeException e) {
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw unavailable("Invalid response from osuRenderer", e);
        }
    }

    byte[] buildDanserConfig(boolean obscured) throws IOException {
        String json;
        String customPath = config.replayRender().configPath();
        if (customPath != null && !customPath.isBlank() && Files.isRegularFile(Path.of(customPath))) {
            json = Files.readString(Path.of(customPath), StandardCharsets.UTF_8);
        } else {
            if (customPath != null && !customPath.isBlank()) {
                LOG.warn("Custom Danser config '{}' does not exist; using the bundled default", customPath);
            }
            try (InputStream input = ReplayService.class.getResourceAsStream("/danser-config.json")) {
                if (input == null) {
                    throw new IOException("Default Danser config is missing");
                }
                json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        if (obscured) {
            try (InputStream input = ReplayService.class.getResourceAsStream("/danser-config-patch-obscured.json")) {
                if (input == null) {
                    throw new IOException("Obscured Danser config patch is missing");
                }
                JsonObject patch = JsonParser.parseString(
                        new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                result = MiscUtil.deepMergeJson(result, patch);
            }
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder request(String relativePath) {
        HttpRequest.Builder request = HttpRequest.newBuilder(rendererUri.resolve(relativePath));
        if (!config.replayRender().apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + config.replayRender().apiKey());
        }
        return request;
    }

    private HttpResponse<String> sendString(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw unavailable("Cannot connect to osuRenderer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while contacting osuRenderer", e);
        }
    }

    private static String stringOrNull(JsonObject json, String name) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : null;
    }

    private static void requireSuccess(int statusCode, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        String suffix = body == null || body.isBlank() ? "" : ": " + body;
        throw new ApiException(ErrorCode.RENDERER_UNAVAILABLE,
                "osuRenderer returned HTTP " + statusCode + suffix);
    }

    private static ApiException unavailable(String message, Exception error) {
        LOG.warn(message, error);
        return new ApiException(ErrorCode.RENDERER_UNAVAILABLE, message, error);
    }

    @Override
    public void close() {
        // HttpClient owns no application executor in this configuration.
    }

    public enum JobStatus {
        QUEUED,
        UNKNOWN,
        RENDERING,
        TIMEOUT,
        FAILED,
        DONE
    }

    public record JobProgress(JobStatus status, String progress, String speed, String eta, String error) {
        public JobProgress(JobStatus status) {
            this(status, null, null, null, null);
        }
    }

    public record QueuedJob(String id, int position) {
    }

    static final class MultipartBody {
        private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
        private final String boundary = "ostella-" + UUID.randomUUID();
        private final List<HttpRequest.BodyPublisher> parts = new ArrayList<>();

        String boundary() {
            return boundary;
        }

        void text(String name, String value) {
            header("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
            parts.add(HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(CRLF));
        }

        void bytes(String name, String filename, String contentType, byte[] bytes) {
            fileHeader(name, filename, contentType);
            parts.add(HttpRequest.BodyPublishers.ofByteArray(bytes));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(CRLF));
        }

        void file(String name, String filename, String contentType, Path path) throws IOException {
            fileHeader(name, filename, contentType);
            parts.add(HttpRequest.BodyPublishers.ofFile(path));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(CRLF));
        }

        HttpRequest.BodyPublisher publisher() {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n", StandardCharsets.UTF_8));
            return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
        }

        private void fileHeader(String name, String filename, String contentType) {
            String safeFilename = filename.replace("\"", "_").replace("\r", "_").replace("\n", "_");
            header("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                    + safeFilename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
        }

        private void header(String value) {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n" + value,
                    StandardCharsets.UTF_8));
        }
    }
}
