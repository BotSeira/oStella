package xyz.zcraft.ostella.service;

import com.google.gson.Gson;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReplayService implements Closeable {
    private static final Logger LOG = LogManager.getLogger(ReplayService.class);
    private static final Gson GSON = new Gson();
    private final AppConfig config;
    private final HttpClient client;
    private final List<RendererWorker> workers;
    private final ExecutorService workerProbeExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RendererWorker> jobWorkers = new ConcurrentHashMap<>();
    private final AtomicInteger workerCursor = new AtomicInteger();

    public ReplayService(AppConfig config) {
        this.config = config;
        this.workers = config.replayRender().workers().stream()
                .map(url -> new RendererWorker(URI.create(url + "/")))
                .toList();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public QueuedJob queueRender(long scoreId, Path replay, long beatmapsetId, Path beatmapset,
                                  double start, double end, boolean obscured) {
        return queueRender(scoreId, replay, beatmapsetId, beatmapset, start, end, obscured, null);
    }

    public QueuedJob queueRender(long scoreId, Path replay, long beatmapsetId, Path beatmapset,
                                  double start, double end, boolean obscured, QqUploadRequest qqUpload) {
        return upload("single", null, beatmapsetId, beatmapset,
                List.of(new ReplayInput(scoreId, replay)), start, end, obscured, qqUpload);
    }

    @SuppressWarnings("UnusedReturnValue")
    public QueuedJob queueRenderShowcase(String beatmapId, long beatmapsetId,
                                         List<ReplayInput> replays, Path beatmapset) {
        return queueRenderShowcase(beatmapId, beatmapsetId, replays, beatmapset, null);
    }

    public QueuedJob queueRenderShowcase(String beatmapId, long beatmapsetId,
                                          List<ReplayInput> replays, Path beatmapset,
                                          QqUploadRequest qqUpload) {
        return upload("showcase", beatmapId, beatmapsetId, beatmapset,
                replays, Double.NaN, Double.NaN, false, qqUpload);
    }

    public JobProgress getJobProgress(String jobId) {
        RendererWorker assigned = jobWorkers.get(jobId);
        if (assigned != null) {
            JobLookup lookup = getJobProgress(assigned, jobId);
            if (!lookup.found()) {
                jobWorkers.remove(jobId, assigned);
            }
            return lookup.progress();
        }

        boolean unavailableWorker = false;
        for (RendererWorker worker : rotatedWorkers()) {
            try {
                JobLookup lookup = getJobProgress(worker, jobId);
                if (lookup.found()) {
                    jobWorkers.put(jobId, worker);
                    return lookup.progress();
                }
            } catch (ApiException e) {
                unavailableWorker = true;
            }
        }
        if (unavailableWorker) {
            throw new ApiException(ErrorCode.RENDERER_UNAVAILABLE,
                    "Could not locate replay job because one or more osuRenderer workers are unavailable");
        }
        return new JobProgress(JobStatus.UNKNOWN);
    }

    private JobLookup getJobProgress(RendererWorker worker, String jobId) {
        HttpResponse<String> response = sendString(worker,
                request(worker, "renders/" + jobId + "/status").GET().build());
        if (response.statusCode() == 404) {
            return new JobLookup(false, new JobProgress(JobStatus.UNKNOWN));
        }
        requireSuccess(response.statusCode(), response.body());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JobStatus status;
        try {
            status = JobStatus.valueOf(json.get("status").getAsString().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw unavailable("osuRenderer returned an invalid job status", e);
        }
        return new JobLookup(true, new JobProgress(
                status,
                stringOrNull(json, "progress"),
                stringOrNull(json, "speed"),
                stringOrNull(json, "eta"),
                stringOrNull(json, "error"),
                json.has("qqFile") && json.get("qqFile").isJsonObject()
                        ? json.getAsJsonObject("qqFile") : null));
    }

    public int getQueueSize() {
        List<WorkerStatus> statuses = probeWorkers();
        if (statuses.isEmpty()) {
            throw new ApiException(ErrorCode.RENDERER_UNAVAILABLE,
                    "No osuRenderer workers are available");
        }
        return statuses.stream().mapToInt(WorkerStatus::queue).sum();
    }

    public InputStream openJobResult(String jobId) {
        RendererWorker assigned = jobWorkers.get(jobId);
        if (assigned != null) {
            VideoLookup lookup = openJobResult(assigned, jobId);
            if (!lookup.found()) {
                jobWorkers.remove(jobId, assigned);
            }
            return lookup.stream();
        }

        boolean unavailableWorker = false;
        for (RendererWorker worker : rotatedWorkers()) {
            try {
                VideoLookup lookup = openJobResult(worker, jobId);
                if (lookup.found()) {
                    jobWorkers.put(jobId, worker);
                    return lookup.stream();
                }
            } catch (ApiException e) {
                unavailableWorker = true;
            }
        }
        if (unavailableWorker) {
            throw new ApiException(ErrorCode.RENDERER_UNAVAILABLE,
                    "Could not locate replay video because one or more osuRenderer workers are unavailable");
        }
        return null;
    }

    private VideoLookup openJobResult(RendererWorker worker, String jobId) {
        try {
            HttpResponse<InputStream> response = client.send(
                    request(worker, "renders/" + jobId + "/video")
                            .timeout(Duration.ofMinutes(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 404) {
                response.body().close();
                return new VideoLookup(false, null);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message;
                try (InputStream body = response.body()) {
                    message = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                }
                requireSuccess(response.statusCode(), message);
            }
            return new VideoLookup(true, response.body());
        } catch (IOException e) {
            throw unavailable("Failed to download video from osuRenderer worker " + worker.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while downloading video from osuRenderer", e);
        }
    }

    public void deleteJob(String jobId) {
        RendererWorker assigned = jobWorkers.remove(jobId);
        if (assigned != null) {
            deleteJob(assigned, jobId);
            return;
        }

        ApiException lastFailure = null;
        int successfulWorkers = 0;
        for (RendererWorker worker : rotatedWorkers()) {
            try {
                deleteJob(worker, jobId);
                successfulWorkers++;
            } catch (ApiException e) {
                lastFailure = e;
            }
        }
        if (successfulWorkers == 0 && lastFailure != null) {
            throw lastFailure;
        }
    }

    private void deleteJob(RendererWorker worker, String jobId) {
        HttpResponse<String> response = sendString(worker,
                request(worker, "renders/" + jobId).DELETE().build());
        if (response.statusCode() != 404) {
            requireSuccess(response.statusCode(), response.body());
        }
    }

    private QueuedJob upload(String mode, String beatmapId, long beatmapsetId, Path beatmapset,
                             List<ReplayInput> replays,
                             double start, double end, boolean obscured, QqUploadRequest qqUpload) {
        try {
            byte[] danserConfig = buildDanserConfig(obscured);
            List<WorkerStatus> idleWorkers = new ArrayList<>();
            List<WorkerStatus> busyWorkers = new ArrayList<>();
            for (WorkerStatus status : probeWorkers()) {
                (status.active() == 0 ? idleWorkers : busyWorkers).add(status);
            }

            busyWorkers.sort(Comparator.comparingInt(WorkerStatus::queue));
            List<WorkerStatus> candidates = new ArrayList<>(idleWorkers.size() + busyWorkers.size());
            candidates.addAll(idleWorkers);
            candidates.addAll(busyWorkers);

            int fullWorkers = 0;
            ApiException lastFailure = null;
            for (WorkerStatus candidate : candidates) {
                try {
                    QueuedJob queued = uploadToWorker(candidate.worker(), mode, beatmapId,
                            beatmapsetId, beatmapset, replays, start, end, qqUpload, danserConfig);
                    jobWorkers.put(queued.id(), candidate.worker());
                    return queued;
                } catch (ApiException e) {
                    lastFailure = e;
                    if (e.getErrorCode() == ErrorCode.RENDER_QUEUE_FULL) {
                        fullWorkers++;
                    } else {
                        LOG.warn("Failed to queue replay on osuRenderer worker {}; trying the next worker",
                                candidate.worker().uri());
                    }
                }
            }
            if (fullWorkers == workers.size()) {
                throw new ApiException(ErrorCode.RENDER_QUEUE_FULL,
                        "All replay rendering queues are full");
            }
            if (lastFailure != null && candidates.size() == workers.size()) {
                throw new ApiException(ErrorCode.RENDERER_UNAVAILABLE,
                        "No osuRenderer worker accepted the replay render", lastFailure);
            }
            throw new ApiException(ErrorCode.RENDERER_UNAVAILABLE,
                    "No osuRenderer workers are available");
        } catch (IOException e) {
            throw unavailable("Failed to prepare files for osuRenderer", e);
        } catch (RuntimeException e) {
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw unavailable("Invalid response from osuRenderer", e);
        }
    }

    private QueuedJob uploadToWorker(RendererWorker worker, String mode, String beatmapId,
                                     long beatmapsetId, Path beatmapset, List<ReplayInput> replays,
                                     double start, double end, QqUploadRequest qqUpload,
                                     byte[] danserConfig) throws IOException {
        List<Long> replayIds = replays.stream().map(ReplayInput::scoreId).toList();
        CacheStatus cacheStatus = getCacheStatus(worker, Set.of(beatmapsetId), new LinkedHashSet<>(replayIds));
        List<ReplayInput> missingReplays = replays.stream()
                .filter(replay -> !cacheStatus.replayIds().contains(replay.scoreId()))
                .toList();

        MultipartBody multipart = new MultipartBody();
        multipart.text("mode", mode);
        multipart.text("beatmapsetId", String.valueOf(beatmapsetId));
        multipart.text("replayIds", GSON.toJson(replayIds));
        multipart.text("replayUploadIds", GSON.toJson(
                missingReplays.stream().map(ReplayInput::scoreId).toList()));
        if (beatmapId != null) multipart.text("beatmapId", beatmapId);
        if (!Double.isNaN(start)) multipart.text("start", String.valueOf(start));
        if (!Double.isNaN(end)) multipart.text("end", String.valueOf(end));
        if (qqUpload != null) multipart.text("qqUpload", GSON.toJson(qqUpload));
        multipart.bytes("config", "danser-config.json", "application/json", danserConfig);
        if (!cacheStatus.beatmapsetIds().contains(beatmapsetId)) {
            multipart.file("beatmapset", beatmapset.getFileName().toString(),
                    "application/octet-stream", beatmapset);
        }
        for (ReplayInput replay : missingReplays) {
            multipart.file("replays", replay.path().getFileName().toString(),
                    "application/octet-stream", replay.path());
        }

        HttpResponse<String> response = sendString(worker, request(worker, "renders")
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "multipart/form-data; boundary=" + multipart.boundary())
                .POST(multipart.publisher())
                .build());
        if (response.statusCode() == 429) {
            throw new ApiException(ErrorCode.RENDER_QUEUE_FULL,
                    "Replay rendering queue is full on worker " + worker.uri());
        }
        requireSuccess(response.statusCode(), response.body());
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            return new QueuedJob(json.get("id").getAsString(), json.get("position").getAsInt());
        } catch (RuntimeException e) {
            throw unavailable("Invalid response from osuRenderer worker " + worker.uri(), e);
        }
    }

    private CacheStatus getCacheStatus(RendererWorker worker, Set<Long> beatmapsetIds, Set<Long> replayIds) {
        JsonObject body = new JsonObject();
        body.add("beatmapsetIds", GSON.toJsonTree(beatmapsetIds));
        body.add("replayIds", GSON.toJsonTree(replayIds));
        HttpResponse<String> response = sendString(worker, request(worker, "cache/status")
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
        requireSuccess(response.statusCode(), response.body());
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            Set<Long> cachedBeatmapsets = new LinkedHashSet<>();
            Set<Long> cachedReplays = new LinkedHashSet<>();
            json.getAsJsonArray("beatmapsetIds").forEach(id -> cachedBeatmapsets.add(id.getAsLong()));
            json.getAsJsonArray("replayIds").forEach(id -> cachedReplays.add(id.getAsLong()));
            return new CacheStatus(Set.copyOf(cachedBeatmapsets), Set.copyOf(cachedReplays));
        } catch (RuntimeException e) {
            throw unavailable("osuRenderer returned an invalid cache status", e);
        }
    }

    private WorkerStatus getWorkerStatus(RendererWorker worker) {
        HttpResponse<String> response = sendString(worker, request(worker, "renders/status")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build());
        if (response.statusCode() == 404) {
            LOG.debug("osuRenderer worker {} does not expose load status; treating its load as unknown",
                    worker.uri());
            return new WorkerStatus(worker, 0, 0);
        }
        requireSuccess(response.statusCode(), response.body());
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            int queue = json.get("queue").getAsInt();
            int active = json.get("active").getAsInt();
            if (queue < 0 || active < 0) throw new IllegalStateException("negative worker count");
            return new WorkerStatus(worker, queue, active);
        } catch (RuntimeException e) {
            throw unavailable("osuRenderer returned an invalid worker status", e);
        }
    }

    private List<WorkerStatus> probeWorkers() {
        List<CompletableFuture<WorkerStatus>> probes = rotatedWorkers().stream()
                .map(worker -> CompletableFuture.supplyAsync(() -> getWorkerStatus(worker), workerProbeExecutor)
                        .exceptionally(_ -> {
                            LOG.warn("Skipping unavailable osuRenderer worker {}", worker.uri());
                            return null;
                        }))
                .toList();
        return probes.stream()
                .map(CompletableFuture::join)
                .filter(java.util.Objects::nonNull)
                .toList();
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

    private HttpRequest.Builder request(RendererWorker worker, String relativePath) {
        HttpRequest.Builder request = HttpRequest.newBuilder(worker.uri().resolve(relativePath));
        if (!config.replayRender().apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + config.replayRender().apiKey());
        }
        return request;
    }

    private HttpResponse<String> sendString(RendererWorker worker, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw unavailable("Cannot connect to osuRenderer worker " + worker.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while contacting osuRenderer", e);
        }
    }

    private List<RendererWorker> rotatedWorkers() {
        int size = workers.size();
        int start = Math.floorMod(workerCursor.getAndIncrement(), size);
        List<RendererWorker> rotated = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rotated.add(workers.get((start + i) % size));
        }
        return rotated;
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
        workerProbeExecutor.close();
        // HttpClient owns no application executor in this configuration.
    }

    public enum JobStatus {
        QUEUED,
        UNKNOWN,
        RENDERING,
        UPLOADING,
        TIMEOUT,
        FAILED,
        DONE
    }

    public record JobProgress(JobStatus status, String progress, String speed, String eta, String error,
                              JsonObject qqFile) {
        public JobProgress(JobStatus status) {
            this(status, null, null, null, null, null);
        }
    }

    public record QueuedJob(String id, int position) {
    }

    public record ReplayInput(long scoreId, Path path) {
        public ReplayInput {
            if (scoreId <= 0 || path == null) {
                throw new IllegalArgumentException("Replay input requires a positive score id and a file");
            }
        }
    }

    public record QqUploadRequest(String accessToken, String targetType, String targetId) {
        public QqUploadRequest {
            accessToken = requireValue(accessToken, "accessToken", 4096);
            targetType = requireValue(targetType, "targetType", 16);
            targetId = requireValue(targetId, "targetId", 256);
            if (!targetType.equals("groups") && !targetType.equals("users")) {
                throw new IllegalArgumentException("qqUpload.targetType must be groups or users");
            }
        }

        private static String requireValue(String value, String name, int maxLength) {
            if (value == null || value.isBlank() || value.length() > maxLength) {
                throw new IllegalArgumentException("qqUpload." + name + " is invalid");
            }
            return value;
        }
    }

    private record CacheStatus(Set<Long> beatmapsetIds, Set<Long> replayIds) {
    }

    private record RendererWorker(URI uri) {
    }

    private record WorkerStatus(RendererWorker worker, int queue, int active) {
    }

    private record JobLookup(boolean found, JobProgress progress) {
    }

    private record VideoLookup(boolean found, InputStream stream) {
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

        @SuppressWarnings("SameParameterValue")
        void bytes(String name, String filename, String contentType, byte[] bytes) {
            fileHeader(name, filename, contentType);
            parts.add(HttpRequest.BodyPublishers.ofByteArray(bytes));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(CRLF));
        }

        @SuppressWarnings("SameParameterValue")
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
