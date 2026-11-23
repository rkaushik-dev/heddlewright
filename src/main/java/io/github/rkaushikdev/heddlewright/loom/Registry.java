package io.github.rkaushikdev.heddlewright.loom;

import io.github.rkaushikdev.heddlewright.loom.BoxMotion.Boxing;
import io.github.rkaushikdev.heddlewright.loom.Loom.Picking;
import io.github.rkaushikdev.heddlewright.loom.Loom.Shedding;
import io.github.rkaushikdev.heddlewright.util.Bounds;
import io.github.rkaushikdev.heddlewright.util.CapacityException;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.NotFoundException;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import io.github.rkaushikdev.heddlewright.util.Problem;
import io.github.rkaushikdev.heddlewright.util.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * The loom register.
 *
 * <p>A shed floor is a list of machines, each with a width, a shedding motion and a speed, and a
 * style can only be put on a loom that can actually hold it. Matching a style to a loom is a
 * capacity question with several parts, and this is where they are all asked at once.
 *
 * <p>Each part is one rule in a table, asked in turn, so a style that fails three ways is told all
 * three. Some rules only bite on some looms: a shuttleless loom cuts its weft every pick, so it has
 * no shuttle to strand and no need for the picks to pair, and it carries its colours on weft feeders
 * rather than in boxes.
 */
public final class Registry {

    /** The references of the looms with one shedding motion. */
    public record SheddingGroup(Shedding shedding, List<String> references) {
        /** Copies the references. */
        public SheddingGroup {
            references = List.copyOf(references);
        }
    }

    /** One capacity rule: the code it reports, when a loom fails it, and what it says. */
    private record Rule(
            String code, BiPredicate<Loom, Requirements> fails, BiFunction<Loom, Requirements, Problem> says) {}

    private static boolean jacquard(Loom loom) {
        return loom.shedding() == Shedding.JACQUARD;
    }

    private static boolean shuttle(Loom loom) {
        return loom.picking() == Picking.SHUTTLE;
    }

    private static Problem problem(String code, String message, double needed, double available) {
        return new Problem(code, message, Json.obj("needed", needed, "available", available));
    }

    private static final List<Rule> RULES = List.of(
            new Rule(
                    "too_narrow",
                    (loom, style) -> style.reedSpaceCm() > loom.reedWidthCm(),
                    (loom, style) -> problem(
                            "too_narrow",
                            loom.reference() + " is " + Text.number(loom.reedWidthCm()) + " cm in the reed",
                            style.reedSpaceCm(),
                            loom.reedWidthCm())),
            new Rule(
                    "not_jacquard",
                    (loom, style) -> style.hooks() > 0 && !jacquard(loom),
                    (loom, style) -> new Problem(
                            "not_jacquard",
                            loom.reference() + " has no jacquard head",
                            Json.obj("shedding", loom.shedding()))),
            new Rule(
                    "too_few_hooks",
                    (loom, style) -> style.hooks() > loom.hooks() && jacquard(loom),
                    (loom, style) -> problem(
                            "too_few_hooks",
                            loom.reference() + " carries " + loom.hooks() + " hooks",
                            style.hooks(),
                            loom.hooks())),
            new Rule(
                    "too_few_shafts",
                    (loom, style) -> style.shafts() > loom.shafts() && !jacquard(loom),
                    (loom, style) -> problem(
                            "too_few_shafts",
                            loom.reference() + " carries " + loom.shafts() + " shafts",
                            style.shafts(),
                            loom.shafts())),
            new Rule(
                    "too_few_boxes",
                    (loom, style) -> style.weftColours() > loom.shuttleBoxes() && shuttle(loom),
                    (loom, style) -> problem(
                            "too_few_boxes",
                            loom.reference() + " has " + loom.shuttleBoxes() + " shuttle boxes",
                            style.weftColours(),
                            loom.shuttleBoxes())),
            new Rule(
                    "too_few_feeders",
                    (loom, style) -> style.weftColours() > loom.shuttleBoxes() && !shuttle(loom),
                    (loom, style) -> problem(
                            "too_few_feeders",
                            loom.reference() + " has " + loom.shuttleBoxes() + " weft feeders",
                            style.weftColours(),
                            loom.shuttleBoxes())),
            // Only a pattern can fail this one, and only on a shuttle loom. A style quoted as a colour
            // count says nothing about the order, and a shuttleless loom has no shuttle to strand.
            new Rule(
                    "shuttle_strands",
                    (loom, style) -> shuttle(loom)
                            && loom.boxedAt() == Boxing.ONE
                            && style.weftPattern()
                                    .map(pattern -> !BoxMotion.oddRuns(pattern).isEmpty())
                                    .orElse(false),
                    (loom, style) -> new Problem(
                            "shuttle_strands",
                            loom.reference() + " is boxed at one end and the weft is not picked in pairs",
                            Json.obj(
                                    "runs",
                                    BoxMotion.oddRuns(style.weftPattern().orElseThrow())))));

    /** The codes of every capacity rule, in the order they are asked. */
    public static final List<String> SHORTFALL_CODES =
            RULES.stream().map(Rule::code).toList();

    private Registry() {}

