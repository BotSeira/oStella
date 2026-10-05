package xyz.zcraft.ostella.addpp;

import xyz.zcraft.osu.model.ModSettings;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Optional inputs for rosu-pp; omitted values remain unset.
 */
public record MapConditions(String mods, Double accuracy,
                            Integer great, Integer ok, Integer meh, Integer misses, Integer combo, boolean fc) {
    private static final Pattern ACC = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final Pattern COUNT = Pattern.compile("(\\d+)(great|ok|meh|miss|misses|x|combo)");
    private static final Set<String> MODS = Set.of("NF", "EZ", "HD", "HR", "SD", "DT", "NC", "HT", "FL", "SO", "PF", "CL", "MR", "DA", "DC");

    public static MapConditions parse(List<String> tokens) {
        if (tokens == null) tokens = List.of();
        if (tokens.size() > 20) throw new IllegalArgumentException("成绩条件过多。");
        String mods = "";
        Double accuracy = null;
        Integer great = null, ok = null, meh = null, misses = null, combo = null;
        boolean fc = false, hasMods = false;
        Set<String> seen = new HashSet<>();
        for (String raw : tokens) {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("成绩条件不能为空。");
            String token = raw.toLowerCase(Locale.ROOT);
            var acc = ACC.matcher(token);
            var value = COUNT.matcher(token);
            String key;
            if (acc.matches()) {
                key = "ACC";
                accuracy = Double.parseDouble(acc.group(1));
                if (!Double.isFinite(accuracy) || accuracy < 0 || accuracy > 100)
                    throw new IllegalArgumentException("ACC 必须在 0%–100% 之间。");
            } else if (value.matches()) {
                int number;
                try {
                    number = Integer.parseInt(value.group(1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("判定数量或 Combo 过大。");
                }
                key = switch (value.group(2)) {
                    case "great" -> "300";
                    case "ok" -> "100";
                    case "meh" -> "50";
                    case "miss", "misses" -> "Miss";
                    default -> "Combo";
                };
                switch (key) {
                    case "300" -> great = number;
                    case "100" -> ok = number;
                    case "50" -> meh = number;
                    case "Miss" -> misses = number;
                    case "Combo" -> combo = number;
                }
            } else if (token.equals("fc")) {
                key = "FC";
                fc = true;
            } else if (token.matches("\\+?[a-z].*")) {
                key = "Mod";
                if (hasMods) throw new IllegalArgumentException("Mod 重复，请合并为 HDDT 等一个条件。");
                mods = parseMods(raw);
                hasMods = true;
            } else
                throw new IllegalArgumentException("无法识别条件「" + raw + "」。支持 HDDT、97.41%、13miss、18ok、2meh、1200x、FC。");
            if (!seen.add(key)) throw new IllegalArgumentException(key + " 重复或冲突，请只提供一次。");
        }
        int miss = misses == null ? 0 : misses;
        if (fc && miss > 0) throw new IllegalArgumentException("FC 与 Miss 冲突。");
        if ((mods.contains("SD") || mods.contains("PF")) && miss > 0)
            throw new IllegalArgumentException("SD/PF 与有 Miss 的通过成绩冲突。");
        if (mods.contains("PF") && ((accuracy != null && accuracy < 100) || (ok != null && ok > 0) || (meh != null && meh > 0)))
            throw new IllegalArgumentException("PF 需要 100% 且无 100/50 判定。");
        if (accuracy != null && accuracy == 100 && (miss > 0 || (ok != null && ok > 0) || (meh != null && meh > 0)))
            throw new IllegalArgumentException("100% ACC 与非 300 判定冲突。");
        return new MapConditions(mods, accuracy, great, ok, meh, misses, combo, fc);
    }

    private static String parseMods(String token) {
        var parsed = ModSettings.parse(token);
        for (var mod : parsed)
            if (!MODS.contains(mod.getAcronym()))
                throw new IllegalArgumentException("不支持计算 PP 的 Mod: " + mod.getAcronym());
        return ModSettings.format(parsed);
    }

    /**
     * Check only explicit contradictions and obvious map bounds, without filling inputs.
     */
    public void validate(int objects, int maxCombo) {
        if (objects <= 0 || objects > 1_000_000 || maxCombo <= 0)
            throw new IllegalArgumentException("谱面物件数量无效。");
        int miss = misses == null ? 0 : misses;
        if ((long) miss + (great == null ? 0 : great) + (ok == null ? 0 : ok) + (meh == null ? 0 : meh) > objects)
            throw new IllegalArgumentException("判定冲突：指定的判定总数超过谱面物件数（" + objects + "）。");
        if (combo != null && (combo < 0 || combo > maxCombo - miss))
            throw new IllegalArgumentException("Combo 冲突：该谱面在指定 Miss 下最多 " + (maxCombo - miss) + "x。");
        if ((fc || mods.contains("PF")) && combo != null && combo != maxCombo)
            throw new IllegalArgumentException("FC/PF 与非满 Combo 冲突。");
    }

    public record Resolved(int great, int ok, int meh, int misses, int combo, double accuracy) {
    }
}
