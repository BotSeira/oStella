package xyz.zcraft.ostella.console;

import xyz.zcraft.ostella.network.WebServer;
import xyz.zcraft.ostella.cache.CacheControlRequest;
import xyz.zcraft.ostella.cache.CacheControlResult;
import xyz.zcraft.ostella.service.CacheService;
import xyz.zcraft.ostella.service.ReplayService;

public interface OstellaConsoleAccess {
    WebServer.ServerStatus status();

    boolean requestTokenRenewal();

    int replayQueueSize();

    ReplayService.JobProgress replayJob(String jobId);

    void deleteReplayJob(String jobId);

    int clearCache(CacheService.CacheArea area);

    CacheControlResult controlCache(CacheControlRequest request);

    void requestStop();
}
