package io.github.rkaushikdev.heddlewright.cloth;

import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.ConflictException;
import io.github.rkaushikdev.heddlewright.util.Group;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import io.github.rkaushikdev.heddlewright.util.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Shade lots.
 *
 * <p>Two pieces dyed in different vessels are never quite the same colour, and a garment cut across
 * the join shows it. So pieces are gathered into lots by how far apart their shade readings are, and
 * a lot is only usable if every piece in it sits inside the tolerance of every other, not merely of
 * the first one. That is the part a simple grouping gets wrong.
 *
 * <p>How far apart two readings are can be measured two ways. The straight distance treats the three
 * axes alike. CMC(l:c) weights each axis by how much the eye forgives at that lightness, chroma and
 * hue, and is the figure a dyehouse passes or fails a shade on; 2:1 is the usual ratio for
 * acceptability and 1:1 for perceptibility.
 */
public final class Lot {

    /** A shade reading against a piece, on the three colour axes. */
    public record Reading(String reference, double lightness, double red, double yellow) {}

    /** A point in colour space with no piece attached, such as the middle of a lot. */
    public record Shade(double lightness, double red, double yellow) {}

    /** Two pieces further apart than the tolerance. */
    public record Pair(String left, String right, double distance) {}

    /** A shade lot: its number, its pieces, its centre and its widest gap. */
    public record ShadeLot(int lot, List<String> pieces, Shade centre, double spread) {
        /** Copies the pieces. */
        public ShadeLot {
            pieces = List.copyOf(pieces);
        }
    }

    /** The pieces dyed in one vessel. */
    public record DyeLot(String lot, List<String> pieces) {
        /** Copies the pieces. */
        public DyeLot {
            pieces = List.copyOf(pieces);
        }
    }

    /** How the distance between two readings is measured. */
    public sealed interface Difference permits Straight, Cmc {}

    /** The straight distance in colour space, every axis counting alike. */
    public record Straight() implements Difference {}

    /** CMC(l:c), with its lightness and chroma weights. */
    public record Cmc(double lightness, double chroma) implements Difference {
        /** Refuses a weight of nothing. */
        public Cmc {
            Check.positiveNumber(lightness, "cmc.lightness");
            Check.positiveNumber(chroma, "cmc.chroma");
        }
    }

    /** The straight distance. */
    public static final Difference STRAIGHT = new Straight();

    /** CMC 2:1, the ratio a shade is accepted or rejected on. */
    public static final Difference CMC_ACCEPTABILITY = new Cmc(2, 1);

    private Lot() {}

    /** Book a shade reading against a piece, on the three colour axes. */
    public static Reading readShade(Object entry) {
        JsonObject record = Check.plainObject(entry, "reading");
        return new Reading(
                Check.nonEmptyString(record.get("reference"), "reference"),
                Check.number(record.get("lightness"), "lightness"),
                Check.number(record.get("red"), "red"),
                Check.number(record.get("yellow"), "yellow"));
    }

    /** How far apart two readings are, as a straight distance in colour space. */
    public static double shadeDistance(Reading left, Reading right) {
        return shadeDistance(left, right, STRAIGHT);
    }

    /**
     * How far apart two readings are, measured the way given. CMC weights its tolerances from the
     * standard's own shade, so it reads differently depending on which piece is the standard; here
     * neither piece is, and the larger of the two readings is taken so a lot never holds on the kinder
     * one.
     */
    public static double shadeDistance(Reading left, Reading right, Difference difference) {
        return switch (difference) {
            case Straight straight -> {
                double dl = left.lightness() - right.lightness();
                double da = left.red() - right.red();
                double db = left.yellow() - right.yellow();
                yield Numeric.roundTo(Math.sqrt(dl * dl + da * da + db * db), 4);
            }
            case Cmc cmc -> Numeric.roundTo(Math.max(cmc(left, right, cmc), cmc(right, left, cmc)), 4);
        };
    }

