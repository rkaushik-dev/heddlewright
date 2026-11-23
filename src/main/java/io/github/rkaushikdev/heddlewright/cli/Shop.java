package io.github.rkaushikdev.heddlewright.cli;

import io.github.rkaushikdev.heddlewright.analysis.Sett;
import io.github.rkaushikdev.heddlewright.cli.Parse.Option;
import io.github.rkaushikdev.heddlewright.cli.Parse.Type;
import io.github.rkaushikdev.heddlewright.jacquard.Card;
import io.github.rkaushikdev.heddlewright.jacquard.Deck;
import io.github.rkaushikdev.heddlewright.jacquard.Estimate;
import io.github.rkaushikdev.heddlewright.jacquard.Machine;
import io.github.rkaushikdev.heddlewright.loom.BoxPlan;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.Matrix;
import io.github.rkaushikdev.heddlewright.util.Table;
import io.github.rkaushikdev.heddlewright.util.Text;
import io.github.rkaushikdev.heddlewright.weave.Catalogue;
import io.github.rkaushikdev.heddlewright.weave.Satin;
import io.github.rkaushikdev.heddlewright.weave.Twill;
import io.github.rkaushikdev.heddlewright.yarn.Count;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The commands that answer about weaves, yarns and card decks rather than about one drawn draft. */
public final class Shop {

    /** What this class puts in the register. */
    public static final List<String> SHOP_COMMANDS =
            List.of("weave", "sett", "count", "systems", "catalogue", "deck", "boxes");

    private Shop() {}

