package io.github.rkaushikdev.heddlewright.cloth;

import io.github.rkaushikdev.heddlewright.util.Bounds;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Four-point cloth grading.
 *
 * <p>The four-point system scores a defect by how long it is, whichever way it runs: up to three
 * inches is one point, up to six is two, up to nine is three, and anything longer is four. A hole is
 * scored on its largest dimension and starts at two points however small it is. No linear yard may
 * score more than four points however many defects it holds, which stops one bad yard from
 * condemning a roll on its own.
 *
 * <p>A running defect, one that carries on down the piece such as a reed mark, a float line or a
 * shaded band, is not measured at all: every yard it runs through scores the full four points, from
 * the yard it starts in to the yard named as its {@code through}.
 *
 * <p>The result is quoted as points per hundred square yards, so rolls of different widths and
 * lengths can be compared:
 *
 * <pre>
 *   points per 100 square yards = total points x 3600 / (width in inches x yards)
 * </pre>
 */
public final class FourPoint {

    /** How a defect is measured. */
    public enum Kind {
        /** Along its length. */
        LENGTH,
        /** A hole, on its largest dimension. */
        HOLE;

        /** Read a kind by its name, refusing anything else. */
        public static Kind parse(Object text) {
            return Check.member(text, "defect.kind", values());
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The defect kinds, length first. */
    public static final List<Kind> DEFECT_KINDS = List.of(Kind.values());

    /** No yard counts for more than this. */
    public static final int MAX_POINTS_PER_YARD = 4;

    /** What every yard of a running defect scores, whatever its width. */
    public static final int RUNNING_POINTS_PER_YARD = MAX_POINTS_PER_YARD;

    /** The usual trade limit: forty points per hundred square yards. */
    public static final double DEFAULT_ACCEPTANCE = 40;

    /** One yard's points, before and after the cap. */
    public record YardScore(int yard, int points, double counted) {}

    /** A scored roll. */
    public record Score(
            int defects,
            double rawPoints,
            double totalPoints,
            List<Integer> cappedYards,
            double pointsPer100SquareYards,
            List<YardScore> perYard) {
        /** Copies the lists. */
        public Score {
            cappedYards = List.copyOf(cappedYards);
            perYard = List.copyOf(perYard);
        }
    }

    /** Whether a scored roll passes, and by how much. */
    public record RollGrade(double points, double acceptance, boolean accepted, double margin) {}

    /** Several rolls scored as one lot. */
    public record LotScore(int rolls, double totalPoints, double squareYards, double pointsPer100SquareYards) {}

    private FourPoint() {}

    /**
     * The points a single defect scores, read from its record: a kind (length by default) and inches,
     * or for a running defect the {@code yard} it starts in and the yard it runs {@code through}, which
     * scores the full four points for every yard it touches.
     */
    public static int defectPoints(Object defect) {
        JsonObject record = Check.plainObject(defect, "defect");
        Json given = record.get("kind");
        Kind kind = Kind.parse(given.isNullish() ? Json.of("length") : given);
        if (!record.get("through").isNullish()) {
            if (kind == Kind.HOLE) {
                throw new InvalidArgumentException("a hole cannot run down the piece", Json.obj("kind", kind));
            }
            Json from = record.get("yard");
            int first = wholeYard(from.isNullish() ? Json.of(1) : from, "defect.yard");
            return (runEnd(record.get("through"), "defect.through", first, 0) - first + 1) * RUNNING_POINTS_PER_YARD;
        }
        double inches = Check.positiveNumber(record.get("inches"), "defect.inches");
        if (kind == Kind.HOLE) {
            return inches <= 1 ? 2 : 4;
        }
        if (inches <= 3) {
            return 1;
        }
        if (inches <= 6) {
            return 2;
        }
        if (inches <= 9) {
            return 3;
        }
        return 4;
    }

    /** Score a roll with the usual four-point cap. */
    public static Score scoreRoll(List<?> defects, double widthInches, double yards) {
        return scoreRoll(defects, widthInches, yards, MAX_POINTS_PER_YARD);
    }

    /**
     * Score a roll from its record: {@code defects}, {@code widthInches}, {@code yards} and an
     * optional {@code cap}.
     */
    public static Score scoreRoll(Object roll) {
        JsonObject record = Check.plainObject(roll, "roll");
        Json cap = record.get("cap");
        return score(
                record.get("defects"),
                record.get("widthInches"),
                record.get("yards"),
                cap.isMissing() ? Json.of(MAX_POINTS_PER_YARD) : cap);
    }

    /**
     * Score a roll. Defects carry the yard they fall in, and each yard is capped before anything is
     * added up.
     */
    public static Score scoreRoll(List<?> defects, double widthInches, double yards, double cap) {
        return score(defects, Json.of(widthInches), Json.of(yards), Json.of(cap));
    }

    private static Score score(Object defects, Object widthGiven, Object yardsGiven, Object capGiven) {
        List<Object> list = Check.array(defects, "defects");
        double widthInches = Check.positiveNumber(widthGiven, "widthInches");
        double yards = Check.positiveNumber(yardsGiven, "yards");
        double cap = Check.number(capGiven, "cap", Bounds.atLeast(1));
        Map<Integer, Integer> perYard = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index += 1) {
            Object defect = list.get(index);
            Json at = fieldOf(defect, "yard");
            int yard = yardOf(at.isNullish() ? Json.of(1) : at, "defects[" + index + "].yard", yards, index);
            Json through = fieldOf(defect, "through");
            if (through.isNullish()) {
                perYard.merge(yard, defectPoints(defect), Integer::sum);
                continue;
            }
            // Read for its refusals alone: a hole cannot run, and a run cannot end before it starts.
            defectPoints(defect);
            int last = yardOf(through, "defects[" + index + "].through", yards, index);
            for (int each = yard; each <= last; each += 1) {
                perYard.merge(each, RUNNING_POINTS_PER_YARD, Integer::sum);
            }
        }
        List<YardScore> capped = new ArrayList<>();
        perYard.forEach((yard, points) -> capped.add(new YardScore(yard, points, Math.min(points, cap))));
        double totalPoints = Numeric.sumBy(capped, YardScore::counted, "defects");
        // The capped yards are read before the yards are sorted, so they come in the order the sheet
        // first named them.
        List<Integer> cappedYards = capped.stream()
                .filter(entry -> entry.points() > cap)
                .map(YardScore::yard)
                .toList();
        List<YardScore> sorted = new ArrayList<>(capped);
        sorted.sort(Comparator.comparingInt(YardScore::yard));
        return new Score(
                list.size(),
                Numeric.sumBy(capped, YardScore::points, "defects"),
                totalPoints,
                cappedYards,
                Numeric.roundTo((totalPoints * 3600) / (widthInches * yards), 2),
                sorted);
    }

