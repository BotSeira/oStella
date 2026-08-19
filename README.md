# oStella

`oStella` is a Java service that fetches osu! data and exposes simple HTTP endpoints for status,
multiplayer info, and rendered osu! images.

It is the backend for [Seira](https://github.com/BotSeira/SeiraCore) bot,
and also provides a standalone API for other clients to consume.

## What You Get

- PNG score panels for best and recent scores, beatmap, beatmapset, and so on!
- PNG score analysis for a specific score
- PNG beatmap PP+ composition and hit-object pattern analysis
- PNG player comparison leaderboard endpoint (`/maplb`, `/leaderboard`)
- Replay video orchestration for solo and multiplayer showcases via a separate osuRenderer service (`/replay`)
- Current multiplayer room info endpoint (`/mp`)
- Current daily challenge endpoint (`/daily`)
- Health endpoint (`/status`)
- Automatic OAuth token renewal for osu! API

Here are some demo:

### Best-of-N

<img width="800" alt="image" src="https://github.com/user-attachments/assets/a10742b8-148d-4fab-90b3-3ecd6882e2ae" />

### Beatmap Card

<img width="400" alt="image" src="https://github.com/user-attachments/assets/41f36596-94c7-4407-af82-490efccf6704" />

### Group Leaderboard

<img width="400" alt="image" src="https://github.com/user-attachments/assets/cbc361a4-61e0-440d-908e-f1d52009373e" />

### Score Card

<img width="400" alt="image" src="https://github.com/user-attachments/assets/c3ac7fde-adea-427b-be0a-9871a45ca8ad" />

### Score Analysis

<img width="400" alt="image" src="https://github.com/user-attachments/assets/024fe6b2-c10a-4c9e-aefc-9c784826a9b7" />

### Miss Analysis

<img width="400" alt="image" src="https://github.com/user-attachments/assets/dd9ab809-b7aa-44b2-a749-8a841781b30c" />

### Beatmapset Card

<img width="400" alt="image" src="https://github.com/user-attachments/assets/2bb887d6-5fff-429b-b39e-963235ec463e" />

### Replay Video

<img width="400" alt="image" src="https://github.com/user-attachments/assets/1a2b993d-f9c5-481f-97eb-30613d3192f7" />
<img width="400" alt="image" src="https://github.com/user-attachments/assets/45946964-7dd6-4f8a-a346-a83be0b00803" />

## Prerequisites

- JDK 25
- Maven 3.9+
- osu! OAuth app credentials (`client_id`, `client_secret`)
- One or more reachable osuRenderer workers when replay video endpoints are enabled

## Quick Start

Local administration commands are documented in [docs/console.md](docs/console.md). Runtime output is handled by Log4J2 and interactive input by JLine.

1. Copy default config file in the project root.
2. Install Playwright dependencies if not already present.
3. Launch the app.
4. Call an endpoint.

### 1) Create `config.yml`

The default config file is generated as `config.yml` when you first start the service.
You can also copy the example config from [ostella-example-config.yml](/src/main/resources/ostella-example-config.yml)

To show the performance+ skill breakdown in score analysis images and enable beatmap analysis, set
`performancePlus.endpoint` to the calculator's API base URL (for example,
`http://localhost:5000/api`). No other performance+ setting is required.

### 2) Install Playwright Dependencies

```shell
mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install-deps"
```

### 3) Launch

```shell
mvn -U clean compile exec:java
```

### 4) Call an Endpoint

```shell
curl "http://localhost:8721/bo?u=12345678&n=20" --output best_of_20.png
```

## Endpoints

Base URL: `http://localhost:<OSTELLA_PORT>`

Most JSON endpoints return: `{"success": boolean, "message": string, "data": any}`.
Image endpoints return PNG bytes. Replay download returns `video/mp4`.

### Beatmaps