    /** Put the shop commands in the register. */
    static void define() {
        Registry.define(
                "weave",
                "print one repeat of a named weave",
                Draw.options(
                        "kind", Option.of(Type.STRING, "twill"),
                        "notation", Option.of(Type.STRING, "2/2"),
                        "threads", Option.of(Type.INTEGER, 5),
                        "move", Option.of(Type.INTEGER, 2)),
                parsed -> {
                    JsonObject options = Parse.checkOptions(parsed, Registry.specOf("weave"));
                    if (options.get("kind").equals(Json.of("satin"))) {
                        int threads = options.get("threads").asInt();
                        int move = options.get("move").asInt();
                        String moves = Satin.regularSatinMoves(threads).stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" "));
                        String header = "satin " + threads + " move " + move + ", legal moves " + moves;
                        return String.join(
                                "\n", header, Satin.satinWeave(threads, move).format());
                    }
                    return Twill.twillWeave(Text.string(options.get("notation")))
                            .format();
                });

        Registry.define(
                "sett",
                "work out the sett a cloth and a yarn will take",
                Draw.options(
                        "notation", Option.of(Type.STRING, "2/2"),
                        "weave", Option.of(Type.STRING),
                        "family", Option.of(Type.STRING),
                        "count", Option.required(Type.NUMBER),
                        "system", Option.of(Type.STRING, "ne"),
                        "picksPerInch", Option.of(Type.NUMBER, 24),
                        "endsPerInch", Option.of(Type.NUMBER, 24)),
                parsed -> {
                    JsonObject options = Parse.checkOptions(parsed, Registry.specOf("sett"));
                    // A catalogue weave, when one is named, takes the place of the twill notation, and
                    // a weave family brings Brierley's figure in beside Ashenhurst's.
                    Json named = options.get("weave");
                    Matrix cloth = named.isNullish()
                            ? Twill.twillWeave(Text.string(options.get("notation")))
                            : Catalogue.weaveByName(Text.string(named));
                    double count = options.get("count").asDouble();
                    String system = Text.string(options.get("system"));
                    JsonObject rows = ((JsonObject) Json.of(Sett.ashenhurstSett(cloth, count, system)))
                            .with(
                                    "twillAngle",
                                    Twill.twillAngle(
                                            options.get("endsPerInch").asDouble(),
                                            options.get("picksPerInch").asDouble()));
                    Json family = options.get("family");
                    if (family.isNullish()) {
                        return Table.renderPairs(rows);
                    }
                    Sett.Brierley brierley = Sett.brierleySett(cloth, count, system, Sett.Family.parse(family));
                    return Table.renderPairs(
                            rows.with("averageFloat", brierley.averageFloat(), "brierley", brierley.sett()));
                });

        Registry.define(
                "count",
                "convert a yarn count between systems",
                Draw.options(
                        "value", Option.required(Type.NUMBER),
                        "from", Option.required(Type.STRING),
                        "to", Option.of(Type.STRING, "tex")),
                parsed -> {
                    JsonObject options = Parse.checkOptions(parsed, Registry.specOf("count"));
                    double value = options.get("value").asDouble();
                    String from = Text.string(options.get("from"));
                    String to = Text.string(options.get("to"));
                    double converted = Count.convertCount(value, from, to);
                    return Text.number(value) + " " + from + " = " + Text.number(converted) + " " + to;
                });

        Registry.define("systems", "list the yarn count systems the tool knows", Draw.options(), parsed -> {
            List<Json> rows = new ArrayList<>();
            Count.COUNT_SYSTEMS.forEach(
                    (name, spec) -> rows.add(Json.obj("system", name, "kind", spec.kind(), "meaning", spec.label())));
            return Table.renderTable(
                    rows, List.of(Table.Column.of("system"), Table.Column.of("kind"), Table.Column.of("meaning")));
        });

        Registry.define(
                "catalogue",
                "list the weaves the package knows, measured",
                Draw.options("family", Option.of(Type.STRING)),
                parsed -> {
                    JsonObject options = Parse.checkOptions(parsed, Registry.specOf("catalogue"));
                    Json family = options.get("family");
                    List<Catalogue.Measurement> rows = Catalogue.measureAll().stream()
                            .filter(row ->
                                    family.isNullish() || Json.of(row.family()).equals(family))
                            .toList();
                    if (rows.isEmpty()) {
                        return "no weaves in that family. families: " + String.join(", ", Catalogue.families());
                    }
                    List<Table.Column> columns = List.of(
                            Table.Column.of("name"),
                            Table.Column.of("family"),
                            Table.Column.of("warpFace").labelled("warp face"),
                            Table.Column.of("longestFloat").labelled("float"),
                            Table.Column.of("firmness"));
                    return Table.renderTable(rows, columns);
                });

        Registry.define(
                "deck",
                "cut a card deck for a named weave and price it",
                Draw.options("weave", Option.of(Type.STRING, "twill 2/2"), "repeats", Option.of(Type.INTEGER, 1)),
                parsed -> {
                    JsonObject options = Parse.checkOptions(parsed, Registry.specOf("deck"));
                    String weave = Text.string(options.get("weave"));
                    Matrix repeat = Catalogue.weaveByName(weave);
                    Machine machine = Machine.smallestMachineFor(repeat.cols());
                    List<List<Integer>> plan = new ArrayList<>();
                    for (int round = 0; round < options.get("repeats").asInt(); round += 1) {
                        plan.addAll(repeat.allRows());
                    }
                    List<Card> deck = Deck.deckFromLiftplan(machine, Matrix.of(plan));
                    Estimate.Quote quote = Estimate.quoteDeck(deck);
                    return Table.renderPairs(Json.obj(
                            "weave", weave,
                            "machine", machine.name(),
                            "cards", Deck.deckLength(deck),
                            "repeat", Deck.deckRepeat(deck),
                            "holes", Deck.totalHoles(deck),
                            "poundsAndPence", Text.fixed(quote.totalPence() / 100, 2)));
                });

        Registry.define(
                "boxes",
                "gait a weft order up on a drop box loom",
                Draw.options(
                        "pattern", Option.required(Type.STRING),
                        "boxes", Option.of(Type.INTEGER, 4),
                        "boxedAt", Option.of(Type.STRING, "both"),
                        "picksMinute", Option.of(Type.NUMBER, 180),
                        "allot", Option.of(Type.STRING, "count")),
                parsed -> {
                    JsonObject options = Parse.checkOptions(parsed, Registry.specOf("boxes"));
                    // The pattern comes in as one argument so that a weaver can paste the order off a
                    // pattern card, "ground ground blue blue", and be done.
                    List<String> pattern = Arrays.stream(
                                    Text.string(options.get("pattern")).split("(" + Text.SPACE_CLASS + "|,)+", -1))
                            .filter(word -> !word.isEmpty())
                            .toList();
                    BoxPlan plan = BoxPlan.boxMotionPlan(Json.obj(
                            "pattern", pattern,
                            "boxes", options.get("boxes"),
                            "boxedAt", options.get("boxedAt"),
                            "picksMinute", options.get("picksMinute"),
                            "allot", options.get("allot")));
                    return Table.renderPairs(Json.obj(
                            "picks", plan.picks(),
                            "colours", plan.colours(),
                            "boxes",
                                    joined(
                                            plan.allotment().stream().map(entry -> entry.box() + ":" + entry.colour()),
                                            "  "),
                            "chain", joined(plan.chain().stream().map(String::valueOf), " "),
                            "changes", plan.changes(),
                            "longestLift", plan.longestLift(),
                            "travel", plan.travel(),
                            "picksLost", plan.picksLost(),
                            "picksMinute", plan.picksMinute()));
                });
    }

    private static String joined(Stream<String> parts, String gap) {
        return parts.collect(Collectors.joining(gap));
    }
}