    /** Register a loom from its plain record. */
    public static Loom createLoom(Object input) {
        JsonObject record = Check.plainObject(input instanceof Loom loom ? loom.toRecord() : input, "loom");
        String reference = Check.nonEmptyString(record.get("reference"), "reference");
        Shedding shedding = Check.member(record.get("shedding"), "shedding", Shedding.values());
        Json picking = record.get("picking");
        Picking picks = picking.isNullish() ? Picking.SHUTTLE : Check.member(picking, "picking", Picking.values());
        double reedWidthCm = Check.positiveNumber(record.get("reedWidthCm"), "reedWidthCm");
        boolean jacquard = shedding == Shedding.JACQUARD;
        int shafts = jacquard ? 0 : Check.positiveInteger(record.get("shafts"), "shafts");
        int hooks = jacquard ? Check.positiveInteger(record.get("hooks"), "hooks") : 0;
        // A dobby or jacquard has no treadles at all, so zero is a real answer here rather than a
        // missing one.
        Json treadlesGiven = record.get("treadles");
        int treadles =
                Check.integer(treadlesGiven.isNullish() ? Json.of(0) : treadlesGiven, "treadles", Bounds.atLeast(0));
        double picksMinute = Check.positiveNumber(record.get("picksMinute"), "picksMinute");
        Json boxes = record.get("shuttleBoxes");
        int shuttleBoxes = boxes.isMissing() ? 1 : Check.positiveInteger(boxes, "shuttleBoxes");
        // Which ends of the sley are boxed. A loom with one box has nothing to be boxed at, so it reads
        // as boxed at one end and the weft rule never bites.
        Json boxing = record.get("boxedAt");
        Boxing boxedAt = boxing.isNullish() ? Boxing.ONE : Boxing.parse(boxing);
        Json installed = record.get("installed");
        return new Loom(
                reference,
                shedding,
                picks,
                reedWidthCm,
                shafts,
                hooks,
                treadles,
                picksMinute,
                shuttleBoxes,
                boxedAt,
                installed.isMissing() ? Json.NULL : installed);
    }

    /** Find a loom by reference. */
    public static Loom loomByReference(List<Loom> looms, String reference) {
        return looms.stream()
                .filter(loom -> loom.reference().equals(reference))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "no loom called \"" + reference + "\"", Json.obj("reference", reference)));
    }

    /**
     * What a style asks of a loom, read from its plain record: a width, a shedding capacity, and a
     * box for every weft colour.
     *
     * <p>A weft pattern may be given instead of a colour count. It carries more than the count does,
     * because the order the colours are picked in decides whether a loom boxed at one end can pick
     * them at all, and a bare count cannot say.
     */
    public static Requirements styleRequirements(Object input) {
        JsonObject record = Check.plainObject(input, "requirements");
        double widthCm = Check.positiveNumber(record.get("widthCm"), "widthCm");
        double shafts = Check.number(orZero(record.get("shafts")), "shafts", Bounds.atLeast(0));
        double hooks = Check.number(orZero(record.get("hooks")), "hooks", Bounds.atLeast(0));
        double pullIn = Check.number(orZero(record.get("pullIn")), "pullIn", Bounds.between(0, 0.5));
        Json colours = record.get("weftColours");
        Json pattern = record.get("weftPattern");
        if (pattern.isMissing()) {
            int weftColours = Check.positiveInteger(colours.isNullish() ? Json.of(1) : colours, "weftColours");
            return new Requirements(widthCm, shafts, hooks, weftColours, Optional.empty(), pullIn);
        }
        List<Object> picks = Check.array(pattern, "weftPattern");
        List<String> named = BoxMotion.coloursIn(picks);
        if (!colours.isMissing() && !(colours.isNumber() && colours.asDouble() == named.size())) {
            throw new InvalidArgumentException(
                    "weftColours does not match the pattern given",
                    Json.obj("weftColours", colours, "counted", named.size()));
        }
        return new Requirements(widthCm, shafts, hooks, named.size(), Optional.of(BoxMotion.colours(picks)), pullIn);
    }

    private static Json orZero(Json value) {
        return value.isMissing() ? Json.of(0) : value;
    }

    /** Every reason a loom cannot take a style, in the order the rules are asked, or none when it can. */
    public static List<Problem> shortfalls(Loom loom, Requirements requirements) {
        return RULES.stream()
                .filter(rule -> rule.fails().test(loom, requirements))
                .map(rule -> rule.says().apply(loom, requirements))
                .toList();
    }

    /** Whether a loom can take a style at all. */
    public static boolean canWeave(Loom loom, Requirements requirements) {
        return shortfalls(loom, requirements).isEmpty();
    }

    /** Refuse a loom that cannot take the style, naming every reason. */
    public static Loom assertCanWeave(Loom loom, Requirements requirements) {
        List<Problem> problems = shortfalls(loom, requirements);
        if (!problems.isEmpty()) {
            throw new CapacityException(
                    loom.reference() + " cannot weave that style",
                    Json.obj("loom", loom.reference(), "problems", problems));
        }
        return loom;
    }

    /** Every loom that can take a style, fastest first. */
    public static List<Loom> loomsFor(List<Loom> looms, Requirements requirements) {
        List<Loom> found = new ArrayList<>();
        for (Loom loom : looms) {
            if (canWeave(loom, requirements)) {
                found.add(loom);
            }
        }
        found.sort(Comparator.comparingDouble(Loom::picksMinute).reversed());
        return List.copyOf(found);
    }

    /** How much of a loom's width a style uses, which is what idle reed costs. */
    public static double widthUtilisation(Loom loom, Requirements requirements) {
        return Numeric.roundTo(requirements.widthCm() / loom.reedWidthCm(), 4);
    }

    /** The register grouped by shedding motion, every motion listed even when it has no loom. */
    public static List<SheddingGroup> byShedding(List<Loom> looms) {
        List<SheddingGroup> out = new ArrayList<>();
        for (Shedding shedding : Shedding.values()) {
            out.add(new SheddingGroup(
                    shedding,
                    looms.stream()
                            .filter(loom -> loom.shedding() == shedding)
                            .map(Loom::reference)
                            .toList()));
        }
        return List.copyOf(out);
    }
}
