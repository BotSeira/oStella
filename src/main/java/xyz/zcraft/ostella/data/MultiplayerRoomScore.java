package xyz.zcraft.ostella.data;

import xyz.zcraft.osu.model.Score;

public record MultiplayerRoomScore(Score score, Integer position, String team) {
    public MultiplayerRoomScore(Score score, Integer position) {
        this(score, position, null);
    }
}