| Method | Path                                 | Purpose                        | Params / POST Body                                 | Response |
|--------|--------------------------------------|--------------------------------|----------------------------------------------------|----------|
| GET    | `/beatmaps/lookup`                   | Resolve beatmap IDs            | See section below                                  | JSON     |
| GET    | `/beatmaps/{beatmapId}`              | Beatmap card image             | path `beatmapId` (+ optional query param `mod`)    | PNG      |
| GET    | `/beatmaps/{beatmapId}/analysis`     | PP+ and object-pattern analysis | path `beatmapId` (+ optional query param `mod`)   | PNG      |
| POST   | `/beatmaps/{beatmapId}/leaderboards` | Compare players on one beatmap | path `beatmapId` + POST Body `{"uids":[user ids]}` | PNG      |

### Beatmapsets

| Method | Path                                   | Purpose                | Params / POST Body   | Response |
|--------|----------------------------------------|------------------------|----------------------|----------|
| GET    | `/beatmapsets/lookup`                  | Resolve beatmapset IDs | See section below    | JSON     |
| GET    | `/beatmapsets/search`                  | Search beatmapsets     | `q` (search keyword) | JSON     |
| GET    | `/beatmapsets/{beatmapsetId}`          | Beatmapset card image  | path `beatmapsetId`  | PNG      |
| GET    | `/beatmapsets/{beatmapsetId}/download` | Download beatmapset    | path `beatmapsetId`  | OSZ      |

### Scores

| Method | Path                                             | Purpose                        | Params / POST Body         | Response |
|--------|--------------------------------------------------|--------------------------------|----------------------------|----------|
| GET    | `/scores/lookup`                                 | Resolve score IDs              | See section below          | JSON     |
| GET    | `/scores/{scoreId}`                              | Score card image               | path `scoreId`             | PNG      |
| GET    | `/scores/{scoreId}/analysis`                     | Score analysis card image      | path `scoreId`             | PNG      |
| GET    | `/scores/{scoreId}/highlight`                    | 20s highlight range of a score | path `scoreId`             | JSON     |
| GET    | `/scores/{scoreId}/misses`                       | List the misses of the score   | path `scoreId`             | JSON     |
| GET    | `/scores/{scoreId}/misses/{missIndex}/visualize` | Visualize misses               | path `scoreId` `missIndex` | PNG      |

### Multiplayer Rooms

| Method | Path                              | Purpose                    | Params / POST Body            | Response |
|--------|-----------------------------------|----------------------------|-------------------------------|----------|
| GET    | `/multiplayer/rooms/current`      | Current multiplayer room   | Requires Authorization Header | JSON     |
| GET    | `/multiplayer/rooms/current/item` | Current room playlist item | Requires Authorization Header | JSON     |

### Users

| Method | Path                            | Purpose                   | Params / POST Body               | Response |
|--------|---------------------------------|---------------------------|----------------------------------|----------|
| POST   | `/users`                        | Get multiple user data    | POST Body `{"ids":[user ids]}`   | JSON     |
| GET    | `/users/me`                     | User data                 | Requires Authorization Header    | JSON     |
| GET    | `/users/me/friends`             | Friends list for user     | Requires Authorization Header    | JSON     |
| POST   | `/users/leaderboards`           | User PP leaderboard image | `{"uids":[user ids]}`            | PNG      |
| GET    | `/users/{userId}/scores/bestof` | Best-of-N scores image    | path `userId`, query `n` (count) | PNG      |
| GET    | `/users/{userId}/scores/recent` | Recent scores image       | path `userId`, query `n` (count) | PNG      |
| GET    | `/users/{userId}/scores/today-best` | Best scores achieved recently | path `userId`, optional query `days` (default 1) | PNG |

### Replays (enabled when `replayRender.enabled` is true)

