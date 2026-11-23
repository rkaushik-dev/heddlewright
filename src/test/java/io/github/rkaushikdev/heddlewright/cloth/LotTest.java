package io.github.rkaushikdev.heddlewright.cloth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.cloth.Lot.Reading;
import io.github.rkaushikdev.heddlewright.util.ConflictException;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LotTest {

    private static final Reading A = shade("P1", 60, 2, -1);
    private static final Reading B = shade("P2", 60.4, 2.1, -1.1);
    private static final Reading C = shade("P3", 64, 3, -2);

    private static Reading shade(String reference, double lightness, double red, double yellow) {
        return Lot.readShade(Json.obj("reference", reference, "lightness", lightness, "red", red, "yellow", yellow));
    }

    @Nested
    class ReadShade {

        @Test
        void takesAReadingOnTheThreeAxes() {
            assertThat(A).isEqualTo(new Reading("P1", 60, 2, -1));
        }

        @Test
        void refusesAReadingWithNoReferenceOrAMissingAxis() {
            assertThatThrownBy(() -> Lot.readShade(Json.obj("lightness", 60, "red", 2, "yellow", 0)))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Lot.readShade(Json.obj("reference", "P1", "lightness", 60, "red", 2)))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class ShadeDistance {

        @Test
        void isNothingBetweenAReadingAndItself() {
            assertThat(Lot.shadeDistance(A, A)).isEqualTo(0);
        }

        @Test
        void isTheStraightDistanceInColourSpace() {
            assertThat(Lot.shadeDistance(A, B)).isCloseTo(Math.sqrt(0.16 + 0.01 + 0.01), within(0.00005));
        }

        @Test
        void doesNotCareWhichWayRoundItIsAsked() {
            assertThat(Lot.shadeDistance(A, C)).isEqualTo(Lot.shadeDistance(C, A));
        }
    }

    @Nested
    class OutliersAndLotHolds {

        @Test
        void findsNothingInATightLot() {
            assertThat(Lot.outliers(List.of(A, B), 1)).isEmpty();
            assertThat(Lot.lotHolds(List.of(A, B), 1)).isTrue();
        }

        @Test
        void findsThePairThatIsTooFarApartWorstFirst() {
            List<Lot.Pair> found = Lot.outliers(List.of(A, B, C), 1);
            assertThat(found).hasSize(2);
            assertThat(found.get(0).distance()).isGreaterThan(found.get(1).distance());
            assertThat(Lot.lotHolds(List.of(A, B, C), 1)).isFalse();
        }

        @Test
        void refusesAToleranceOfNothing() {
            assertThatThrownBy(() -> Lot.outliers(List.of(A), 0)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class BuildLots {

        @Test
        void putsPiecesThatMatchIntoOneLot() {
            List<Lot.ShadeLot> lots = Lot.buildLots(List.of(A, B), 1);
            assertThat(lots).hasSize(1);
            assertThat(lots.get(0).pieces()).containsExactly("P1", "P2");
        }

        @Test
        void opensASecondLotForAPieceThatDoesNotMatch() {
            List<Lot.ShadeLot> lots = Lot.buildLots(List.of(A, B, C), 1);
            assertThat(lots).hasSize(2);
            assertThat(lots.get(1).pieces()).containsExactly("P3");
        }

        @Test
        void willNotLetALotDriftAcrossTheSpaceOneStepAtATime() {
            Reading middle = shade("P4", 62, 2.5, -1.5);
            List<Lot.ShadeLot> lots = Lot.buildLots(List.of(A, middle, C), 2.5);
            assertThat(lots.size()).isGreaterThan(1);
            for (Lot.ShadeLot lot : lots) {
                assertThat(lot.spread()).isLessThanOrEqualTo(2.5);
            }
        }

        @Test
        void reportsTheCentreAndTheSpreadOfEachLot() {
            List<Lot.ShadeLot> lots = Lot.buildLots(List.of(A, B), 1);
            assertThat(lots.get(0).centre().lightness()).isEqualTo(60.2);
            assertThat(lots.get(0).spread()).isEqualTo(Lot.shadeDistance(A, B));
        }

        @Test
        void countsTheLotsADeliveryNeeds() {
            assertThat(Lot.lotCount(List.of(A, B, C), 1)).isEqualTo(2);
            assertThat(Lot.lotCount(List.of(A, B, C), 10)).isEqualTo(1);
        }
    }

    @Nested
    class LotCentreAndLotSpread {

        @Test
        void averagesASingleReadingToItself() {
            assertThat(Lot.lotCentre(List.of(A))).isEqualTo(new Lot.Shade(60, 2, -1));
            assertThat(Lot.lotSpread(List.of(A))).isEqualTo(0);
        }

        @Test
        void takesTheWidestGapNotTheLastOne() {
            assertThat(Lot.lotSpread(List.of(A, B, C))).isEqualTo(Lot.shadeDistance(A, C));
        }
    }

    @Nested
    class AssertLotHolds {

        @Test
        void passesALotThatHolds() {
            assertThat(Lot.assertLotHolds(List.of(A, B), 1)).containsExactly(A, B);
        }

        @Test
        void refusesOneThatDoesNotNamingTheWidestPair() {
            ConflictException err =
                    catchThrowableOfType(ConflictException.class, () -> Lot.assertLotHolds(List.of(A, C), 1));
            assertThat(err).isNotNull();
            assertThat(err.detail().get("widest").get("left")).isEqualTo(Json.of("P1"));
        }
    }

    @Nested
    class LotOf {

        @Test
        void findsWhichLotAPieceWentIn() {
            List<Lot.ShadeLot> lots = Lot.buildLots(List.of(A, B, C), 1);
            assertThat(Lot.lotOf(lots, "P2")).isEqualTo(1);
            assertThat(Lot.lotOf(lots, "P3")).isEqualTo(2);
        }

        @Test
        void refusesAPieceNothingHolds() {
            assertThatThrownBy(() -> Lot.lotOf(Lot.buildLots(List.of(A), 1), "P9"))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class ByDyeLot {

        @Test
        void groupsPiecesOnTheLotTheyWereDyedIn() {
            List<?> pieces = List.of(
                    Json.obj("reference", "P1", "lot", "D1"),
                    Json.obj("reference", "P2", "lot", "D1"),
                    Json.obj("reference", "P3", "lot", "D2"));
            assertThat(Lot.byDyeLot(pieces))
                    .containsExactly(new Lot.DyeLot("D1", List.of("P1", "P2")), new Lot.DyeLot("D2", List.of("P3")));
        }

        @Test
        void gathersThePiecesNobodyRecordedALotFor() {
            assertThat(Lot.byDyeLot(List.of(Json.obj("reference", "P1"))))
                    .containsExactly(new Lot.DyeLot("unrecorded", List.of("P1")));
        }
    }

    @Nested
    class CmcDifference {

        @Test
        void forgivesLightnessByTheLightnessWeight() {
            Reading light = shade("L1", 60, 0, 0);
            Reading lighter = shade("L2", 61, 0, 0);
            double sl = (0.040975 * 60) / (1 + 0.01765 * 60);
            assertThat(Lot.shadeDistance(light, lighter, Lot.CMC_ACCEPTABILITY))
                    .isCloseTo(1 / (2 * sl), within(0.00005));
            assertThat(Lot.shadeDistance(light, lighter, new Lot.Cmc(1, 1)))
                    .isCloseTo(2 * Lot.shadeDistance(light, lighter, Lot.CMC_ACCEPTABILITY), within(0.0002));
        }

        @Test
        void holdsTheLightnessWeightFixedBelowSixteen() {
            assertThat(Lot.shadeDistance(shade("D1", 10, 0, 0), shade("D2", 11, 0, 0), Lot.CMC_ACCEPTABILITY))
                    .isEqualTo(0.9785);
        }

        @Test
        void weighsHueDifferentlyEitherSideOfTheBlueBand() {
            assertThat(Lot.shadeDistance(shade("R1", 50, 30, 20), shade("R2", 50, 32, 18), Lot.CMC_ACCEPTABILITY))
                    .isEqualTo(2.4902);
            assertThat(Lot.shadeDistance(shade("B1", 50, -20, -30), shade("B2", 50, -22, -28), Lot.CMC_ACCEPTABILITY))
                    .isEqualTo(1.8162);
        }

        @Test
        void takesTheHarsherReadingWhicheverPieceIsNamedFirst() {
            Reading red = shade("R1", 50, 30, 20);
            Reading other = shade("R2", 50, 32, 18);
            assertThat(Lot.shadeDistance(red, other, Lot.CMC_ACCEPTABILITY))
                    .isEqualTo(Lot.shadeDistance(other, red, Lot.CMC_ACCEPTABILITY));
            double oneWay = Lot.cmc(red, other, Lot.CMC_ACCEPTABILITY);
            double otherWay = Lot.cmc(other, red, Lot.CMC_ACCEPTABILITY);
            assertThat(oneWay).isNotEqualTo(otherWay);
            assertThat(Lot.shadeDistance(red, other, Lot.CMC_ACCEPTABILITY))
                    .isEqualTo(Math.round(Math.max(oneWay, otherWay) * 10000) / 10000.0);
        }

        @Test
        void keepsInOneLotAPairTheStraightDistanceWouldSplit() {
            Reading first = shade("P1", 60, 0, 0);
            Reading second = shade("P2", 61.2, 0, 0);
            assertThat(Lot.lotCount(List.of(first, second), 1)).isEqualTo(2);
            assertThat(Lot.lotCount(List.of(first, second), 1, Lot.CMC_ACCEPTABILITY))
                    .isEqualTo(1);
            assertThat(Lot.lotHolds(List.of(first, second), 1, Lot.CMC_ACCEPTABILITY))
                    .isTrue();
            assertThat(Lot.buildLots(List.of(first, second), 1, Lot.CMC_ACCEPTABILITY)
                            .get(0)
                            .spread())
                    .isEqualTo(Lot.shadeDistance(first, second, Lot.CMC_ACCEPTABILITY));
        }

        @Test
        void stillRefusesALotThatFailsOnCmc() {
            assertThat(Lot.outliers(List.of(A, B, C), 1, Lot.CMC_ACCEPTABILITY))
                    .extracting(Lot.Pair::distance)
                    .containsExactly(2.4771, Lot.shadeDistance(B, C, Lot.CMC_ACCEPTABILITY));
            assertThatThrownBy(() -> Lot.assertLotHolds(List.of(A, C), 1, Lot.CMC_ACCEPTABILITY))
                    .isInstanceOf(ConflictException.class);
            assertThat(Lot.lotSpread(List.of(A, B), Lot.CMC_ACCEPTABILITY)).isEqualTo(0.2479);
        }

        @Test
        void refusesAWeightOfNothing() {
            assertThatThrownBy(() -> new Lot.Cmc(0, 1)).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> new Lot.Cmc(2, -1)).isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void leavesTheStraightDistanceAsTheDefault() {
            assertThat(Lot.shadeDistance(A, C)).isEqualTo(Lot.shadeDistance(A, C, Lot.STRAIGHT));
            assertThat(Lot.shadeDistance(A, C, new Lot.Straight())).isEqualTo(4.2426);
        }
    }

    @Nested
    class ShadeSort {

        private final Reading standard = shade("STD", 60, 20, 10);

        @Test
        void splitsCmcIntoSignedPartsThatAddBackUp() {
            Lot.CmcParts parts = Lot.cmcParts(standard, shade("L", 61, 20, 10), Lot.CMC_ACCEPTABILITY);
            assertThat(parts.lightness()).isGreaterThan(0);
            assertThat(parts.chroma()).isEqualTo(0);
            assertThat(parts.hue()).isEqualTo(0);
            Lot.CmcParts dark = Lot.cmcParts(standard, shade("D", 59, 20, 10), Lot.CMC_ACCEPTABILITY);
            assertThat(dark.lightness()).isLessThan(0);
            Reading turned = shade("H", 60, 19, 12);
            Lot.CmcParts mixed = Lot.cmcParts(standard, turned, Lot.CMC_ACCEPTABILITY);
            assertThat(Math.sqrt(mixed.lightness() * mixed.lightness()
                            + mixed.chroma() * mixed.chroma()
                            + mixed.hue() * mixed.hue()))
                    .isCloseTo(Lot.cmc(standard, turned, Lot.CMC_ACCEPTABILITY), within(1e-9));
            assertThat(mixed.hue()).isGreaterThan(0);
        }

        @Test
        void codesAPieceOnTheStandardFiveFiveFive() {
            assertThat(Lot.sortCode(standard, shade("P1", 60, 20, 10), 0.5).code())
                    .contains("555");
        }

        @Test
        void movesTheDigitOneStepForEveryStepOfDifference() {
            double step = Lot.cmcParts(standard, shade("x", 61, 20, 10), Lot.CMC_ACCEPTABILITY)
                    .lightness();
            assertThat(Lot.sortCode(standard, shade("P2", 61, 20, 10), step).code())
                    .contains("655");
            assertThat(Lot.sortCode(standard, shade("P3", 58, 20, 10), step).code())
                    .hasValueSatisfying(code -> assertThat(code).startsWith("3"));
        }

        @Test
        void leavesAPieceTooFarOutUncoded() {
            Lot.SortCode code = Lot.sortCode(standard, shade("P4", 70, 20, 10), 0.5);
            assertThat(code.code()).isEmpty();
            assertThat(code.parts().lightness()).isGreaterThan(4.5 * 0.5);
        }

        @Test
        void boxesTheDeliveryOnTheStandardFirstAndTheMisfitsLast() {
            double step = Lot.cmcParts(standard, shade("x", 61, 20, 10), Lot.CMC_ACCEPTABILITY)
                    .lightness();
            List<Lot.SortBox> boxes = Lot.shadeSort(
                    standard,
                    List.of(
                            shade("A", 61, 20, 10),
                            shade("B", 60, 20, 10),
                            shade("C", 80, 20, 10),
                            shade("D", 61, 20, 10),
                            shade("E", 59, 20, 10)),
                    step);
            assertThat(boxes).extracting(Lot.SortBox::code).containsExactly("555", "455", "655", "out");
            assertThat(boxes.get(2).pieces()).containsExactly("A", "D");
            assertThat(boxes.get(3).pieces()).containsExactly("C");
        }

        @Test
        void refusesAStepOfNothing() {
            assertThatThrownBy(() -> Lot.sortCode(standard, standard, 0)).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Lot.shadeSort(standard, List.of(), 1))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }
}
