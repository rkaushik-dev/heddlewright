package io.github.rkaushikdev.heddlewright.cloth;

import io.github.rkaushikdev.heddlewright.util.Bounds;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Group;
import io.github.rkaushikdev.heddlewright.util.Ids;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.NotFoundException;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import io.github.rkaushikdev.heddlewright.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The piece register.
 *
 * <p>Cloth comes off the loom as a continuous length and leaves the shed as numbered pieces. A piece
 * is cut where the cloth changes: a beam runs out, a shade changes, or a stretch is too faulty to
 * sell as first quality. The register has to add back up to the length that came off the loom, and
 * that check is the one thing a shed cannot afford to get wrong.
 *
 * <p>The register functions take pieces as records, so a {@link Piece} and the stored JSON of one
 * read the same.
 *
 * <p>A faulty piece is cut so the bad stretch comes away on its own, and every length that comes out
 * is graded on the defects that fell in it: first quality inside the grading limit, seconds up to
 * twice it, and rejects past that. A running fault cut through is split between the lengths it runs
 * into, so each is charged only for the yards it carries.
 */
public final class Roll {

    /** The grades a piece is sold at. */
    public enum Grade {
        /** First quality. */
        FIRST,
        /** Seconds. */
        SECOND,
        /** Not for sale. */
        REJECT;

