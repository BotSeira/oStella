package xyz.zcraft.ostella.cache;

import java.util.List;

public record CacheControlResult(String operation, String type, long id, List<CacheNodeResult> nodes) {
    public CacheControlResult {
        nodes = List.copyOf(nodes);
    }

    public record CacheNodeResult(
            String node,
            String status,
            String path,
            Long sizeBytes,
            String modifiedAt,
            String message
    ) {
        public CacheNodeResult withNode(String value) {
            return new CacheNodeResult(value, status, path, sizeBytes, modifiedAt, message);
        }
    }
}
