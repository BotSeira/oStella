# Image and JSON responses

Image endpoints support an explicit JSON opt-in using `Accept: application/json`.
URLs, query parameters, authentication, filtering, and ordering remain the same.

```sh
curl -H 'Accept: application/json' 'http://localhost:8080/users/123'
curl -H 'Accept: application/json' 'http://localhost:8080/beatmapsets/456'
curl -H 'Accept: application/json' 'http://localhost:8080/users/123/scores/bestof?n=5'
```

Use your configured server port. JSON responses use the existing envelope:

```json
{"success":true,"message":"Success","data":{}}
```

The response has `Content-Type: application/json`. Rendered images have
`Content-Type: image/png`; background downloads retain their image format.
Negotiated responses include `Vary: Accept` so caches distinguish representations.

An absent `Accept`, `*/*`, or an image media type keeps the image response.
Media types are case-insensitive; parameters and comma-separated media ranges are
supported. An explicit `application/json` with a valid positive `q` value opts in,
even when images are also listed. `application/json;q=0` does not opt in.
Wildcard media types do not opt in to JSON.

## Supported endpoints and `data`

| Endpoint                                                           | JSON data                                                                                                            |
|--------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `GET /users/{userId}`                                              | User object; no best-score request is made                                                                           |
| `GET /beatmapsets/{beatmapsetId}`                                  | Beatmapset object with difficulties ordered by stars                                                                 |
| `GET /beatmaps/{beatmapId}`                                        | Beatmap object with beatmapset metadata                                                                              |
| `GET /scores/{scoreId}`                                            | Score object                                                                                                         |
| `GET /users/{userId}/scores/recent`                                | `user`, filtered `scores`, `type`, `filters`, and original `positions`                                               |
| `GET /users/{userId}/scores/bestof`                                | Same score-list structure                                                                                            |
| `GET /users/{userId}/scores/today-best`                            | Same structure, plus `title` describing the time window                                                              |
| `POST /users/leaderboards`                                         | Users sorted by osu! pp                                                                                              |
| `POST /beatmaps/{beatmapId}/leaderboards`                          | `beatmap` and sorted `placements`                                                                                    |
| `GET /beatmaps/{beatmapId}/analysis`                               | `beatmap`, `diff`, `mods`, `performance`, and `patterns`                                                             |
| `GET /scores/{scoreId}/analysis`                                   | Score, difficulty, hit/miss positions, timing errors, unstable rate, performance graphs, PP+, and simulation results |
| `GET /scores/{scoreId}/misses/{missIndex}/visualize`               | Miss index, beatmap ID, object index, time, type, nearby keyframes, difficulty, and PP loss estimates                |
| `GET /multiplayer/rooms/{roomId}/playlist/{playlistItemId}/result` | Multiplayer result data including players, teams, and series scores                                                  |
| `POST /templates/{templateName}/render`                            | Resolved template variables, including `@score`, `@user`, `@beatmap`, and `@beatmapset` references                   |
| `GET /beatmapsets/{beatmapsetId}/background`                       | `beatmapset_id` and cover `url`; no image download                                                                   |
| `GET /beatmaps/{beatmapId}/background`                             | `beatmapId`, `beatmapsetId`, and background `fileName`; no archive extraction                                        |

osu! model fields retain their existing JSON names. New composite data uses the
field names listed above; optional null fields may be omitted. The nested
beatmapset in a beatmap response omits `beatmaps` to avoid circular references.
Score analysis excludes the parser's complete replay/beatmap objects.

JSON requests skip HTML, screenshots, and the browser render queue. Basic score,
beatmap, and map leaderboard JSON requests also skip image-only difficulty
calculations. Analysis endpoints still perform the analysis and require the same
replay/PP+ dependencies as their image counterparts. The basic beatmap JSON is
the API model; the `mod` parameter affects rendered difficulty, while the analysis
endpoint returns calculated modded data.

Existing JSON-only endpoints, beatmapset archive downloads, and replay video
endpoints keep their existing response formats.

## Implementation

`ImageResponse` centralizes negotiation and asynchronous rendering. Controllers
prepare data first and pass a render function which runs only for image requests.
The HTTP handler names use `get…` for resources offering either representation.
`ScoreAnalysisData` and `PerformanceGraphData` live in the data package;
`MissVisualizeService` separates analysis preparation from drawing so both formats
share validation and calculations. `BeatmapData` creates independent enriched
models without mutating cached API objects.