    /** CMC(l:c) of a batch against a standard. */
    static double cmc(Reading standard, Reading batch, Cmc weights) {
        double chroma = Math.hypot(standard.red(), standard.yellow());
        double dl = standard.lightness() - batch.lightness();
        double dc = chroma - Math.hypot(batch.red(), batch.yellow());
        double da = standard.red() - batch.red();
        double db = standard.yellow() - batch.yellow();
        double dh = Math.sqrt(Math.max(0, da * da + db * db - dc * dc));
        double hue = Math.toDegrees(Math.atan2(standard.yellow(), standard.red()));
        hue = hue < 0 ? hue + 360 : hue;
        double sl = standard.lightness() < 16
                ? 0.511
                : (0.040975 * standard.lightness()) / (1 + 0.01765 * standard.lightness());
        double sc = (0.0638 * chroma) / (1 + 0.0131 * chroma) + 0.638;
        double f = Math.sqrt(Math.pow(chroma, 4) / (Math.pow(chroma, 4) + 1900));
        double t = hue >= 164 && hue <= 345
                ? 0.56 + Math.abs(0.2 * Math.cos(Math.toRadians(hue + 168)))
                : 0.36 + Math.abs(0.4 * Math.cos(Math.toRadians(hue + 35)));
        double sh = sc * (f * t + 1 - f);
        double l = dl / (weights.lightness() * sl);
        double c = dc / (weights.chroma() * sc);
        double h = dh / sh;
        return Math.sqrt(l * l + c * c + h * h);
    }

    /** Every pair of readings with the distance between them, in the order they were named. */
    private static List<Pair> pairs(List<Reading> readings, Difference difference) {
        Check.array(readings, "readings", 1);
        List<Pair> out = new ArrayList<>();
        for (int i = 0; i < readings.size(); i += 1) {
            for (int j = i + 1; j < readings.size(); j += 1) {
                out.add(new Pair(
                        readings.get(i).reference(),
                        readings.get(j).reference(),
                        shadeDistance(readings.get(i), readings.get(j), difference)));
            }
        }
        return out;
    }

    /** Every pair in a lot that sits further apart than the tolerance, widest first. */
    public static List<Pair> outliers(List<Reading> readings, double tolerance) {
        return outliers(readings, tolerance, STRAIGHT);
    }

    /** Every pair further apart than the tolerance on the difference given, widest first. */
    public static List<Pair> outliers(List<Reading> readings, double tolerance, Difference difference) {
        Check.positiveNumber(tolerance, "tolerance");
        List<Pair> found = new ArrayList<>(pairs(readings, difference).stream()
                .filter(pair -> pair.distance() > tolerance)
                .toList());
        found.sort(Comparator.comparingDouble(Pair::distance).reversed());
        return List.copyOf(found);
    }

    /** A lot holds together when no two pieces in it are further apart than the tolerance. */
    public static boolean lotHolds(List<Reading> readings, double tolerance) {
        return lotHolds(readings, tolerance, STRAIGHT);
    }

    /** The same, on the difference given. */
    public static boolean lotHolds(List<Reading> readings, double tolerance, Difference difference) {
        return outliers(readings, tolerance, difference).isEmpty();
    }

    /**
     * Gather readings into lots. A piece joins a lot only when it sits inside the tolerance of every
     * piece already in it, so a lot cannot drift across the whole colour space one small step at a
     * time.
     */
    public static List<ShadeLot> buildLots(List<Reading> readings, double tolerance) {
        return buildLots(readings, tolerance, STRAIGHT);
    }

