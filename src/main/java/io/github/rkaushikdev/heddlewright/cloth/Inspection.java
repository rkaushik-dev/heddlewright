package io.github.rkaushikdev.heddlewright.cloth;

import io.github.rkaushikdev.heddlewright.cloth.FourPoint.Kind;
import io.github.rkaushikdev.heddlewright.util.Bounds;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Collation;
import io.github.rkaushikdev.heddlewright.util.Group;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.NotFoundException;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import io.github.rkaushikdev.heddlewright.util.Text;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The fault catalogue and the inspection sheet.
 *
 * <p>A grader books a fault against a name, and the name carries where the fault comes from. That is
 * what turns a roll of points into an instruction: a sheet full of weft bars is a weft supply
 * problem, a sheet full of reed marks is a loom problem, and the two go to different people.
 */
public final class Inspection {

    /** Where a fault comes from. */
    public enum Origin {
        /** The warp. */
        WARP,
        /** The weft. */
        WEFT,
        /** The loom. */
        LOOM,
        /** The yarn itself. */
        YARN,
        /** Handling after weaving. */
        HANDLING;

        /** Read an origin by its name, refusing anything else. */
        public static Origin parse(Object text) {
            return Check.member(text, "origin", values());
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The origins, warp first. */
    public static final List<Origin> FAULT_ORIGINS = List.of(Origin.values());

    /** A fault in the catalogue. */
    public record Fault(String code, String name, Origin origin, Kind kind) {}

    /**
     * A booked defect: the catalogue entry, where and how long it is, the yard a running fault carries
     * on to, the grader's note, and its points. A note that is not text is kept as its text.
     */
    public record Defect(
            String code,
            String name,
            Origin origin,
            Kind kind,
            double inches,
            double yard,
            OptionalDouble through,
            String note,
            int points) {}

    /** A printed inspection sheet. */
    public record Sheet(
            int defects,
            double points,
            double density,
            List<JsonObject> byFault,
            List<JsonObject> byOrigin,
            Optional<Origin> dominantOrigin,
            Optional<Defect> worstDefect)
            implements Json.Writable {
        /** Copies the rankings. */
        public Sheet {
            byFault = List.copyOf(byFault);
            byOrigin = List.copyOf(byOrigin);
        }

        /** The sheet as the service sends it, with null for an origin or a worst defect it has not got. */
        @Override
        public Json toJson() {
            return Json.obj(
                    "defects", defects,
                    "points", points,
                    "density", density,
                    "byFault", byFault,
                    "byOrigin", byOrigin,
                    "dominantOrigin",
                            dominantOrigin.<Object>map(origin -> origin).orElse(Json.NULL),
                    "worstDefect", worstDefect.<Object>map(defect -> defect).orElse(Json.NULL));
        }
    }

    private static final Map<String, Fault> FAULTS = register(
            new Fault("broken_pick", "broken pick", Origin.WEFT, Kind.LENGTH),
            new Fault("double_pick", "double pick", Origin.LOOM, Kind.LENGTH),
            new Fault("missing_end", "missing end", Origin.WARP, Kind.LENGTH),
            new Fault("float", "float", Origin.LOOM, Kind.LENGTH),
            new Fault("reed_mark", "reed mark", Origin.LOOM, Kind.LENGTH),
            new Fault("starting_mark", "starting mark", Origin.LOOM, Kind.LENGTH),
            new Fault("weft_bar", "weft bar", Origin.WEFT, Kind.LENGTH),
            new Fault("temple_mark", "temple mark", Origin.LOOM, Kind.LENGTH),
            new Fault("tight_end", "tight end", Origin.WARP, Kind.LENGTH),
            new Fault("slack_end", "slack end", Origin.WARP, Kind.LENGTH),
            new Fault("slub", "slub", Origin.YARN, Kind.LENGTH),
            new Fault("knot", "knot", Origin.YARN, Kind.LENGTH),
            new Fault("oil_stain", "oil stain", Origin.HANDLING, Kind.LENGTH),
            new Fault("hole", "hole", Origin.LOOM, Kind.HOLE),
            new Fault("selvedge_fault", "selvedge fault", Origin.LOOM, Kind.LENGTH),
            new Fault("thin_place", "thin place", Origin.WEFT, Kind.LENGTH));

    private static Map<String, Fault> register(Fault... faults) {
        Map<String, Fault> out = new LinkedHashMap<>();
        for (Fault fault : faults) {
            out.put(fault.code(), fault);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private Inspection() {}

    /** Every fault the catalogue knows, in code order. */
    public static List<Fault> listFaults() {
        return FAULTS.values().stream()
                .sorted(Comparator.comparing(Fault::code, Collation.ORDER))
                .toList();
    }

    /** Look a fault up by its code. */
    public static Fault faultByCode(Object code) {
        String wanted = Check.nonEmptyString(code, "code");
        Fault fault = FAULTS.get(wanted);
        if (fault == null) {
            throw new NotFoundException(
                    "no fault called \"" + wanted + "\"",
                    Json.obj("code", wanted, "known", List.copyOf(FAULTS.keySet())));
        }
        return fault;
    }

    /** Every fault that comes from a given place. */
    public static List<Fault> faultsByOrigin(Object origin) {
        Origin wanted = Origin.parse(origin);
        return listFaults().stream().filter(fault -> fault.origin() == wanted).toList();
    }

    /**
     * Book a defect from the grader's entry, filling in the kind and the origin from the catalogue. A
     * fault that runs down the piece is booked with the yard it runs {@code through}; its length is
     * the run itself unless the grader measured one, and it scores four points a yard. A hole cannot
     * run.
     */
    public static Defect recordDefect(Object entry) {
        JsonObject record = Check.plainObject(entry, "entry");
        Fault fault = faultByCode(record.get("code"));
        double yard = Check.number(record.get("yard"), "entry.yard", Bounds.atLeast(0.0001));
        Json note = record.get("note");
        Json runs = record.get("through");
        OptionalDouble through = runs.isNullish()
                ? OptionalDouble.empty()
                : OptionalDouble.of(Check.number(runs, "entry.through", Bounds.atLeast(yard)));
        double inches = through.isPresent() && record.get("inches").isNullish()
                ? Numeric.roundTo((through.getAsDouble() - yard) * 36, 2)
                : Check.number(record.get("inches"), "entry.inches", Bounds.atLeast(0.01));
        int points = FourPoint.defectPoints(
                through.isPresent()
                        ? Json.obj("kind", fault.kind(), "yard", yard, "through", through.getAsDouble())
                        : Json.obj("kind", fault.kind(), "inches", inches));
        return new Defect(
                fault.code(),
                fault.name(),
                fault.origin(),
                fault.kind(),
                inches,
                yard,
                through,
                note.isNullish() ? "" : Text.string(note),
                points);
    }

    /** Book a whole sheet at once. */
    public static List<Defect> recordSheet(List<?> entries) {
        return Check.array(entries, "entries").stream()
                .map(Inspection::recordDefect)
                .toList();
    }

    /** Points and counts grouped by fault, worst first. */
    public static List<JsonObject> byFault(List<Defect> defects) {
        return Group.tally(
                defects,
                Group.<Defect>by(Defect::code)
                        .label("code")
                        .field("points", Defect::points)
                        .sortBy("points"));
    }

    /** Points and counts grouped by where the fault came from, worst first. */
    public static List<JsonObject> byOrigin(List<Defect> defects) {
        return Group.tally(
                defects,
                Group.<Defect>by(Defect::origin)
                        .label("origin")
                        .field("points", Defect::points)
                        .sortBy("points"));
    }

    /** Where most of the trouble is coming from, or nothing when nothing is booked. */
    public static Optional<Origin> dominantOrigin(List<Defect> defects) {
        List<JsonObject> ranked = byOrigin(defects);
        return ranked.isEmpty()
                ? Optional.empty()
                : Optional.of(Origin.parse(ranked.get(0).get("origin")));
    }

    /** Defects per hundred square yards, which is the count rather than the score. */
    public static double defectDensity(List<?> defects, double widthInches, double yards) {
        List<Object> list = Check.array(defects, "defects");
        Check.number(widthInches, "widthInches", Bounds.atLeast(0.01));
        Check.number(yards, "yards", Bounds.atLeast(0.01));
        return Numeric.roundTo((list.size() * 3600) / (widthInches * yards), 2);
    }

    /** A printed sheet: the totals, the ranking and the worst single defect, the first if tied. */
    public static Sheet inspectionSheet(List<Defect> defects, double widthInches, double yards) {
        Check.array(defects, "defects");
        Defect worst = null;
        for (Defect defect : defects) {
            if (defect.points() > (worst == null ? 0 : worst.points())) {
                worst = defect;
            }
        }
        return new Sheet(
                defects.size(),
                Numeric.sumBy(defects, Defect::points, "defects"),
                defectDensity(defects, widthInches, yards),
                byFault(defects),
                byOrigin(defects),
                dominantOrigin(defects),
                Optional.ofNullable(worst));
    }
}
