package xyz.zcraft.ostella.snapshot;

import com.google.gson.Gson;
import desu.life.RosuFFI;
import xyz.zcraft.osu.parser.ReplayAnalyzer;
import xyz.zcraft.osu.parser.data.PerformanceState;
import xyz.zcraft.osu.parser.data.beatmap.HitObject;
import xyz.zcraft.osu.parser.data.replay.HitEvent;
import xyz.zcraft.osu.parser.data.replay.OsuReplay;
import xyz.zcraft.osu.parser.data.replay.ReplayAnalyze;

import java.util.*;

/**
 * Reconstructs only state known at the selected song time; it never uses future hits.
 */
public final class SnapshotScene {
    private final ReplayAnalyze analyze;
    private final List<OsuReplay.TimedKeyFrame> frames;
    private final Map<Integer, List<HitEvent>> eventsByObject = new HashMap<>();
    private final List<ObjectState> objects = new ArrayList<>();
    private final List<Judgement> judgements = new ArrayList<>();
    private final List<double[]> errors = new ArrayList<>();
    private final long time;
    private final double radius, preempt, clockRate;
    private final boolean hardRock, hidden, lazer, sliderHeadAccuracy, authoritativeResults;
    private final Cursor cursor;
    private final PerformanceState performance = new PerformanceState();
    private final long endTime;
    private double pp;
    private int completedObjects;
    private int largeTickHits, largeTicks, tailHits, tails;

    public SnapshotScene(ReplayAnalyze analyze, SnapshotRequest request, boolean calculatePp) {
        this.analyze = Objects.requireNonNull(analyze);
        if (analyze.replay().gameMode() != 0 || (analyze.beatmap().getMode() != null && analyze.beatmap().getMode() != 0)) {
            throw new IllegalArgumentException("Replay snapshots support osu!standard only");
        }
        frames = analyze.replay().timedKeyFrames().stream()
                .filter(f -> Float.isFinite(f.cursorX()) && Float.isFinite(f.cursorY()))
                .sorted(Comparator.comparingLong(OsuReplay.TimedKeyFrame::time)).toList();
        if (frames.isEmpty() || analyze.beatmap().getHitObjects().isEmpty()) {
            throw new IllegalArgumentException("Replay or beatmap contains no playable data");
        }
        int mods = ReplayAnalyzer.effectiveLegacyMods(analyze.replay());
        hardRock = (mods & 16) != 0;
        hidden = (mods & 8) != 0;
        lazer = analyze.replay().replayInfo() != null;
        sliderHeadAccuracy = lazer && (analyze.replay().replayInfo().mods() == null
                || analyze.replay().replayInfo().mods().stream().noneMatch(m -> "CL".equals(m.getAcronym())
                && (m.getSettings() == null || !Boolean.FALSE.equals(m.getSettings().get("no_slider_head_accuracy")))));
        var replay = analyze.replay();
        authoritativeResults = Short.toUnsignedInt(replay.count300()) + Short.toUnsignedInt(replay.count100())
                + Short.toUnsignedInt(replay.count50()) + Short.toUnsignedInt(replay.countMiss()) == analyze.beatmap().getHitObjects().size();
        if ((mods & (1024 | 1 << 21 | 1 << 30)) != 0) {
            throw new IllegalArgumentException("Flashlight, Random and Mirror replay snapshots are not supported");
        }
        if (lazer && replay.replayInfo().mods() != null) {
            Set<String> supported = Set.of("NM", "NF", "EZ", "TD", "HD", "HR", "SD", "DT", "RX", "HT", "NC", "AT", "AP", "SO", "PF", "CL", "V2", "DA", "DC");
            for (var mod : replay.replayInfo().mods()) {
                if (!supported.contains(mod.getAcronym()))
                    throw new IllegalArgumentException("Unsupported snapshot mod: " + mod.getAcronym());
            }
        }
        radius = analyze.calculatedDifficulty().getCircleRadiusInPixel();
        clockRate = analyze.calculatedDifficulty().clockRate();
        double ar = analyze.calculatedDifficulty().ar();
        preempt = (ar < 5 ? 1800 - ar * 120 : 1200 - (ar - 5) * 150) * clockRate;
        for (var event : analyze.events())
            eventsByObject.computeIfAbsent(event.objectIndex(), _ -> new ArrayList<>()).add(event);
        endTime = (long) Math.ceil(analyze.beatmap().getHitObjects().stream().mapToDouble(this::endOf).max().orElse(0));
        time = resolveTime(request);
        long maximumTime = endTime + Math.round(1000 * clockRate);
        if (!authoritativeResults) maximumTime = Math.min(maximumTime, frames.getLast().time());
        if (time < 0 || time > maximumTime) {
            throw new IllegalArgumentException("Snapshot time is outside the recorded song range: 0–" + maximumTime + "ms");
        }
        cursor = cursorAt(time);
        buildObjects();
        buildCounters();
        if (time >= endTime && authoritativeResults && Short.toUnsignedInt(replay.maxCombo()) > 0) {
            performance.maxCombo = Short.toUnsignedInt(replay.maxCombo());
        }
        if (calculatePp && completedObjects > 0) {
            pp = calculatePp(mods);
        }
    }

