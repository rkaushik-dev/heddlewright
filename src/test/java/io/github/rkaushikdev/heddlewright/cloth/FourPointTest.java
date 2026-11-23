package io.github.rkaushikdev.heddlewright.cloth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FourPointTest {

    private static int points(Object... fields) {
        return FourPoint.defectPoints(Json.obj(fields));
    }

    private static double roundTwo(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static JsonObject roll(double widthInches, double yards, List<?> defects) {
        return Json.obj("widthInches", widthInches, "yards", yards, "defects", defects);
    }

    private static List<JsonObject> defectsOf(int count, double inches) {
        List<JsonObject> out = new ArrayList<>();
        for (int i = 0; i < count; i += 1) {
            out.add(Json.obj("yard", i + 1, "inches", inches));
        }
        return out;
    }

    @Nested
    class DefectPoints {

        @Test
        void scoresALengthDefectByTheBandItFallsIn() {
            assertThat(points("inches", 1)).isEqualTo(1);
            assertThat(points("inches", 3)).isEqualTo(1);
            assertThat(points("inches", 3.5)).isEqualTo(2);
            assertThat(points("inches", 6)).isEqualTo(2);
            assertThat(points("inches", 7)).isEqualTo(3);
            assertThat(points("inches", 9)).isEqualTo(3);
            assertThat(points("inches", 12)).isEqualTo(4);
        }

        @Test
        void putsEachBandBoundaryInTheLowerBand() {
            for (int[] band : new int[][] {{3, 1}, {6, 2}, {9, 3}}) {
                assertThat(points("inches", band[0])).isEqualTo(band[1]);
                assertThat(points("inches", band[0] + 0.01)).isEqualTo(band[1] + 1);
            }
        }

        @Test
        void startsAHoleAtTwoPointsHoweverSmallItIs() {
            assertThat(points("kind", "hole", "inches", 0.25)).isEqualTo(2);
            assertThat(points("kind", "hole", "inches", 1)).isEqualTo(2);
            assertThat(points("kind", "hole", "inches", 1.5)).isEqualTo(4);
        }

        @Test
        void namesItsTwoKindsAndRefusesAnythingElse() {
            assertThat(FourPoint.DEFECT_KINDS).extracting(Object::toString).containsExactly("length", "hole");
            assertThatThrownBy(() -> points("kind", "stain", "inches", 1)).isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void refusesADefectWithNoLength() {
            assertThatThrownBy(() -> points("inches", 0)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class ScoreRoll {

        private final JsonObject sheet = roll(
                60,
                100,
                List.of(
                        Json.obj("yard", 1, "inches", 2),
                        Json.obj("yard", 4, "inches", 8),
                        Json.obj("yard", 4, "inches", 4),
                        Json.obj("yard", 50, "kind", "hole", "inches", 2)));

        @Test
        void addsThePointsUpPerYard() {
            FourPoint.Score score = FourPoint.scoreRoll(sheet);
            assertThat(score.defects()).isEqualTo(4);
            assertThat(score.rawPoints()).isEqualTo(1 + 3 + 2 + 4);
            assertThat(score.totalPoints()).isEqualTo(1 + 4 + 4);
        }

        @Test
        void capsAYardAtFourPointsHoweverManyDefectsFallInIt() {
            FourPoint.Score score = FourPoint.scoreRoll(sheet);
            assertThat(score.cappedYards()).containsExactly(4);
            FourPoint.YardScore yard4 = score.perYard().stream()
                    .filter(entry -> entry.yard() == 4)
                    .findFirst()
                    .orElseThrow();
            assertThat(yard4.points()).isEqualTo(5);
            assertThat(yard4.counted()).isEqualTo(FourPoint.MAX_POINTS_PER_YARD);
        }

        @Test
        void quotesTheScorePerHundredSquareYards() {
            assertThat(FourPoint.scoreRoll(sheet).pointsPer100SquareYards())
                    .isEqualTo(roundTwo((9.0 * 3600) / (60 * 100)));
        }

        @Test
        void isNothingForACleanRoll() {
            FourPoint.Score score = FourPoint.scoreRoll(roll(60, 100, List.of()));
            assertThat(score.totalPoints()).isEqualTo(0);
            assertThat(score.pointsPer100SquareYards()).isEqualTo(0);
        }

        @Test
        void putsADefectPartWayThroughAYardIntoThatYard() {
            FourPoint.Score score = FourPoint.scoreRoll(roll(60, 10, List.of(Json.obj("yard", 2.4, "inches", 1))));
            assertThat(score.perYard()).containsExactly(new FourPoint.YardScore(3, 1, 1));
        }

        @Test
        void refusesADefectPastTheEndOfTheRoll() {
            assertThatThrownBy(() -> FourPoint.scoreRoll(roll(60, 10, List.of(Json.obj("yard", 11, "inches", 1)))))
                    .hasMessageContaining("past the end");
        }

        @Test
        void takesADifferentPerYardCap() {
            FourPoint.Score score = FourPoint.scoreRoll(sheet.with("cap", 10));
            assertThat(score.totalPoints()).isEqualTo(score.rawPoints());
            assertThat(score.cappedYards()).isEmpty();
        }
    }

    @Nested
    class GradeRoll {

        @Test
        void passesARollInsideTheTradeLimitAndSaysByHowMuch() {
            FourPoint.Score score = FourPoint.scoreRoll(roll(60, 100, List.of(Json.obj("yard", 1, "inches", 2))));
            FourPoint.RollGrade grade = FourPoint.gradeRoll(score);
            assertThat(grade.accepted()).isTrue();
            assertThat(grade.acceptance()).isEqualTo(FourPoint.DEFAULT_ACCEPTANCE);
            assertThat(grade.margin()).isGreaterThan(0);
        }

        @Test
        void failsARollPastIt() {
            FourPoint.Score score = FourPoint.scoreRoll(roll(36, 40, defectsOf(40, 12)));
            assertThat(FourPoint.gradeRoll(score).accepted()).isFalse();
        }

        @Test
        void takesATighterAcceptanceFromAFussierCustomer() {
            FourPoint.Score score = FourPoint.scoreRoll(roll(60, 10, List.of(Json.obj("yard", 1, "inches", 12))));
            assertThat(FourPoint.gradeRoll(score, 30).accepted()).isTrue();
            assertThat(FourPoint.gradeRoll(score, 20).accepted()).isFalse();
        }
    }

    @Nested
    class PointsAllowed {

        @Test
        void saysHowManyPointsARollMayCarry() {
            assertThat(FourPoint.pointsAllowed(60, 100)).isEqualTo(66);
        }

        @Test
        void allowsMoreOnABiggerRoll() {
            assertThat(FourPoint.pointsAllowed(60, 200)).isGreaterThan(FourPoint.pointsAllowed(60, 100));
        }

        @Test
        void agreesWithTheGradingWhichIsThePointOfIt() {
            int allowed = FourPoint.pointsAllowed(60, 100);
            FourPoint.Score score = FourPoint.scoreRoll(roll(60, 100, defectsOf(allowed, 1)));
            assertThat(FourPoint.gradeRoll(score).accepted()).isTrue();
        }
    }

    @Nested
    class ScoreLot {

        @Test
        void weightsEachRollByItsOwnArea() {
            FourPoint.LotScore lot = FourPoint.scoreLot(
                    List.of(roll(60, 100, List.of(Json.obj("yard", 1, "inches", 12))), roll(60, 100, List.of())));
            assertThat(lot.rolls()).isEqualTo(2);
            assertThat(lot.totalPoints()).isEqualTo(4);
            assertThat(lot.squareYards()).isCloseTo((60.0 * 200) / 36, within(0.0005));
        }

        @Test
        void readsLowerThanTheBadRollAloneBecauseTheGoodOneDilutesIt() {
            JsonObject bad = roll(60, 100, List.of(Json.obj("yard", 1, "inches", 12)));
            double alone = FourPoint.scoreRoll(bad).pointsPer100SquareYards();
            FourPoint.LotScore lot = FourPoint.scoreLot(List.of(bad, roll(60, 100, List.of())));
            assertThat(lot.pointsPer100SquareYards()).isLessThan(alone);
        }

        @Test
        void refusesAnEmptyLot() {
            assertThatThrownBy(() -> FourPoint.scoreLot(List.of())).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class RunningDefects {

        @Test
        void scoresFourPointsInEveryYardTheDefectRunsThrough() {
            FourPoint.Score score = FourPoint.scoreRoll(roll(60, 100, List.of(Json.obj("yard", 10, "through", 14))));
            assertThat(score.perYard()).extracting(FourPoint.YardScore::yard).containsExactly(10, 11, 12, 13, 14);
            assertThat(score.perYard()).allMatch(entry -> entry.points() == FourPoint.RUNNING_POINTS_PER_YARD);
            assertThat(score.totalPoints()).isEqualTo(20);
            assertThat(score.defects()).isEqualTo(1);
        }

        @Test
        void needsNoLengthBecauseItIsNotMeasured() {
            FourPoint.Score score = FourPoint.scoreRoll(roll(60, 10, List.of(Json.obj("yard", 2, "through", 2))));
            assertThat(score.perYard()).containsExactly(new FourPoint.YardScore(2, 4, 4));
        }

        @Test
        void takesPartYardsIntoTheYardsTheyFallIn() {
            FourPoint.Score score = FourPoint.scoreRoll(roll(60, 10, List.of(Json.obj("yard", 2.2, "through", 3.5))));
            assertThat(score.perYard()).extracting(FourPoint.YardScore::yard).containsExactly(3, 4);
        }

        @Test
        void stillCapsAYardItSharesWithAnotherDefect() {
            FourPoint.Score score = FourPoint.scoreRoll(
                    roll(60, 10, List.of(Json.obj("yard", 3, "inches", 2), Json.obj("yard", 3, "through", 4))));
            assertThat(score.rawPoints()).isEqualTo(1 + 4 + 4);
            assertThat(score.totalPoints()).isEqualTo(8);
            assertThat(score.cappedYards()).containsExactly(3);
        }

        @Test
        void refusesOneThatEndsBeforeItStartsOrRunsOffTheRoll() {
            assertThatThrownBy(() -> FourPoint.scoreRoll(roll(60, 10, List.of(Json.obj("yard", 5, "through", 4)))))
                    .isInstanceOf(InvalidArgumentException.class)
                    .hasMessageContaining("ends before it starts");
            assertThatThrownBy(() -> FourPoint.scoreRoll(roll(60, 10, List.of(Json.obj("yard", 5, "through", 12)))))
                    .hasMessageContaining("past the end");
        }

        @Test
        void scoresTheWholeRunAsOneDefectOnItsOwn() {
            assertThat(points("yard", 3, "through", 5)).isEqualTo(12);
            assertThat(points("yard", 3.5, "through", 4)).isEqualTo(4);
        }

        @Test
        void refusesAHoleThatRuns() {
            assertThatThrownBy(() -> points("kind", "hole", "yard", 1, "through", 3))
                    .isInstanceOf(InvalidArgumentException.class)
                    .hasMessageContaining("hole cannot run");
        }

        @Test
        void failsARollThatOneLongRunningFaultCondemns() {
            FourPoint.Score score = FourPoint.scoreRoll(roll(60, 100, List.of(Json.obj("yard", 1, "through", 20))));
            assertThat(FourPoint.gradeRoll(score).accepted()).isFalse();
            assertThat(score.pointsPer100SquareYards()).isEqualTo(roundTwo((80.0 * 3600) / (60 * 100)));
        }
    }
}
