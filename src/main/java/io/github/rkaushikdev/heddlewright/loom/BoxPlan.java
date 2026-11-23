package io.github.rkaushikdev.heddlewright.loom;

import io.github.rkaushikdev.heddlewright.loom.BoxMotion.Allotment;
import io.github.rkaushikdev.heddlewright.loom.BoxMotion.Boxing;
import io.github.rkaushikdev.heddlewright.loom.BoxMotion.WeftRun;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Everything a weaver needs to gait a box pattern up: the allotment, the chain to lace, how far the
 * box travels, what it costs in speed, and whether the loom can pick it at all. The speed and the
 * picks lost are given only when the request names the loom's speed.
 *
 * <p>The boxes are allotted by pick count unless the request asks for the least travel, which keeps
 * the commonest weft resting in box one and puts the colours that change into each other next door.
 */
public record BoxPlan(
        int picks,
        int colours,
        int boxes,
        Boxing boxedAt,
        Allot allot,
        List<Allotment> allotment,
        List<Integer> chain,
        int changes,
        int longestLift,
        int travel,
        List<WeftRun> runs,
        List<JsonObject> share,
        OptionalDouble picksLost,
        OptionalDouble picksMinute) {

    /** How the boxes are allotted. */
    public enum Allot {
        /** The commonest weft first, then by pick count. */
        COUNT,
        /** The commonest weft first, then for the least travel. */
        TRAVEL;

        /** Read an allotment rule by its name, refusing anything else. */
        public static Allot parse(Object text) {
            return Check.member(text, "allot", values());
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Set<String> TAKES = Set.of("pattern", "boxes", "boxedAt", "picksMinute", "allot");

    /** Copies the lists. */
    public BoxPlan {
        allotment = List.copyOf(allotment);
        chain = List.copyOf(chain);
        runs = List.copyOf(runs);
        share = List.copyOf(share);
    }

    /**
     * Gait a pattern up from a plain request: the {@code pattern}, the {@code boxes} the loom has,
     * how it is {@code boxedAt}, if known its {@code picksMinute}, and how to {@code allot} the
     * boxes. Anything else in the request is refused rather than ignored.
     */
    public static BoxPlan boxMotionPlan(Object request) {
        JsonObject record = Check.plainObject(request, "request");
        List<String> unknown =
                record.keys().stream().filter(key -> !TAKES.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidArgumentException(
                    "boxMotionPlan does not take " + String.join(", ", unknown), Json.obj("unknown", unknown));
        }
        Json boxing = record.get("boxedAt");
        Json speed = record.get("picksMinute");
        Json allot = record.get("allot");
        return boxMotionPlan(
                Check.array(record.get("pattern"), "pattern"),
                Check.positiveInteger(record.get("boxes"), "boxes"),
                boxing.isMissing() ? Boxing.BOTH : Boxing.parse(boxing),
                speed.isMissing() ? OptionalDouble.empty() : OptionalDouble.of(Check.number(speed, "picksMinute")),
                allot.isNullish() ? Allot.COUNT : Allot.parse(allot));
    }

    /** Gait a pattern up on a loom with this many boxes, boxed this way, allotted by pick count. */
    public static BoxPlan boxMotionPlan(List<?> pattern, int boxes, Boxing boxedAt, OptionalDouble picksMinute) {
        return boxMotionPlan(pattern, boxes, boxedAt, picksMinute, Allot.COUNT);
    }

    /** Gait a pattern up on a loom with this many boxes, boxed this way, allotted the way asked. */
    public static BoxPlan boxMotionPlan(
            List<?> pattern, int boxes, Boxing boxedAt, OptionalDouble picksMinute, Allot allot) {
        Check.required(allot, "allot");
        List<Allotment> allotment =
                switch (allot) {
                    case COUNT -> BoxMotion.allotBoxes(pattern, boxes);
                    case TRAVEL -> BoxMotion.allotForTravel(pattern, boxes);
                };
        BoxMotion.assertShuttlesReturn(pattern, boxedAt);
        List<Integer> chain = BoxMotion.boxChain(pattern, allotment);
        return new BoxPlan(
                chain.size(),
                allotment.size(),
                boxes,
                boxedAt,
                allot,
                allotment,
                chain,
                BoxMotion.boxChanges(chain),
                BoxMotion.longestLift(chain),
                BoxMotion.boxTravel(chain),
                BoxMotion.weftRuns(pattern),
                BoxMotion.pickShare(allotment),
                picksMinute.stream()
                        .map(speed -> BoxMotion.picksLostToChanging(chain))
                        .findFirst(),
                picksMinute.stream()
                        .map(speed -> BoxMotion.speedWithChanging(chain, speed))
                        .findFirst());
    }
}