    static HitEvent.HitResult sliderResult(List<HitEvent> events) {
        long total = events.stream().filter(e -> e.eventType() == HitEvent.EventType.SLIDER_HEAD
                || e.eventType() == HitEvent.EventType.SLIDER_TICK || e.eventType() == HitEvent.EventType.SLIDER_END).count();
        long hit = events.stream().filter(e -> e.wasHit() && (e.eventType() == HitEvent.EventType.SLIDER_HEAD
                || e.eventType() == HitEvent.EventType.SLIDER_TICK || e.eventType() == HitEvent.EventType.SLIDER_END)).count();
        return total > 0 && hit == total ? HitEvent.HitResult.PERFECT : hit > 0 && hit * 2 >= total
                ? HitEvent.HitResult.OK : hit > 0 ? HitEvent.HitResult.MEH : HitEvent.HitResult.MISS;
    }

    public static int overlayKeys(int raw) {
        return ((raw & 4) != 0 ? 1 : 0) | ((raw & 8) != 0 ? 2 : 0)
                | ((raw & 1) != 0 && (raw & 4) == 0 ? 4 : 0)
                | ((raw & 2) != 0 && (raw & 8) == 0 ? 8 : 0);
    }

    public static double clamp(double value) {
        return Math.clamp(value, 0, 1);
    }

    private long resolveTime(SnapshotRequest request) {
        long selected;
        if (request.kind() == SnapshotRequest.Kind.TIME) selected = request.value();
        else if (request.kind() == SnapshotRequest.Kind.OBJECT) {
            if (request.value() > analyze.beatmap().getHitObjects().size())
                throw new IllegalArgumentException("Object index is out of range");
            selected = analyze.beatmap().getHitObjects().get((int) request.value() - 1).getTime();
        } else {
            var misses = analyze.misses();
            if (request.value() > misses.size())
                throw new IllegalArgumentException("Miss index is out of range (1–" + misses.size() + ")");
            selected = (long) Math.ceil(judgedAt(misses.get((int) request.value() - 1)) + 1);
        }
        return Math.addExact(selected, request.offset());
    }

    public double judgedAt(HitEvent event) {
        if (event.eventType() == HitEvent.EventType.SPINNER) return event.hitObject().getEndTime();
        if ((event.wasHit() || !event.isObjectStart()) && event.hitTime() >= 0) return event.hitTime();
        if (event.eventType() == HitEvent.EventType.HIT_CIRCLE || event.eventType() == HitEvent.EventType.SLIDER_HEAD) {
            return event.hitObject().getTime() + Math.floor(analyze.calculatedDifficulty().getMehWindow() * clockRate);
        }
        return event.eventTime();
    }

