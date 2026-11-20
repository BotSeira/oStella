package xyz.zcraft.ostella.snapshot;

/**
 * Exactly one selector, in beatmap milliseconds or one-based object/miss indexes.
 */
public record SnapshotRequest(Kind kind, long value, long offset) {
    public SnapshotRequest {
        if (kind == null || value < 0 || (kind != Kind.TIME && value == 0)
                || Math.abs((double) offset) > 60000) {
            throw new IllegalArgumentException("Invalid snapshot selector or offset (maximum ±60000ms)");
        }
    }

    public static SnapshotRequest fromQuery(String time, String object, String miss, String offset) {
        int count = (time == null ? 0 : 1) + (object == null ? 0 : 1) + (miss == null ? 0 : 1);
        if (count != 1) throw new IllegalArgumentException("Specify exactly one of time, object or miss");
        Kind kind = time != null ? Kind.TIME : object != null ? Kind.OBJECT : Kind.MISS;
        return new SnapshotRequest(kind, Long.parseLong(time != null ? time : object != null ? object : miss),
                offset == null ? 0 : Long.parseLong(offset));
    }

    public enum Kind {TIME, OBJECT, MISS}
}