    /** The whole yard a position along the roll falls in. */
    private static int wholeYard(Json at, String field) {
        return (int) Math.ceil(Check.number(at, field, Bounds.atLeast(0.0001)));
    }

    /** The last whole yard a running defect touches, refusing one that ends before it starts. */
    private static int runEnd(Json through, String field, int first, int index) {
        int last = wholeYard(through, field);
        if (last < first) {
            throw new InvalidArgumentException(
                    "a running defect ends before it starts", Json.obj("yard", first, "through", last, "index", index));
        }
        return last;
    }

    /** The whole yard a position along the roll falls in, refusing one past the end of the roll. */
    private static int yardOf(Json at, String field, double yards, int index) {
        int yard = wholeYard(at, field);
        if (yard > Math.ceil(yards)) {
            throw new InvalidArgumentException(
                    "a defect falls past the end of the roll", Json.obj("yard", yard, "yards", yards, "index", index));
        }
        return yard;
    }

    /** A field of a value that may not be an object: the field of an object, and absent for anything else. */
    static Json fieldOf(Object value, String key) {
        if (value instanceof JsonObject || value instanceof Map<?, ?> || value instanceof Record) {
            return Check.plainObject(value, key).get(key);
        }
        return Json.MISSING;
    }

    /** Whether a scored roll passes the trade limit. */
    public static RollGrade gradeRoll(Score score) {
        return gradeRoll(score, DEFAULT_ACCEPTANCE);
    }

    /** Whether a scored roll passes, and by how much. */
    public static RollGrade gradeRoll(Score score, double acceptance) {
        Check.positiveNumber(acceptance, "acceptance");
        double points = score.pointsPer100SquareYards();
        return new RollGrade(points, acceptance, points <= acceptance, Numeric.roundTo(acceptance - points, 2));
    }

    /** How many points a roll may carry at the trade limit. */
    public static int pointsAllowed(double widthInches, double yards) {
        return pointsAllowed(widthInches, yards, DEFAULT_ACCEPTANCE);
    }

    /** How many points a roll of this size may carry before it is rejected. */
    public static int pointsAllowed(double widthInches, double yards, double acceptance) {
        Check.positiveNumber(widthInches, "widthInches");
        Check.positiveNumber(yards, "yards");
        Check.positiveNumber(acceptance, "acceptance");
        return (int) Math.floor((acceptance * widthInches * yards) / 3600);
    }

    /** Score several rolls, each a record as {@link #scoreRoll(Object)} reads one, as one lot. */
    public static LotScore scoreLot(List<?> rolls) {
        List<Object> list = Check.array(rolls, "rolls", 1);
        double points = 0;
        double squareYards = 0;
        for (int index = 0; index < list.size(); index += 1) {
            JsonObject roll = Check.plainObject(list.get(index), "rolls[" + index + "]");
            Score score = scoreRoll(roll);
            points += score.totalPoints();
            squareYards +=
                    (roll.get("widthInches").asDouble() * roll.get("yards").asDouble()) / 36;
        }
        return new LotScore(
                list.size(), points, Numeric.roundTo(squareYards, 3), Numeric.roundTo((points * 100) / squareYards, 2));
    }
}