    public Cursor cursorAt(double at) {
        int low = 0, high = frames.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).time() <= at) low = middle + 1;
            else high = middle;
        }
        if (low == 0) return new Cursor(frames.getFirst().cursorX(), frames.getFirst().cursorY(), 0);
        var before = frames.get(low - 1);
        if (low == frames.size()) return new Cursor(before.cursorX(), before.cursorY(), before.key());
        var after = frames.get(low);
        double fraction = Math.clamp((at - before.time()) / Math.max(1, after.time() - before.time()), 0, 1);
        return new Cursor(before.cursorX() + fraction * (after.cursorX() - before.cursorX()),
                before.cursorY() + fraction * (after.cursorY() - before.cursorY()), before.key());
    }

    private double endOf(HitObject object) {
        return switch (object.getObjectType()) {
            case HIT_CIRCLE -> object.getTime();
            case SLIDER -> object.getTime() + ReplayAnalyzer.sliderDuration(analyze.beatmap(), object);
            case SPINNER -> object.getEndTime();
        };
    }

    private void buildObjects() {
        double mapAr = preempt < 1200 ? 5 + (1200 - preempt) / 150 : (1800 - preempt) / 120;
        int[] stacks = ReplayAnalyzer.calculateStackHeights(analyze.beatmap(), mapAr);
        double stackUnit = (1 - .7 * (analyze.calculatedDifficulty().cs() - 5) / 5) / 2 * 6.4;
        int number = 0, colour = -1;
        boolean previousSpinner = true;
        var hitObjects = analyze.beatmap().getHitObjects();
        for (int index = 0; index < hitObjects.size(); index++) {
            var object = hitObjects.get(index);
            boolean spinner = object.getObjectType() == HitObject.ObjectType.SPINNER;
            if (!spinner && (object.isNewCombo() || previousSpinner)) {
                colour += 1 + ((object.getTypeFlag() >> 4) & 7);
                number = 0;
            }
            number++;
            previousSpinner = spinner;
            double stack = stacks[index] * stackUnit;
            double x = object.getX() - stack, y = (hardRock ? 384 - object.getY() : object.getY()) - stack;
            double start = object.getTime(), end = endOf(object);
            List<HitEvent> events = eventsByObject.getOrDefault(index, List.of());
            HitEvent head = events.stream().filter(HitEvent::isObjectStart).findFirst().orElse(null);
            double judge = head == null ? start + analyze.calculatedDifficulty().getMehWindow() * clockRate : judgedAt(head);
            double age = time - (start - preempt);
            double fadeIn = hidden && !spinner ? preempt * .4 : Math.min(400, preempt);
            double alpha = clamp(age / fadeIn), scale = 1;
            if (hidden && !spinner) alpha *= clamp(1 - (age - fadeIn) / (preempt * .3));
            boolean headVisible = time < judge + (head != null && head.wasHit() ? 240 : 120) * clockRate;
            if (time >= judge && head != null) {
                alpha *= clamp(1 - (time - judge) / ((head.wasHit() ? 240 : 120) * clockRate));
                if (head.wasHit()) scale += .5 * Math.pow(clamp((time - judge) / (240 * clockRate)), .4);
            }
            if (time >= start && time < judge) alpha = hidden ? alpha : 1;
            double approachScale = 1 + 3 * clamp((start - time) / preempt);
            double approachAlpha = time < judge && (!hidden || index == 0)
                    ? .9 * clamp(age / Math.min(800, preempt)) * clamp(1 - (time - start) / (50 * clockRate)) : 0;
            double bodyAlpha = clamp(age / Math.min(400, preempt));
            if (hidden && !spinner) bodyAlpha *= clamp(1 - (time - (start - preempt + Math.min(400, preempt)))
                    / Math.max(1, end - (start - preempt + Math.min(400, preempt))));
            if (time >= end) bodyAlpha *= clamp(1 - (time - end) / (240 * clockRate));
            if (spinner) {
                alpha = bodyAlpha;
                headVisible = time < end + 240 * clockRate;
            }
            ReplayAnalyzer.SliderPath path = object.getObjectType() == HitObject.ObjectType.SLIDER
                    ? new ReplayAnalyzer.SliderPath(object, stack, hardRock) : null;
            double duration = end - start;
            double progress = path == null ? 0 : ReplayAnalyzer.sliderProgress(object, duration, time);
            // Stable-style snake-in finishes during the first third of preempt.
            double snakeEnd = clamp(age / Math.max(1, preempt / 3));
            double snakeStart = 0;
            int spans = Math.max(1, object.getSlides());
            if (path != null && time >= start && duration > 0) {
                double spanTime = duration / spans;
                int span = Math.min(spans - 1, (int) ((time - start) / spanTime));
                if (span == spans - 1) {
                    if ((span & 1) == 0) snakeStart = progress;
                    else snakeEnd = progress;
                }
            }
            boolean following = path != null && time >= start && time < end && isFollowing(path, object, duration, head);
            if (head != null && (path == null || sliderHeadAccuracy) && time >= judge && time - judge < 1000 * clockRate) {
                judgements.add(new Judgement(index, judge, head.hitResult(), x, y));
            }
            if (path != null && !sliderHeadAccuracy && time >= end && time - end < 1000 * clockRate) {
                var point = path.positionAt((spans & 1) == 1 ? 1 : 0);
                judgements.add(new Judgement(index, end, finalSliderResult(events, head), point.x(), point.y()));
            }
            if (age < 0 || (!headVisible && bodyAlpha <= 0) || (time > end + 1000 * clockRate)) continue;
            objects.add(new ObjectState(index, object, x, y, number, Math.max(0, colour), start, end,
                    alpha, bodyAlpha, scale, approachScale, approachAlpha, headVisible,
                    snakeStart, snakeEnd, progress, following, path));
        }
    }

    private boolean isFollowing(ReplayAnalyzer.SliderPath path, HitObject object, double duration, HitEvent head) {
        boolean tracking = head != null && head.wasHit() && judgedAt(head) <= object.getTime();
        boolean automatic = (ReplayAnalyzer.effectiveLegacyMods(analyze.replay()) & (128 | 2048)) != 0;
        for (var frame : frames) {
            if (frame.time() < object.getTime()) continue;
            if (frame.time() > time) break;
            var ball = path.positionAt(ReplayAnalyzer.sliderProgress(object, duration, frame.time()));
            double distance = Math.hypot(frame.cursorX() - ball.x(), frame.cursorY() - ball.y());
            tracking = (automatic || (frame.key() & 15) != 0)
                    && distance <= (tracking ? radius * 2.4 + Math.clamp(37 - radius, 0, 5) : radius);
        }
        var ball = path.positionAt(ReplayAnalyzer.sliderProgress(object, duration, time));
        return (automatic || (cursor.keys() & 15) != 0)
                && Math.hypot(cursor.x() - ball.x(), cursor.y() - ball.y())
                <= (tracking ? radius * 2.4 + Math.clamp(37 - radius, 0, 5) : radius);
    }

    private void buildCounters() {
        var chronological = analyze.events().stream().sorted(Comparator.comparingDouble(this::judgedAt)).toList();
        for (var event : chronological) {
            if (judgedAt(event) > time) continue;
            if (ReplayAnalyzer.isComboEvent(event) && !(sliderHeadAccuracy && event.eventType() == HitEvent.EventType.SLIDER_END)) {
                if (event.wasHit()) performance.maxCombo = Math.max(performance.maxCombo, ++performance.currentCombo);
                else performance.currentCombo = 0;
            }
            if (event.eventType() == HitEvent.EventType.SLIDER_TICK
                    || (lazer && !sliderHeadAccuracy && event.eventType() == HitEvent.EventType.SLIDER_HEAD)) {
                largeTicks++;
                if (event.wasHit()) largeTickHits++;
            } else if (event.eventType() == HitEvent.EventType.SLIDER_END) {
                tails++;
                if (event.wasHit()) tailHits++;
            }
            if (event.wasHit() && event.isObjectStart() && event.eventType() != HitEvent.EventType.SPINNER
                    && time - event.hitTime() <= 10000 * clockRate) {
                errors.add(new double[]{event.hitTime(), event.hitTimeOffset() / clockRate});
            }
        }
        var hitObjects = analyze.beatmap().getHitObjects();
        for (int i = 0; i < hitObjects.size(); i++) {
            var object = hitObjects.get(i);
            var events = eventsByObject.getOrDefault(i, List.of());
            var head = events.stream().filter(HitEvent::isObjectStart).findFirst().orElse(null);
            if (head == null) continue;
            double completed = object.getObjectType() == HitObject.ObjectType.SLIDER && !sliderHeadAccuracy ? endOf(object) : judgedAt(head);
            if (completed > time) continue;
            completedObjects++;
            var result = object.getObjectType() == HitObject.ObjectType.SLIDER ? finalSliderResult(events, head) : head.hitResult();
            switch (result) {
                case PERFECT -> performance.n300++;
                case OK -> performance.n100++;
                case MEH -> performance.n50++;
                case MISS -> performance.misses++;
            }
        }
    }

    private HitEvent.HitResult finalSliderResult(List<HitEvent> events, HitEvent head) {
        // The analyzer reconciles complete replays to their embedded judgement totals.
        return head != null && (authoritativeResults || sliderHeadAccuracy) ? head.hitResult() : sliderResult(events);
    }

    private double calculatePp(int mods) {
        try (var map = new RosuFFI.Beatmap(analyze.beatmap().toBeatmapString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
             var rosuMods = lazer && analyze.replay().replayInfo().mods() != null
                     ? RosuFFI.Mods.fromJson(new Gson().toJson(analyze.replay().replayInfo().mods()), RosuFFI.Mode.Osu, false)
                     : RosuFFI.Mods.fromBits(mods, RosuFFI.Mode.Osu);
             var calculation = new RosuFFI.Performance()) {
            calculation.mods(rosuMods);
            calculation.lazer(lazer);
            ReplayAnalyzer.applyPerformanceState(calculation, performance, completedObjects, performance.maxCombo);
            calculation.largeTickHits(largeTickHits);
            calculation.smallTickHits(sliderHeadAccuracy ? 0 : tailHits);
            calculation.sliderEndHits(sliderHeadAccuracy ? tailHits : 0);
            return calculation.calculate(map).asOsu().pp;
        }
    }

    public int[] keyCounts() {
        int[] counts = new int[4];
        int previous = 0;
        for (var frame : frames) {
            if (frame.time() > time) break;
            int keys = overlayKeys(frame.key());
            for (int i = 0; i < 4; i++) if ((keys & (1 << i)) != 0 && (previous & (1 << i)) == 0) counts[i]++;
            previous = keys;
        }
        return counts;
    }

    public ReplayAnalyze analyze() {
        return analyze;
    }

    public List<ObjectState> objects() {
        return List.copyOf(objects);
    }

    public List<Judgement> judgements() {
        return List.copyOf(judgements);
    }

    public List<double[]> errors() {
        return List.copyOf(errors);
    }

    public long maximumTime() {
        long maximum = endTime + Math.round(1000 * clockRate);
        return authoritativeResults ? maximum : Math.min(maximum, frames.getLast().time());
    }

    public long time() {
        return time;
    }

    public long endTime() {
        return endTime;
    }

    public double radius() {
        return radius;
    }

    public double preempt() {
        return preempt;
    }

    public double clockRate() {
        return clockRate;
    }

    public Cursor cursor() {
        return cursor;
    }

    public PerformanceState performance() {
        return performance;
    }

    public double pp() {
        return pp;
    }

    public double accuracy() {
        double numerator = performance.n300 * 300.0 + performance.n100 * 100 + performance.n50 * 50;
        double denominator = completedObjects * 300.0;
        if (lazer) {
            numerator += largeTickHits * 30.0 + tailHits * (sliderHeadAccuracy ? 150.0 : 10.0);
            denominator += largeTicks * 30.0 + tails * (sliderHeadAccuracy ? 150.0 : 10.0);
        }
        return denominator == 0 ? 1 : numerator / denominator;
    }

    public double progress() {
        return clamp((double) time / Math.max(1, endTime));
    }

    public Map<String, Object> responseData() {
        return Map.of("time", time, "endTime", endTime, "clockRate", clockRate, "preempt", preempt,
                "radius", radius, "cursor", cursor, "pp", pp, "accuracy", accuracy(),
                "combo", performance.currentCombo, "visibleObjects", objects.stream().map(o -> Map.of(
                        "index", o.index() + 1, "type", o.object().getObjectType(), "x", o.x(), "y", o.y(),
                        "alpha", o.alpha(), "bodyAlpha", o.bodyAlpha(), "progress", o.progress(), "following", o.following())).toList());
    }

    public record Cursor(double x, double y, int keys) {
    }

    public record Judgement(int objectIndex, double time, HitEvent.HitResult result, double x, double y) {
    }

    public record ObjectState(int index, HitObject object, double x, double y, int number, int colour,
                              double start, double end, double alpha, double bodyAlpha, double scale,
                              double approachScale, double approachAlpha, boolean headVisible,
                              double snakeStart, double snakeEnd, double progress, boolean following,
                              ReplayAnalyzer.SliderPath path) {
    }
}
