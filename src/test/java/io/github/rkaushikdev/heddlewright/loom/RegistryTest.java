package io.github.rkaushikdev.heddlewright.loom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.heddlewright.loom.BoxMotion.Boxing;
import io.github.rkaushikdev.heddlewright.loom.Loom.Picking;
import io.github.rkaushikdev.heddlewright.loom.Loom.Shedding;
import io.github.rkaushikdev.heddlewright.util.CapacityException;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonArray;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.NotFoundException;
import io.github.rkaushikdev.heddlewright.util.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RegistryTest {

    private static final Loom DOBBY = Registry.createLoom(Json.obj(
            "reference", "L1",
            "shedding", "dobby",
            "reedWidthCm", 190,
            "shafts", 16,
            "treadles", 0,
            "picksMinute", 220,
            "shuttleBoxes", 4));

    private static final Loom JACQUARD = Registry.createLoom(Json.obj(
            "reference",
            "L2",
            "shedding",
            "jacquard",
            "picking",
            "rapier",
            "reedWidthCm",
            260,
            "hooks",
            1344,
            "picksMinute",
            180));

    private static final Loom TAPPET = Registry.createLoom(
            Json.obj("reference", "L3", "shedding", "tappet", "reedWidthCm", 120, "shafts", 4, "picksMinute", 300));

    private static final List<Loom> REGISTER = List.of(DOBBY, JACQUARD, TAPPET);

    private static Requirements style(Object... fields) {
        return Registry.styleRequirements(Json.obj(fields));
    }

    private static List<String> codes(List<Problem> problems) {
        return problems.stream().map(Problem::code).toList();
    }

    private static Loom dobbyWith(String key, Object value) {
        return Registry.createLoom(DOBBY.toRecord().with(key, value));
    }

    @Nested
    class CreateLoom {

        @Test
        void recordsTheMachineAndFreezesIt() {
            assertThat(DOBBY.shafts()).isEqualTo(16);
            assertThat(DOBBY.hooks()).isEqualTo(0);
            // A record has no setters, so nothing can change one after it is built.
            assertThat(Loom.class.isRecord()).isTrue();
        }

        @Test
        void countsHooksInsteadOfShaftsOnAJacquard() {
            assertThat(JACQUARD.hooks()).isEqualTo(1344);
            assertThat(JACQUARD.shafts()).isEqualTo(0);
        }

        @Test
        void defaultsThePickingMotionAndTheBoxCount() {
            assertThat(TAPPET.picking()).isEqualTo(Picking.SHUTTLE);
            assertThat(TAPPET.shuttleBoxes()).isEqualTo(1);
        }

        @Test
        void namesTheMotionsItKnowsAndRefusesOthers() {
            assertThat(Shedding.SHEDDING).extracting(Object::toString).containsExactly("tappet", "dobby", "jacquard");
            assertThat(Picking.PICKING).extracting(Object::toString).contains("air jet");
            assertThatThrownBy(() -> dobbyWith("shedding", "lever")).isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void refusesAShaftLoomWithNoShafts() {
            assertThatThrownBy(() -> Registry.createLoom(
                            Json.obj("reference", "x", "shedding", "dobby", "reedWidthCm", 100, "picksMinute", 200)))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class LoomByReference {

        @Test
        void findsALoomAndRefusesOneThatIsNotRegistered() {
            assertThat(Registry.loomByReference(REGISTER, "L2")).isSameAs(JACQUARD);
            assertThatThrownBy(() -> Registry.loomByReference(REGISTER, "L9")).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Shortfalls {

        @Test
        void isEmptyWhenTheLoomCanTakeTheStyle() {
            assertThat(Registry.shortfalls(DOBBY, style("widthCm", 150, "shafts", 12)))
                    .isEmpty();
        }

        @Test
        void namesALoomThatIsTooNarrow() {
            List<Problem> problems = Registry.shortfalls(TAPPET, style("widthCm", 150, "shafts", 4));
            assertThat(codes(problems)).containsExactly("too_narrow");
            assertThat(problems.get(0).detail()).isEqualTo(Json.obj("needed", 150, "available", 120));
        }

        @Test
        void namesEveryReasonAtOnceRatherThanStoppingAtTheFirst() {
            List<Problem> problems = Registry.shortfalls(TAPPET, style("widthCm", 200, "shafts", 12, "weftColours", 3));
            assertThat(codes(problems).stream().sorted().toList())
                    .containsExactly("too_few_boxes", "too_few_shafts", "too_narrow");
        }

        @Test
        void refusesAJacquardStyleOnAShaftLoom() {
            List<Problem> problems = Registry.shortfalls(DOBBY, style("widthCm", 150, "hooks", 400));
            assertThat(codes(problems)).contains("not_jacquard");
        }

        @Test
        void countsHooksRatherThanShaftsOnAJacquard() {
            assertThat(Registry.shortfalls(JACQUARD, style("widthCm", 250, "hooks", 1344)))
                    .isEmpty();
            assertThat(Registry.shortfalls(JACQUARD, style("widthCm", 250, "hooks", 2688))
                            .get(0)
                            .code())
                    .isEqualTo("too_few_hooks");
        }
    }

    @Nested
    class CanWeaveAndAssertCanWeave {

        @Test
        void answersTheSameQuestionTwoWays() {
            Requirements wanted = style("widthCm", 150, "shafts", 12);
            assertThat(Registry.canWeave(DOBBY, wanted)).isTrue();
            assertThat(Registry.assertCanWeave(DOBBY, wanted)).isSameAs(DOBBY);
        }

        @Test
        void throwsWithEveryReasonAttached() {
            CapacityException err = catchThrowableOfType(
                    CapacityException.class,
                    () -> Registry.assertCanWeave(TAPPET, style("widthCm", 200, "shafts", 12)));
            assertThat(err).isNotNull();
            assertThat(((JsonArray) err.detail().get("problems")).size()).isEqualTo(2);
        }
    }

    @Nested
    class LoomsFor {

        @Test
        void listsTheLoomsThatCanTakeAStyleFastestFirst() {
            List<Loom> found = Registry.loomsFor(REGISTER, style("widthCm", 100, "shafts", 4));
            assertThat(found).extracting(Loom::reference).containsExactly("L3", "L1", "L2");
        }

        @Test
        void letsTheJacquardTakeAShaftStyleBecauseEveryEndIsItsOwnHook() {
            assertThat(Registry.canWeave(JACQUARD, style("widthCm", 100, "shafts", 16)))
                    .isTrue();
            assertThat(Registry.canWeave(TAPPET, style("widthCm", 100, "shafts", 16)))
                    .isFalse();
        }

        @Test
        void isEmptyWhenNothingOnTheFloorWillDo() {
            assertThat(Registry.loomsFor(REGISTER, style("widthCm", 400, "shafts", 4)))
                    .isEmpty();
        }

        @Test
        void findsTheJacquardForAJacquardStyleAndNothingElse() {
            List<Loom> found = Registry.loomsFor(REGISTER, style("widthCm", 200, "hooks", 400));
            assertThat(found).extracting(Loom::reference).containsExactly("L2");
        }
    }

    @Nested
    class WidthUtilisation {

        @Test
        void isTheShareOfTheReedTheStyleUses() {
            assertThat(Registry.widthUtilisation(DOBBY, style("widthCm", 95))).isEqualTo(0.5);
        }

        @Test
        void readsLowWhenANarrowStyleGoesOnAWideLoom() {
            assertThat(Registry.widthUtilisation(JACQUARD, style("widthCm", 65)))
                    .isEqualTo(0.25);
        }
    }

    @Nested
    class ByShedding {

        @Test
        void groupsTheRegisterListingEveryMotionEvenWhenEmpty() {
            assertThat(Registry.byShedding(List.of(DOBBY, TAPPET)))
                    .containsExactly(
                            new Registry.SheddingGroup(Shedding.TAPPET, List.of("L3")),
                            new Registry.SheddingGroup(Shedding.DOBBY, List.of("L1")),
                            new Registry.SheddingGroup(Shedding.JACQUARD, List.of()));
        }
    }

    @Nested
    class HowALoomIsBoxed {

        @Test
        void readsAsBoxedAtOneEndUnlessTheRegisterSaysOtherwise() {
            assertThat(DOBBY.boxedAt()).isEqualTo(Boxing.ONE);
            assertThat(dobbyWith("boxedAt", "both").boxedAt()).isEqualTo(Boxing.BOTH);
        }

        @Test
        void refusesABoxingNobodyBuilds() {
            assertThatThrownBy(() -> dobbyWith("boxedAt", "left")).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class AStyleQuotedAsAWeftPattern {

        private static final List<String> CHECK = List.of("red", "red", "blue", "blue");
        private static final List<String> SINGLE = List.of("red", "blue", "blue", "blue");

        @Test
        void countsItsOwnColours() {
            Requirements wanted = style("widthCm", 100, "weftPattern", CHECK);
            assertThat(wanted.weftColours()).isEqualTo(2);
            assertThat(wanted.weftPattern()).contains(CHECK);
        }

        @Test
        void keepsACopySoAmendingTheListItWasGivenChangesNothing() {
            List<String> pattern = new ArrayList<>(CHECK);
            Requirements wanted = style("widthCm", 100, "weftPattern", pattern);
            pattern.set(0, "green");
            assertThat(wanted.weftPattern().orElseThrow().get(0)).isEqualTo("red");
        }

        @Test
        void refusesAColourCountThatDisagreesWithThePattern() {
            assertThatThrownBy(() -> style("widthCm", 100, "weftPattern", CHECK, "weftColours", 3))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatCode(() -> style("widthCm", 100, "weftPattern", CHECK, "weftColours", 2))
                    .doesNotThrowAnyException();
        }

        @Test
        void carriesNoPatternWhenItWasQuotedAsACount() {
            assertThat(style("widthCm", 100, "weftColours", 2).weftPattern()).isEqualTo(Optional.empty());
        }

        @Test
        void cannotGoOnALoomBoxedAtOneEndWhenThePicksDoNotPair() {
            Requirements wanted = style("widthCm", 100, "weftPattern", SINGLE);
            List<Problem> problems = Registry.shortfalls(DOBBY, wanted);
            assertThat(codes(problems)).containsExactly("shuttle_strands");
            JsonObject run = (JsonObject) problems.get(0).detail().get("runs").at(0);
            assertThat(run.get("colour")).isEqualTo(Json.of("red"));
            assertThat(run.get("picks")).isEqualTo(Json.of(1));
        }

        @Test
        void goesOnTheSameLoomBoxedAtBothEnds() {
            Requirements wanted = style("widthCm", 100, "weftPattern", SINGLE);
            assertThat(Registry.canWeave(dobbyWith("boxedAt", "both"), wanted)).isTrue();
        }

        @Test
        void saysNothingAboutPairingWhenTheStyleWasQuotedAsACount() {
            // A bare count carries no order, so there is nothing here to fail on.
            assertThat(Registry.canWeave(DOBBY, style("widthCm", 100, "weftColours", 2)))
                    .isTrue();
        }

        @Test
        void stillWantsABoxForEveryColour() {
            Requirements wanted =
                    style("widthCm", 100, "weftPattern", List.of("a", "a", "b", "b", "c", "c", "d", "d", "e", "e"));
            assertThat(codes(Registry.shortfalls(DOBBY, wanted))).containsExactly("too_few_boxes");
        }
    }

    @Nested
    class TheRules {

        private final Loom rapier = Registry.createLoom(Json.obj(
                "reference",
                "R1",
                "shedding",
                "dobby",
                "picking",
                "rapier",
                "reedWidthCm",
                190,
                "shafts",
                16,
                "picksMinute",
                450,
                "shuttleBoxes",
                8));

        @Test
        void asksForReedSpaceForThePullIn() {
            Requirements wide = style("widthCm", 180, "shafts", 8, "pullIn", 0.06);
            List<Problem> problems = Registry.shortfalls(DOBBY, wide);
            assertThat(codes(problems)).containsExactly("too_narrow");
            assertThat(problems.get(0).detail()).isEqualTo(Json.obj("needed", 191.49, "available", 190));
            assertThat(wide.reedSpaceCm()).isEqualTo(191.49);
            assertThat(Registry.canWeave(DOBBY, style("widthCm", 178, "shafts", 8, "pullIn", 0.06)))
                    .isTrue();
        }

        @Test
        void takesAStyleWithNoPullInAtItsOwnWidth() {
            assertThat(style("widthCm", 150).reedSpaceCm()).isEqualTo(150);
            assertThat(Requirements.fromRecord(Json.obj("widthCm", 150)).reedSpaceCm())
                    .isEqualTo(150);
            assertThat(Requirements.fromRecord(Json.obj("widthCm", 150, "pullIn", 0.04))
                            .reedSpaceCm())
                    .isEqualTo(156.25);
        }

        @Test
        void refusesAPullInNoClothCouldHave() {
            assertThatThrownBy(() -> style("widthCm", 150, "pullIn", 0.6)).isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void pairsNoPicksOnAShuttlelessLoom() {
            Requirements single = style("widthCm", 100, "weftPattern", List.of("red", "blue", "blue", "blue"));
            assertThat(Registry.canWeave(rapier, single)).isTrue();
            assertThat(codes(Registry.shortfalls(DOBBY, single))).containsExactly("shuttle_strands");
        }

        @Test
        void countsWeftFeedersRatherThanBoxesOnAShuttlelessLoom() {
            List<Problem> problems = Registry.shortfalls(rapier, style("widthCm", 100, "weftColours", 9));
            assertThat(codes(problems)).containsExactly("too_few_feeders");
            assertThat(problems.get(0).message()).contains("8 weft feeders");
            assertThat(codes(Registry.shortfalls(DOBBY, style("widthCm", 100, "weftColours", 9))))
                    .containsExactly("too_few_boxes");
        }

        @Test
        void asksEveryRuleInATableItCanName() {
            assertThat(Registry.SHORTFALL_CODES)
                    .containsExactly(
                            "too_narrow",
                            "not_jacquard",
                            "too_few_hooks",
                            "too_few_shafts",
                            "too_few_boxes",
                            "too_few_feeders",
                            "shuttle_strands");
            List<Problem> problems = Registry.shortfalls(TAPPET, style("widthCm", 200, "shafts", 12, "weftColours", 3));
            assertThat(codes(problems)).containsExactly("too_narrow", "too_few_shafts", "too_few_boxes");
        }
    }
}