For video jobs, oStella batch-checks osuRenderer's persistent asset cache using
the beatmapset ID and score IDs. Only missing `.osz` and `.osr` files are uploaded;
the public replay endpoints below remain unchanged.
Multiple worker URLs can be configured with `replayRender.workers`; all workers
share `replayRender.apiKey`. oStella checks every worker before submission, prefers
an idle worker, skips unreachable workers, and falls back across busy queues. It
returns a queue-full error only when every configured worker rejects the job as full.
The legacy `replayRender.rendererUrl` field remains supported as a single-worker fallback.
SeiraCore may also include an optional `qqUpload` object containing a short-lived
QQ access token and the destination. oStella passes it through without using or
persisting it. After osuRenderer uploads the completed MP4, `/status` returns the
QQ `qqFile` identifier to SeiraCore. Deploy both service hops behind TLS because
the render request can contain a bearer credential.

| Method | Path                                    | Purpose                                | Params / POST Body                                | Response    |
|--------|-----------------------------------------|----------------------------------------|---------------------------------------------------|-------------|
| GET    | `/replays/status`                       | Replay renderer overview               | none                                              | JSON        |
| POST   | `/cache/control`                        | Control cache across oStella and workers | `{"operation":"QUERY","type":"BEATMAPSET","id":12345}` | JSON |
| POST   | `/replays/renders/score/{scoreId}`      | Queue single replay render             | path `scoreId`                                    | `202` JSON  |
| POST   | `/replays/renders/showcase/scores`      | Queue multi-score showcase render      | POST Body `{"ids":[score ids]}`                   | `202` JSON  |
| POST   | `/replays/renders/showcase/{beatmapId}` | Queue multi-score showcase render      | path `beatmapId` + POST Body `{"ids":[user ids]}` | `202` JSON  |
| GET    | `/replays/{jobId}/status`               | Get render job state                   | path `{jobId}`                                    | JSON        |
| GET    | `/replays/{jobId}/video`                | Download rendered video                | path `{jobId}`                                    | `video/mp4` |
| DELETE | `/replays/{jobId}/video`                | Remove rendered video and job metadata | path `{jobId}`                                    | text        |

### Miscellaneous

| Method | Path      | Purpose                              | Params / POST Body | Response |
|--------|-----------|--------------------------------------|--------------------|----------|
| GET    | `/daily`  | Current daily challenge room summary | none               | JSON     |
| GET    | `/health` | Service health and osu! API health   | none               | JSON     |

### Custom Templates

| Method | Path                               | Response |
|--------|------------------------------------|----------|
| POST   | `/templates/{templateName}/render` | PNG      |

To render custom templates, you can use this endpoint. 
Put your template file under `templates/` directory, 
and call the endpoint with the filename without `.html` as the template name.

#### POST Body Structure

You can POST a JSON body with the following structure:

```json
{
  "@score": 12345678,
  "@beatmap": 12345,
  "@beatmapset": 56789,
  "@user": 12345678,
  "key1": "value1",
  "key2": "value2"
}
```

If `@score`, `@beatmap`, `@beatmapset`, or `@user` are provided,
the corresponding data will be fetched and made available in the template context.
The rest of key-value pairs will be  available as direct variables in the template.

