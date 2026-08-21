package xyz.zcraft.ostella.util.format;

import xyz.zcraft.ostella.util.Colors;
import xyz.zcraft.osu.model.Mod;

public class ModFormatUtil {
    public static String getColorHex(Mod mod) {
        return Colors.getModColor(mod.getAcronym());
    }

    public static String getColorHex(String acronym) {
        return Colors.getModColor(acronym);
    }

    public static String getTextColorHex(Mod mod) {
        return Colors.getModTextColor(mod.getAcronym());
    }

    public static String getModIcon(Mod mod) {
        return getModIcon(mod.getAcronym());
    }

    public static String getModIcon(String acronym) {
        //https://osu.ppy.sh/assets/images/mod-icon.dacd6669.svg
        return switch (acronym) {
            case "EZ" -> "https://osu.ppy.sh/assets/images/mod-easy.92150de2.svg";
            case "NF" -> "https://osu.ppy.sh/assets/images/mod-no-fail.325de5a8.svg";
            case "HT" -> "https://osu.ppy.sh/assets/images/mod-half-time.680aa75e.svg";
            case "DC" -> "https://osu.ppy.sh/assets/images/mod-daycore.9c82ffc8.svg";
            case "HR" -> "https://osu.ppy.sh/assets/images/mod-hard-rock.46546bbc.svg";
            case "SD" -> "https://osu.ppy.sh/assets/images/mod-sudden-death.93286ad6.svg";
            case "PF" -> "https://osu.ppy.sh/assets/images/mod-perfect.8c11b369.svg";
            case "DT" -> "https://osu.ppy.sh/assets/images/mod-double-time.895bdda1.svg";
            case "NC" -> "https://osu.ppy.sh/assets/images/mod-nightcore.d992ee1a.svg";
            case "HD" -> "https://osu.ppy.sh/assets/images/mod-hidden.823bd26e.svg";
            case "TC" -> "https://osu.ppy.sh/assets/images/mod-traceable.7e0d7ee4.svg";
            case "FL" -> "https://osu.ppy.sh/assets/images/mod-flashlight.de7d21b1.svg";
            case "BL" -> "https://osu.ppy.sh/assets/images/mod-blinds.88a04b37.svg";
            case "ST" -> "https://osu.ppy.sh/assets/images/mod-strict-tracking.f4d72427.svg";
            case "AC" -> "https://osu.ppy.sh/assets/images/mod-accuracy-challenge.74b68b1a.svg";
            case "AT" -> "";
            case "CN" -> "";
            case "RX" -> "https://osu.ppy.sh/assets/images/mod-relax.931824f3.svg";
            case "AP" -> "https://osu.ppy.sh/assets/images/mod-autopilot.4d43baa9.svg";
            case "SO" -> "https://osu.ppy.sh/assets/images/mod-spun-out.b62218a0.svg";
            case "TP" -> "https://osu.ppy.sh/assets/images/mod-target-practice.aaa2dd92.svg";
            case "DA" -> "https://osu.ppy.sh/assets/images/mod-difficulty-adjust.141b8620.svg";
            case "CL" -> "https://osu.ppy.sh/assets/images/mod-classic.f5f25b6c.svg";
            case "RD" -> "https://osu.ppy.sh/assets/images/mod-random.9db2b428.svg";
            case "MR" -> "https://osu.ppy.sh/assets/images/mod-mirror.c4cf36ba.svg";
            case "AL" -> "https://osu.ppy.sh/assets/images/mod-alternate.b7f824fd.svg";
            case "SG" -> "https://osu.ppy.sh/assets/images/mod-single-tap.4f3cc54d.svg";
            case "TR" -> "https://osu.ppy.sh/assets/images/mod-transform.db3daacc.svg";
            case "WG" -> "https://osu.ppy.sh/assets/images/mod-wiggle.9a6ac38c.svg";
            case "SI" -> "https://osu.ppy.sh/assets/images/mod-spin-in.6385bf5d.svg";
            case "GR" -> "https://osu.ppy.sh/assets/images/mod-grow.102b93f9.svg";
            case "DF" -> "https://osu.ppy.sh/assets/images/mod-deflate.e1bc9923.svg";
            case "WU" -> "https://osu.ppy.sh/assets/images/mod-wind-up.84949cad.svg";
            case "WD" -> "https://osu.ppy.sh/assets/images/mod-wind-down.be29a432.svg";
            case "BR" -> "https://osu.ppy.sh/assets/images/mod-barrel-roll.05dda62a.svg";
            case "AD" -> "https://osu.ppy.sh/assets/images/mod-approach-different.30320c23.svg";
            case "MU" -> "https://osu.ppy.sh/assets/images/mod-muted.04898964.svg";
            case "NS" -> "https://osu.ppy.sh/assets/images/mod-no-scope.4113573e.svg";
            case "MG" -> "https://osu.ppy.sh/assets/images/mod-magnetised.8ed278d7.svg";
            case "RP" -> "https://osu.ppy.sh/assets/images/mod-repel.d7444b42.svg";
            case "AS" -> "https://osu.ppy.sh/assets/images/mod-adaptive-speed.ea159754.svg";
            case "FR" -> "https://osu.ppy.sh/assets/images/mod-freeze-frame.12116f6c.svg";
            case "BU" -> "https://osu.ppy.sh/assets/images/mod-bubbles.055f75af.svg";
            case "SY" -> "https://osu.ppy.sh/assets/images/mod-synesthesia.0283311f.svg";
            case "DP" -> "https://osu.ppy.sh/assets/images/mod-depth.0cfcae1d.svg";
            case "BM" -> "https://osu.ppy.sh/assets/images/mod-bloom.a02383c1.svg";
            case "TD" -> "https://osu.ppy.sh/assets/images/mod-touch-device.ceb86291.svg";
            case "V2" -> "https://picui.ogmua.cn/s1/2026/08/12/6a7b4bb14fc57.webp";
            default -> "https://osu.ppy.sh/assets/images/mod-no-mod.3634af18.svg";
        };
    }
}

