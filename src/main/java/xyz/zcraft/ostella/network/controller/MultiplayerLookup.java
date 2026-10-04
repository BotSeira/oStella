package xyz.zcraft.ostella.network.controller;

import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.osu.model.multiplayer.Room;

final class MultiplayerLookup {
    private MultiplayerLookup() { }

    static Long roomId(String value) {
        if (value == null) return null;
        try {
            if (value.matches("[0-9]+")) {
                long id = Long.parseLong(value);
                if (id > 0) return id;
            }
        } catch (NumberFormatException ignored) { }
        throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "room must be a positive integer");
    }

    static Room.PlaylistItem currentItem(Room room) {
        if (room == null) throw new ApiException(ErrorCode.NO_ROOM_FOUND);
        var item = room.getCurrentPlaylistItem();
        if (item == null || item.getBeatmapId() <= 0)
            throw new ApiException(ErrorCode.NO_BEATMAP_FOUND, "Room has no current beatmap");
        return item;
    }
}