Template rendering is powered by [Thymeleaf](https://www.thymeleaf.org/), 
and you can use Thymeleaf syntax in your templates to access the data.

Use `Osu.formatStarRating(value)` and `Osu.formatAccuracy(value)` for values that
should match osu!'s in-game display. These helpers floor star ratings to two
decimal places and accuracy ratios to four decimal places before formatting.

#### External Assets

To use external assets, put them under `templates/assets/` directory,
and reference them in your template with `http://local-asset/` + relative paths.

For example, if you have `templates/assets/image.png`,
you can reference it in your template as:

```html
<img src="http://local-asset/image.png"  alt="Image"/>
```

### Lookup Params

#### Looking up beatmaps, beatmapsets, or scores by explicit ID:

- `m` = map ID
- `s` = score ID
- `ms` = mapset ID
- `i` = index for mapset (e.g., `i=0` for the first map in a mapset, `i=1` for the second, etc.)

You can look up a score's beatmap and beatmapset,
a beatmap's beatmapset, or a beatmapset's beatmaps
by including the index `i` in the query parameters.
You can also look up the score of a beatmap, or the beatmap index of a beatmapset.

#### Looking up beatmaps, beatmapset, or scores by a user and index (e.g., best-of-N):

- `of` = score type
- `i` = index (for `bo` `rs` `rp`, which score index to return)
- `u` = user ID

##### Score Types for `of` parameter:

- `bo` - best scores
- `rs` - recent scores
- `rp` - recent **passed** scores
- `mp` - current multiplayer playlist item

#### Examples

- `/beatmaps/lookup?m=12345678` - Look up beatmap by map ID
- `/beatmaps/lookup?ms=12345678&i=0` - Look up the first beatmap of a beatmapset
- `/beatmaps/lookup?s=12345678` - Look up the beatmap of a score
- `/beatmaps/lookup?of=bo&i=0&u=12345678` - Look up the beatmap of a user's best score #1
- `/beatmapsets/lookup?ms=12345678` - Look up beatmapset by mapset ID
- `/beatmapsets/lookup?m=12345678` - Look up the beatmapset of a beatmap
- `/beatmapsets/lookup?of=mp` - Look up the beatmapset of the current multiplayer playlist item
- `/scores/lookup?of=rs&i=2&u=12345678` - Look up the score ID of a user's recent score #3
- `/scores/lookup?s=12345678` - Look up score by score ID

## Cache Behavior

`oStella` caches downloaded assets on disk to reduce repeated upstream requests.

- Beatmap files used for difficulty/PP calculation are cached in `data/cache/beatmap/`.
- Remote images used by templates (avatars, covers, flags) are cached in `data/cache/image/`.
- Image cache keys are generated from the image URL, so the same URL reuses the same cached file.
- Cache is file-based and persists across restarts.
- Public HTTP endpoints currently use on-demand caching (download when missing).

To clear cache, stop the service and remove files under `data/cache/`; they will be re-downloaded on future requests.

## Build Artifacts

`mvn clean package` generates:

- `target/oStella-{version}.jar`
- `target/oStella-{version}-jar-with-dependencies.jar`

## Playwright Note

Image rendering depends on Playwright Chromium. If your environment is missing browser binaries,
when first started, Playwright will attempt to download them.

## Performance & Requirements

oStella is designed to be highly concurrent, but its resource usage scales directly
with how you configure image rendering. Danser CPU and memory usage now belongs to the
separately deployed osuRenderer instance.

### Minimum System Requirements

* **CPU:** 2+ Cores
* **RAM:** 2 GB minimum
* **Storage:** 5+ GB free space for cached osu! beatmaps, beatmapsets, and replays

### RAM Usage

> Memory consumption is strictly controlled by your worker pool configurations.
> By default, oStella prevents Out-Of-Memory crashes by queuing requests rather
> than spawning infinite browser instances.

* **Core Java Server:** ~250MB - 500MB (depending on JVM garbage collection and cache size).
* **Image Rendering (Playwright/Chromium):** ~100MB - 150MB per active worker.
  If you configure `ostella.renderWorkers: 4`, expect Chromium to reserve up to ~600MB of RAM under peak load.

### CPU Usage

* **API Routing & Network:** Near 0% CPU impact. Asynchronous request handling allows the server to idle efficiently.
* **Image Rendering:** Moderate, bursty CPU usage. Chromium utilizes separate OS processes for rendering,
  meaning concurrent image requests will actively utilize multiple CPU cores for brief moments.

### Low Resource Environments?

If you are running oStella on a low-resource environment (e.g., 2GB RAM, 2 CPU cores), it is crucial to:

1. Limit your Playwright worker pool to `2` or `3` to prevent memory exhaustion.
2. Deploy osuRenderer on a separate worker host for replay rendering.
3. Monitor resource usage under load to ensure the service remains responsive.

## Logs

Log files are written to `logs/`:

- `latest.log` (application logs)
- `javalin-server.log` (Javalin/Jetty logs)
- rolled `*.log.gz` archives

## License

MIT. See `LICENSE`.