        /** Read a grade by its name, refusing anything else. */
        public static Grade parse(Object text) {
            return Check.member(text, "grade", values());
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The grades, first quality first. */
    public static final List<Grade> GRADES = List.of(Grade.values());

    /**
     * A piece in the register. The id is a content id of everything else, so a regraded piece is a
     * different record.
     *
     * @param reference the piece number
     * @param yards its length
     * @param widthInches its width
     * @param grade what it is sold as
     * @param lot the dye lot, or empty
     * @param squareYards its area
     * @param id the content id
     */
    public record Piece(
            String reference,
            double yards,
            double widthInches,
            Grade grade,
            String lot,
            double squareYards,
            String id) {}

    /** One grade's line in the register totals. */
    public record GradeTotal(Grade grade, int pieces, double yards) {}

    /** The register added up, by grade and overall. */
    public record Totals(int pieces, double yards, double squareYards, List<GradeTotal> byGrade) {
        /** Copies the grade lines. */
        public Totals {
            byGrade = List.copyOf(byGrade);
        }
    }

    /** A length cut from a piece, its defects moved to its own yards, and how it scored and graded. */
    public record Graded(Piece piece, List<JsonObject> defects, FourPoint.Score score, FourPoint.RollGrade grade) {
        /** Copies the defects. */
        public Graded {
            defects = List.copyOf(defects);
        }
    }

    /** How far past the grading limit a length may score and still go as seconds, as a multiple of it. */
    public static final double SECONDS_FACTOR = 2;

    /** A register against the length the loom wove. */
    public record Reconciliation(double wovenYards, double registeredYards, double difference, boolean balanced) {}

    private Roll() {}

    /**
     * Open a piece from its record: a reference, yards and a width, with the grade defaulting to first
     * and the lot to empty. A lot that is not text is kept as its text.
     */
    public static Piece createPiece(Object input) {
        JsonObject record = Check.plainObject(input, "piece");
        String reference = Check.nonEmptyString(record.get("reference"), "reference");
        double yards = Check.positiveNumber(record.get("yards"), "yards");
        double widthInches = Check.positiveNumber(record.get("widthInches"), "widthInches");
        Json given = record.get("grade");
        Grade grade = given.isMissing() ? Grade.FIRST : Grade.parse(given);
        Json lotGiven = record.get("lot");
        String lot = lotGiven.isMissing() ? "" : Text.string(lotGiven);
        double rounded = Numeric.roundTo(yards, 3);
        double squareYards = Numeric.roundTo((yards * widthInches) / 36, 3);
        String id = Ids.contentId(
                "piece",
                Json.obj(
                        "reference", reference,
                        "yards", rounded,
                        "widthInches", widthInches,
                        "grade", grade,
                        "lot", lot,
                        "squareYards", squareYards));
        return new Piece(reference, rounded, widthInches, grade, lot, squareYards, id);
    }

    /** Cut a piece in two, suffixing the halves A and B. */
    public static List<Piece> cutPiece(Object piece, double atYards) {
        return cutPiece(piece, atYards, List.of("A", "B"));
    }

    /**
     * Cut a piece in two at a point along its length, keeping both halves in the register. The two
     * lengths must add back to the original.
     */
    public static List<Piece> cutPiece(Object piece, double atYards, List<String> suffixes) {
        JsonObject record = Check.plainObject(piece, "piece");
        Check.number(atYards, "atYards", Bounds.atLeast(0.001));
        double yards = record.get("yards").asDouble();
        if (atYards >= yards) {
            throw new InvalidArgumentException(
                    "the cut falls at or past the end of the piece", Json.obj("atYards", atYards, "yards", yards));
        }
        Check.array(suffixes, "suffixes", 2, 2);
        return cutAt(record, List.of(atYards), suffixes);
    }

    /** Cut a piece at several points at once, left to right, numbering the lengths from one. */
    public static List<Piece> cutPieces(Object piece, List<? extends Number> atYards) {
        Check.array(atYards, "atYards", 1);
        JsonObject record = Check.plainObject(piece, "piece");
        List<Double> points = cutPoints(record, atYards);
        return cutAt(
                record,
                points,
                IntStream.rangeClosed(1, points.size() + 1)
                        .mapToObj(n -> "-" + n)
                        .toList());
    }

    /** The points a piece is cut at, sorted, each forward of the last and inside the piece. */
    private static List<Double> cutPoints(JsonObject record, List<? extends Number> atYards) {
        double yards = record.get("yards").asDouble();
        List<Double> points = atYards.stream().map(Number::doubleValue).sorted().toList();
        double previous = 0;
        for (double at : points) {
            if (at <= previous || at >= yards) {
                throw new InvalidArgumentException(
                        "the cuts must run forwards inside the piece",
                        Json.obj("at", at, "previous", previous, "yards", yards));
            }
            previous = at;
        }
        return points;
    }

    /** The lengths between the cut points, each a piece named with its suffix. */
    private static List<Piece> cutAt(JsonObject record, List<Double> points, List<String> suffixes) {
        String reference = Text.string(record.get("reference"));
        List<Double> edges = edges(record, points);
        List<Piece> out = new ArrayList<>(edges.size() - 1);
        for (int index = 0; index + 1 < edges.size(); index += 1) {
            out.add(createPiece(record.with(
                    "reference",
                    reference + suffixes.get(index),
                    "yards",
                    Numeric.roundTo(edges.get(index + 1) - edges.get(index), 3))));
        }
        return List.copyOf(out);
    }

    private static List<Double> edges(JsonObject record, List<Double> points) {
        List<Double> edges = new ArrayList<>(List.of(0.0));
        edges.addAll(points);
        edges.add(record.get("yards").asDouble());
        return edges;
    }

    /**
     * Cut a piece at the points given and grade every length on the defects that fall in it, on the
     * scheme and limit given. A defect sits in the length its yard falls in; a running fault is split
     * at every cut it crosses.
     */
    public static List<Graded> cutAndGrade(
            Object piece, List<? extends Number> atYards, List<?> defects, FourPoint.Scheme scheme, double acceptance) {
        JsonObject record = Check.plainObject(piece, "piece");
        Check.required(scheme, "scheme");
        List<Double> points = atYards.isEmpty() ? List.of() : cutPoints(record, atYards);
        List<Piece> lengths = points.isEmpty() ? List.of(createPiece(record)) : cutPieces(record, points);
        List<Double> edges = edges(record, points);
        List<Object> sheet = Check.array(defects, "defects");
        List<Graded> out = new ArrayList<>(lengths.size());
        for (int index = 0; index < lengths.size(); index += 1) {
            double from = edges.get(index);
            double to = edges.get(index + 1);
            List<JsonObject> mine = new ArrayList<>();
            for (Object entry : sheet) {
                within(Check.plainObject(entry, "defect"), from, to).ifPresent(mine::add);
            }
            Piece length = lengths.get(index);
            FourPoint.Score score = FourPoint.scoreRoll(Json.obj(
                    "defects", mine, "widthInches", length.widthInches(), "yards", length.yards(), "scheme", scheme));
            FourPoint.RollGrade grade = FourPoint.gradeRoll(score, acceptance);
            Grade sold = grade.accepted()
                    ? Grade.FIRST
                    : grade.points() <= acceptance * SECONDS_FACTOR ? Grade.SECOND : Grade.REJECT;
            out.add(new Graded(regrade(length, sold), mine, score, grade));
        }
        return List.copyOf(out);
    }

    /** A defect moved onto a length running from {@code from} to {@code to}, or nothing when it misses it. */
    private static Optional<JsonObject> within(JsonObject defect, double from, double to) {
        double yard = Check.positiveNumber(defect.get("yard"), "defect.yard");
        Json runs = defect.get("through");
        double through = runs.isNullish() ? yard : Check.number(runs, "defect.through");
        if (through <= from || yard > to) {
            return Optional.empty();
        }
        JsonObject moved = defect.with("yard", Numeric.roundTo(Math.max(yard, from + 0.001) - from, 3));
        return Optional.of(
                runs.isNullish() ? moved : moved.with("through", Numeric.roundTo(Math.min(through, to) - from, 3)));
    }

    /** Regrade a piece without touching its length. */
    public static Piece regrade(Object piece, Object grade) {
        return createPiece(Check.plainObject(piece, "piece").with("grade", Grade.parse(grade)));
    }

    private static List<JsonObject> records(List<?> pieces) {
        List<Object> list = Check.array(pieces, "pieces");
        List<JsonObject> out = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index += 1) {
            JsonObject piece = Check.plainObject(list.get(index), "pieces[" + index + "]");
            Json grade = piece.get("grade");
            if (GRADES.stream().noneMatch(known -> Json.of(known).equals(grade))) {
                throw new InvalidArgumentException(
                        "piece " + Text.string(piece.get("reference")) + " carries an unknown grade",
                        Json.obj("grade", grade));
            }
            out.add(piece);
        }
        return out;
    }

