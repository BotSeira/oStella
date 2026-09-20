# oStella administration console

oStella uses Log4J2 for service output and JLine for interactive input, history, completion, and prompt-safe log redraw. Console text is English to avoid terminal encoding problems.

| Command                                                                 | Purpose                                                                      |
|-------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `status`                                                                | Web, token, HTTP, async, renderer, replay-worker, cache, and uptime health   |
| `metrics`                                                               | HTTP and asynchronous work counters                                          |
| `token status`                                                          | Show osu! API token health                                                   |
| `token renew`                                                           | Queue an immediate token renewal                                             |
| `replay status`                                                         | Probe configured osuRenderer queues                                          |
| `replay job <uuid>`                                                     | Find a remote render job                                                     |
| `replay delete <uuid> confirm`                                          | Delete a remote render job                                                   |
| `cache status`                                                          | Show file count and size for every cache area                                |
| `cache <query/delete/get/fetch> <score/beatmap/beatmapset/replay> <id>` | Operate on local cache and aggregate every configured osuRenderer worker     |
| `cache clear <area> confirm`                                            | Clear `beatmaps`, `images`, `replays`, `score-json`, `beatmapsets`, or `all` |
| `config show`                                                           | Show effective configuration with credentials redacted                       |
| `config check`                                                          | Validate `config.yml` without applying it                                    |
| `log show`                                                              | Show the current Log4J2 root level                                           |
| `log level <level>`                                                     | Set `trace`, `debug`, `info`, `warn`, or `error` until restart               |
| `system`                                                                | Show version, JVM, OS, threads, memory, and uptime                           |
| `stop confirm`                                                          | Gracefully stop the console and every owned service                          |

Use `help [command]` and Tab completion in the running console. Bulk cache clearing and service shutdown require the literal `confirm` argument; unified single-ID cache deletion uses the four-part command directly.

For the unified cache command, `query` returns the chain presence matrix, `get` adds path/size/time metadata, `delete` removes all reachable copies, and `fetch` downloads into oStella before pushing beatmapsets or replays to every worker. Score and beatmap caches are oStella-only and therefore appear as `N/A` on workers.

## Automatic caching

`autocache <beatmapset/beatmapset-json/beatmap/beatmap-json> <on/off>` toggles each type independently. All four default to off, and settings last until restart. For example:

```text
autocache beatmapset-json on
autocache beatmap-json on
autocache beatmap on
autocache beatmapset off
```

The worker discovers distinct user IDs from currently cached score JSON files and fetches their best 200 osu!standard scores in two pages of at most 100. It prefetches missing `.osz` archives, full beatmapset JSON, `.osu` files, or full beatmap JSON according to the enabled types. Shared maps/sets reuse the existing local cache; it does not push files to rendering workers.

Work starts only after five seconds without foreground API activity, including queued requests and replay requests. One page or cache item is processed per second, subject to the existing API rate limit. Direct API calls, including token renewal, also count as activity. Foreground calls never wait for the background download; an already-started download may finish, but subsequent requests pause until idle again. Turning a type off prevents further downloads of that type; shutdown interrupts the worker.

Cached-score users are rediscovered every five minutes when the current queue drains. Each user's best scores are refreshed at most hourly; missing or failed resources are retried on a later refresh. Changing a toggle restarts discovery. Malformed cached score files are skipped. Downloaded beatmapsets can use substantial disk space; there is no automatic eviction.