    /** Gather readings into lots on the difference given. */
    public static List<ShadeLot> buildLots(List<Reading> readings, double tolerance, Difference difference) {
        Check.array(readings, "readings", 1);
        Check.positiveNumber(tolerance, "tolerance");
        List<List<Reading>> lots = new ArrayList<>();
        for (Reading reading : readings) {
            List<Reading> home = lots.stream()
                    .filter(lot ->
                            lot.stream().allMatch(member -> shadeDistance(member, reading, difference) <= tolerance))
                    .findFirst()
                    .orElse(null);
            if (home == null) {
                lots.add(new ArrayList<>(List.of(reading)));
                continue;
            }
            home.add(reading);
        }
        List<ShadeLot> out = new ArrayList<>(lots.size());
        for (int index = 0; index < lots.size(); index += 1) {
            List<Reading> members = lots.get(index);
            out.add(new ShadeLot(
                    index + 1,
                    members.stream().map(Reading::reference).toList(),
                    lotCentre(members),
                    lotSpread(members, difference)));
        }
        return List.copyOf(out);
    }

    /** The middle of a lot, which is what a shade card records. */
    public static Shade lotCentre(List<Reading> readings) {
        Check.array(readings, "readings", 1);
        return new Shade(
                Numeric.roundTo(Numeric.meanBy(readings, Reading::lightness, "readings"), 4),
                Numeric.roundTo(Numeric.meanBy(readings, Reading::red, "readings"), 4),
                Numeric.roundTo(Numeric.meanBy(readings, Reading::yellow, "readings"), 4));
    }

    /** The widest gap inside a lot, which is what a customer would see. */
    public static double lotSpread(List<Reading> readings) {
        return lotSpread(readings, STRAIGHT);
    }

    /** The widest gap inside a lot on the difference given. */
    public static double lotSpread(List<Reading> readings, Difference difference) {
        return pairs(readings, difference).stream()
                .mapToDouble(Pair::distance)
                .max()
                .orElse(0);
    }

    /** Refuse a lot that would not hold together. */
    public static List<Reading> assertLotHolds(List<Reading> readings, double tolerance) {
        return assertLotHolds(readings, tolerance, STRAIGHT);
    }

    /** Refuse a lot that would not hold together on the difference given. */
    public static List<Reading> assertLotHolds(List<Reading> readings, double tolerance, Difference difference) {
        List<Pair> found = outliers(readings, tolerance, difference);
        if (!found.isEmpty()) {
            throw new ConflictException(
                    "those pieces are not one shade lot", Json.obj("pairs", found.size(), "widest", found.get(0)));
        }
        return readings;
    }

    /** Which lot a piece was put in, by reference. */
    public static int lotOf(List<ShadeLot> lots, String reference) {
        Check.nonEmptyString(reference, "reference");
        return lots.stream()
                .filter(lot -> lot.pieces().contains(reference))
                .findFirst()
                .orElseThrow(() ->
                        new InvalidArgumentException("no lot holds " + reference, Json.obj("reference", reference)))
                .lot();
    }

    /** How many lots a delivery would need at a given tolerance. */
    public static int lotCount(List<Reading> readings, double tolerance) {
        return lotCount(readings, tolerance, STRAIGHT);
    }

    /** How many lots a delivery would need at a tolerance on the difference given. */
    public static int lotCount(List<Reading> readings, double tolerance, Difference difference) {
        return buildLots(readings, tolerance, difference).size();
    }

    /**
     * Pieces grouped by the lot they were dyed in, rather than by shade, in the order the lots first
     * appear. A piece with no lot recorded goes under "unrecorded"; a lot or a reference that is not
     * text is read as its text.
     */
    public static List<DyeLot> byDyeLot(List<?> pieces) {
        List<JsonObject> records = new ArrayList<>();
        for (Object piece : Check.array(pieces, "items")) {
            records.add(Check.plainObject(piece, "piece"));
        }
        Map<String, List<JsonObject>> groups = Group.groupBy(records, (JsonObject piece) -> {
            Json lot = piece.get("lot");
            return lot.isNullish() ? "unrecorded" : Text.string(lot);
        });
        List<DyeLot> out = new ArrayList<>(groups.size());
        groups.forEach((lot, members) -> out.add(new DyeLot(
                lot,
                members.stream()
                        .map(piece -> Text.string(piece.get("reference")))
                        .toList())));
        return List.copyOf(out);
    }
}