    /** The totals a register adds up to, by grade and overall, every grade listed even at nothing. */
    public static Totals registerTotals(List<?> pieces) {
        List<JsonObject> records = records(pieces);
        List<GradeTotal> byGrade = Group.tallyOver(
                        GRADES,
                        records,
                        Group.<JsonObject>by(piece -> Grade.parse(piece.get("grade")))
                                .label("grade")
                                .field("yards", piece -> piece.get("yards")))
                .stream()
                .map(row -> new GradeTotal(
                        Grade.parse(row.get("grade")),
                        row.get("count").asInt(),
                        row.get("yards").asDouble()))
                .toList();
        return new Totals(
                records.size(),
                Numeric.roundTo(
                        Numeric.sum(records.stream()
                                .map(piece -> piece.get("yards"))
                                .toList()),
                        3),
                Numeric.roundTo(
                        Numeric.sum(records.stream()
                                .map(piece -> piece.get("squareYards"))
                                .toList()),
                        3),
                byGrade);
    }

    /** Check a register against the loom with half a yard of tolerance. */
    public static Reconciliation reconcile(List<?> pieces, double wovenYards) {
        return reconcile(pieces, wovenYards, 0.5);
    }

    /**
     * Check a register against the length that came off the loom. Anything that does not reconcile is
     * cloth nobody can account for.
     */
    public static Reconciliation reconcile(List<?> pieces, double wovenYards, double toleranceYards) {
        Check.positiveNumber(wovenYards, "wovenYards");
        Check.number(toleranceYards, "toleranceYards", Bounds.atLeast(0));
        Totals totals = registerTotals(pieces);
        double difference = Numeric.roundTo(totals.yards() - wovenYards, 3);
        return new Reconciliation(wovenYards, totals.yards(), difference, Math.abs(difference) <= toleranceYards);
    }

    /** Find a piece by reference. */
    public static <T> T pieceByReference(List<T> pieces, String reference) {
        Check.array(pieces, "pieces");
        for (T piece : pieces) {
            if (Check.plainObject(piece, "piece").get("reference").equals(Json.of(reference))) {
                return piece;
            }
        }
        throw new NotFoundException("no piece called \"" + reference + "\"", Json.obj("reference", reference));
    }

    /** The share of the register that is first quality, by length. */
    public static double firstQualityShare(List<?> pieces) {
        Totals totals = registerTotals(pieces);
        if (totals.yards() == 0) {
            return 0;
        }
        GradeTotal first = totals.byGrade().stream()
                .filter(entry -> entry.grade() == Grade.FIRST)
                .findFirst()
                .orElseThrow();
        return Numeric.roundTo(first.yards() / totals.yards(), 4);
    }
}
