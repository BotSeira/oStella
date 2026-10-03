package xyz.zcraft.ostella.addpp;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Stable/CL hit results: explicit values are constraints, never silently overwritten.
 */
public record MapConditions(String mods, Double accuracy, double accuracyTolerance,
                            Integer great, Integer ok, Integer meh, int misses, Integer combo, boolean fc) {
    private static final Pattern ACC = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final Pattern COUNT = Pattern.compile("(\\d+)(great|ok|meh|miss|misses|x|combo)");
    private static final Set<String> MODS = Set.of("NF", "EZ", "HD", "HR", "SD", "DT", "NC", "HT", "FL", "SO", "PF", "CL", "MR");

    public static MapConditions parse(List<String> tokens) {
        if (tokens == null || tokens.size() > 20) throw new IllegalArgumentException("成绩条件过多或缺失。");
        String mods = "";
        Double accuracy = null;
        double tolerance = 0;
        Integer great = null, ok = null, meh = null, misses = null, combo = null;
        boolean fc = false, hasMods = false;
        Set<String> seen = new HashSet<>();
        for (String raw : tokens) {
            String token = raw.toLowerCase(Locale.ROOT);
            var acc = ACC.matcher(token);
            var value = COUNT.matcher(token);
            String key;
            if (acc.matches()) {
                key = "ACC";
                accuracy = Double.parseDouble(acc.group(1));
                if (!Double.isFinite(accuracy) || accuracy < 0 || accuracy > 100)
                    throw new IllegalArgumentException("ACC 必须在 0%–100% 之间。");
                int dot = acc.group(1).indexOf('.');
                int precision = dot < 0 ? 0 : acc.group(1).length() - dot - 1;
                tolerance = .5 * Math.pow(10, -precision) + 1e-9;
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
            } else if (token.matches("\\+?[a-z]+")) {
                key = "Mod";
                if (hasMods) throw new IllegalArgumentException("Mod 重复，请合并为 HDDT 等一个条件。");
                mods = parseMods(token);
                hasMods = true;
            } else
                throw new IllegalArgumentException("无法识别条件「" + raw + "」。支持 HDDT、97.41%、13miss、18ok、2meh、1200x、FC。");
            if (!seen.add(key)) throw new IllegalArgumentException(key + " 重复或冲突，请只提供一次。");
        }
        int miss = misses == null ? 0 : misses;
        if (fc && miss > 0) throw new IllegalArgumentException("FC 与 Miss 冲突。");
        if (miss > 0 && combo == null)
            throw new IllegalArgumentException("条件不足：有 Miss 时请提供最大 Combo，例如 1200x。");
        if (accuracy == null && great == null && ok == null && meh == null && misses == null)
            throw new IllegalArgumentException("条件不足：请提供 ACC（如 98%）或判定数量（如 18ok 2meh 0miss）。");
        if ((mods.contains("SD") || mods.contains("PF")) && miss > 0)
            throw new IllegalArgumentException("SD/PF 与有 Miss 的通过成绩冲突。");
        if (mods.contains("PF") && ((accuracy != null && accuracy < 100) || (ok != null && ok > 0) || (meh != null && meh > 0)))
            throw new IllegalArgumentException("PF 需要 100% 且无 100/50 判定。");
        return new MapConditions(mods, accuracy, tolerance, great, ok, meh, miss, combo, fc);
    }

    private static String parseMods(String token) {
        String value = token.replace("+", "").toUpperCase(Locale.ROOT);
        if (value.equals("NM")) return "";
        if (value.length() % 2 != 0) throw new IllegalArgumentException("无法识别 Mod「" + value + "」。");
        Set<String> mods = new LinkedHashSet<>();
        for (int i = 0; i < value.length(); i += 2) {
            String mod = value.substring(i, i + 2);
            if (!MODS.contains(mod))
                throw new IllegalArgumentException("不支持 Mod「" + mod + "」；仅接受可获得 PP 的 osu!standard Mod 默认设置。");
            if (!mods.add(mod)) throw new IllegalArgumentException("Mod「" + mod + "」重复。");
        }
        for (Set<String> conflict : List.of(Set.of("HR", "EZ"), Set.of("DT", "HT"), Set.of("NC", "HT"),
                Set.of("NF", "SD"), Set.of("NF", "PF")))
            if (mods.containsAll(conflict))
                throw new IllegalArgumentException("Mod 冲突：" + String.join("/", conflict) + "。");
        return String.join("", mods);
    }

    private static double acc(int n, int g, int o, int f) {
        return 100.0 * (6.0 * g + 2.0 * o + f) / (6.0 * n);
    }

    public Resolved resolve(int objects, int maxCombo) {
        if (objects <= 0 || objects > 1_000_000 || maxCombo <= 0)
            throw new IllegalArgumentException("谱面物件数量无效。");
        if (misses > objects || (long) misses + (great == null ? 0 : great) + (ok == null ? 0 : ok) + (meh == null ? 0 : meh) > objects)
            throw new IllegalArgumentException("判定冲突：指定的判定总数超过谱面物件数（" + objects + "）。");
        int resolvedCombo = combo == null ? maxCombo : combo;
        if (resolvedCombo < 0 || resolvedCombo > maxCombo - misses)
            throw new IllegalArgumentException("Combo 冲突：该谱面在指定 Miss 下最多 " + (maxCombo - misses) + "x。");
        if ((fc || mods.contains("PF")) && resolvedCombo != maxCombo)
            throw new IllegalArgumentException("FC/PF 与非满 Combo 冲突。");
        int g = -1, o = -1, f = -1;
        if (accuracy == null) {
            o = ok == null ? 0 : ok;
            f = meh == null ? 0 : meh;
            g = great == null ? objects - misses - o - f : great;
            if ((long) g + o + f + misses != objects)
                throw new IllegalArgumentException("判定冲突：判定总数必须等于谱面物件数（" + objects + "）。");
        } else {
            double bestError = Double.POSITIVE_INFINITY;
            int start = meh == null ? 0 : meh, end = meh == null ? objects - misses : meh;
            for (int candidate50 = start; candidate50 <= end; candidate50++) {
                int candidate100 = ok != null ? ok : great != null ? objects - misses - great - candidate50
                        : (int) Math.round((6 * objects * (1 - accuracy / 100) - 6 * misses - 5 * candidate50) / 4);
                candidate100 = ok == null && great == null ? Math.clamp(candidate100, 0, objects - misses - candidate50) : candidate100;
                int candidate300 = objects - misses - candidate100 - candidate50;
                if (candidate100 < 0 || candidate300 < 0 || (great != null && candidate300 != great)) continue;
                double error = Math.abs(acc(objects, candidate300, candidate100, candidate50) - accuracy);
                if (error < bestError - 1e-10) {
                    bestError = error;
                    g = candidate300;
                    o = candidate100;
                    f = candidate50;
                }
            }
            if (g < 0 || bestError > accuracyTolerance)
                throw new IllegalArgumentException("ACC 与判定冲突：在 " + objects + " 个物件下，指定判定无法得到该 ACC；请检查 ACC/100/50/Miss。");
        }
        double actual = acc(objects, g, o, f);
        if (mods.contains("PF") && actual < 100) throw new IllegalArgumentException("PF 需要全 300 判定。");
        return new Resolved(g, o, f, misses, resolvedCombo, actual);
    }

    public record Resolved(int great, int ok, int meh, int misses, int combo, double accuracy) {
    }
}
