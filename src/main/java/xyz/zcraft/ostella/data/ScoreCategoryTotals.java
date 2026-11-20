package xyz.zcraft.ostella.data;

public record ScoreCategoryTotals<T extends Number>(T ranked, T unranked, T local) {
}
