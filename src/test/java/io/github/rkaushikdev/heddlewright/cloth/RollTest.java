package io.github.rkaushikdev.heddlewright.cloth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.heddlewright.cloth.Roll.Grade;
import io.github.rkaushikdev.heddlewright.cloth.Roll.Piece;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.NotFoundException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RollTest {

    private static final Piece PIECE = piece("P1001", 100, 54);

    private static Piece piece(String reference, double yards, double widthInches) {
        return Roll.createPiece(Json.obj("reference", reference, "yards", yards, "widthInches", widthInches));
    }

    private static Piece piece(String reference, double yards, double widthInches, String grade) {
        return Roll.createPiece(
                Json.obj("reference", reference, "yards", yards, "widthInches", widthInches, "grade", grade));
    }

    private static double roundThree(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static List<Double> yards(List<Piece> pieces) {
        return pieces.stream().map(Piece::yards).toList();
    }

    @Nested
    class CreatePiece {

        @Test
        void worksTheAreaOutFromTheLengthAndTheWidth() {
            assertThat(PIECE.squareYards()).isEqualTo(roundThree((100.0 * 54) / 36));
        }

        @Test
        void defaultsToFirstQuality() {
            assertThat(PIECE.grade()).isEqualTo(Grade.FIRST);
            assertThat(Roll.GRADES).extracting(Object::toString).containsExactly("first", "second", "reject");
        }

        @Test
        void givesThePieceAContentIdAndFreezesIt() {
            assertThat(PIECE.id()).matches("piece-[0-9a-f]{8}");
            // A record has no setters, so nothing can change a piece once it is opened.
            assertThat(Piece.class.isRecord()).isTrue();
        }

        @Test
        void refusesAPieceOfNoLengthAndAnUnknownGrade() {
            assertThatThrownBy(() -> piece("x", 0, 54)).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> piece("x", 1, 54, "thirds")).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class CutPiece {

        @Test
        void splitsIntoTwoLengthsThatAddBackToTheOriginal() {
            List<Piece> parts = Roll.cutPiece(PIECE, 40);
            assertThat(parts.get(0).yards()).isEqualTo(40);
            assertThat(parts.get(1).yards()).isEqualTo(60);
            assertThat(parts.get(0).yards() + parts.get(1).yards()).isEqualTo(PIECE.yards());
        }

        @Test
        void suffixesTheReferences() {
            List<Piece> parts = Roll.cutPiece(PIECE, 40);
            assertThat(parts.get(0).reference()).isEqualTo("P1001A");
            assertThat(parts.get(1).reference()).isEqualTo("P1001B");
        }

        @Test
        void takesDifferentSuffixes() {
            assertThat(Roll.cutPiece(PIECE, 40, List.of("/1", "/2")).get(0).reference())
                    .isEqualTo("P1001/1");
        }

        @Test
        void refusesACutAtOrPastTheEnd() {
            assertThatThrownBy(() -> Roll.cutPiece(PIECE, 100)).hasMessageContaining("at or past the end");
            assertThatThrownBy(() -> Roll.cutPiece(PIECE, 120)).isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void keepsTheWidthAndTheGrade() {
            Piece second = Roll.regrade(PIECE, "second");
            for (Piece part : Roll.cutPiece(second, 30)) {
                assertThat(part.widthInches()).isEqualTo(54);
                assertThat(part.grade()).isEqualTo(Grade.SECOND);
            }
        }
    }

    @Nested
    class CutPieces {

        @Test
        void cutsAtSeveralPointsAndNumbersTheParts() {
            List<Piece> parts = Roll.cutPieces(PIECE, List.of(30, 70));
            assertThat(yards(parts)).containsExactly(30.0, 40.0, 30.0);
            assertThat(parts).extracting(Piece::reference).containsExactly("P1001-1", "P1001-2", "P1001-3");
        }

        @Test
        void sortsTheCutPointsBeforeWorking() {
            assertThat(yards(Roll.cutPieces(PIECE, List.of(70, 30)))).containsExactly(30.0, 40.0, 30.0);
        }

        @Test
        void addsBackToTheOriginalLength() {
            List<Piece> parts = Roll.cutPieces(PIECE, List.of(10, 25, 60));
            assertThat(parts.stream().mapToDouble(Piece::yards).sum()).isEqualTo(PIECE.yards());
        }

        @Test
        void refusesARepeatedCutPointWhichWouldMakeAPieceOfNothing() {
            assertThatThrownBy(() -> Roll.cutPieces(PIECE, List.of(30, 30))).hasMessageContaining("run forwards");
        }

        @Test
        void refusesACutPastTheEnd() {
            assertThatThrownBy(() -> Roll.cutPieces(PIECE, List.of(110))).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class Regrade {

        @Test
        void changesTheGradeAndNothingElseAboutTheLength() {
            Piece second = Roll.regrade(PIECE, "second");
            assertThat(second.grade()).isEqualTo(Grade.SECOND);
            assertThat(second.yards()).isEqualTo(PIECE.yards());
        }

        @Test
        void changesTheIdBecauseTheGradeIsPartOfTheRecord() {
            assertThat(Roll.regrade(PIECE, "second").id()).isNotEqualTo(PIECE.id());
        }
    }

    @Nested
    class RegisterTotals {

        private final List<Piece> register =
                List.of(piece("A", 60, 54), piece("B", 30, 54, "second"), piece("C", 10, 54, "reject"));

        @Test
        void addsTheLengthsAndTheAreas() {
            Roll.Totals totals = Roll.registerTotals(register);
            assertThat(totals.pieces()).isEqualTo(3);
            assertThat(totals.yards()).isEqualTo(100);
            assertThat(totals.squareYards()).isEqualTo(roundThree((100.0 * 54) / 36));
        }

        @Test
        void splitsByGradeListingEveryGradeEvenAtZero() {
            Roll.Totals totals = Roll.registerTotals(register);
            assertThat(totals.byGrade()).extracting(Roll.GradeTotal::grade).isEqualTo(Roll.GRADES);
            assertThat(totals.byGrade().get(0)).isEqualTo(new Roll.GradeTotal(Grade.FIRST, 1, 60));
        }

        @Test
        void isEmptyForAnEmptyRegister() {
            assertThat(Roll.registerTotals(List.of()).yards()).isEqualTo(0);
        }
    }

    @Nested
    class Reconcile {

        @Test
        void balancesWhenTheRegisterMatchesTheLoom() {
            Roll.Reconciliation result = Roll.reconcile(Roll.cutPieces(PIECE, List.of(40)), 100);
            assertThat(result.difference()).isEqualTo(0);
            assertThat(result.balanced()).isTrue();
        }

        @Test
        void reportsMissingClothAsANegativeDifference() {
            Roll.Reconciliation result = Roll.reconcile(List.of(piece("A", 95, 54)), 100);
            assertThat(result.difference()).isEqualTo(-5);
            assertThat(result.balanced()).isFalse();
        }

        @Test
        void letsASmallDifferenceThroughOnTheTolerance() {
            List<Piece> register = List.of(piece("A", 99.7, 54));
            assertThat(Roll.reconcile(register, 100).balanced()).isTrue();
            assertThat(Roll.reconcile(register, 100, 0.1).balanced()).isFalse();
        }
    }

    @Nested
    class PieceByReference {

        @Test
        void findsAPiece() {
            assertThat(Roll.pieceByReference(Roll.cutPiece(PIECE, 40), "P1001B").yards())
                    .isEqualTo(60);
        }

        @Test
        void saysPlainlyWhenThereIsNoSuchPiece() {
            assertThatThrownBy(() -> Roll.pieceByReference(List.of(), "P9999")).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class FirstQualityShare {

        @Test
        void isTheFirstQualityLengthOverTheWholeLength() {
            assertThat(Roll.firstQualityShare(List.of(piece("A", 80, 54), piece("B", 20, 54, "second"))))
                    .isEqualTo(0.8);
        }

        @Test
        void isNothingForAnEmptyRegister() {
            assertThat(Roll.firstQualityShare(List.of())).isEqualTo(0);
        }
    }

    @Nested
    class CutAndGrade {

        private final List<Object> sheet = List.of(
                Json.obj("yard", 10, "inches", 2),
                Json.obj("yard", 60, "through", 63),
                Json.obj("yard", 95, "kind", "hole", "inches", 2));

        private List<Roll.Graded> cut(Object... at) {
            return Roll.cutAndGrade(
                    PIECE,
                    java.util.Arrays.stream(at).map(n -> (Number) n).toList(),
                    sheet,
                    FourPoint.Scheme.FOUR_POINT,
                    FourPoint.DEFAULT_ACCEPTANCE);
        }

        @Test
        void gradesEachLengthOnTheDefectsThatFellInIt() {
            List<Roll.Graded> lengths = cut(55, 75);
            assertThat(lengths)
                    .extracting(graded -> graded.piece().reference())
                    .containsExactly("P1001-1", "P1001-2", "P1001-3");
            assertThat(lengths)
                    .extracting(graded -> graded.piece().grade())
                    .containsExactly(Grade.FIRST, Grade.SECOND, Grade.FIRST);
            assertThat(lengths.get(1).score().totalPoints()).isEqualTo(16);
            assertThat(lengths.get(2).defects()).containsExactly(Json.obj("yard", 20, "kind", "hole", "inches", 2));
        }

        @Test
        void rejectsALengthPastTwiceTheLimit() {
            List<Roll.Graded> lengths = cut(58, 64);
            assertThat(lengths.get(1).piece().grade()).isEqualTo(Grade.REJECT);
            assertThat(lengths.get(1).grade().points())
                    .isGreaterThan(FourPoint.DEFAULT_ACCEPTANCE * Roll.SECONDS_FACTOR);
        }

        @Test
        void splitsARunningFaultBetweenTheLengthsItRunsInto() {
            List<Roll.Graded> lengths = cut(62);
            assertThat(lengths.get(0).defects()).contains(Json.obj("yard", 60, "through", 62));
            assertThat(lengths.get(1).defects()).contains(Json.obj("yard", 0.001, "through", 1));
            assertThat(lengths.get(0).score().totalPoints() - 1).isEqualTo(12);
            assertThat(lengths.get(1).score().totalPoints() - 4).isEqualTo(4);
        }

        @Test
        void chargesBothSidesForAYardTheCutFallsInside() {
            List<Roll.Graded> lengths = cut(61.5);
            assertThat(lengths.get(0).score().totalPoints() - 1).isEqualTo(12);
            assertThat(lengths.get(1).score().totalPoints() - 4).isEqualTo(8);
        }

        @Test
        void gradesTheWholePieceWhenThereIsNoCut() {
            List<Roll.Graded> whole = cut();
            assertThat(whole).hasSize(1);
            assertThat(whole.get(0).piece().reference()).isEqualTo("P1001");
            assertThat(whole.get(0).score().totalPoints()).isEqualTo(1 + 16 + 4);
            assertThat(whole.get(0).piece().grade()).isEqualTo(Grade.FIRST);
        }

        @Test
        void gradesOnTheTenPointSystemWhenAsked() {
            List<Roll.Graded> lengths = Roll.cutAndGrade(PIECE, List.of(55, 75), sheet, FourPoint.Scheme.TEN_POINT, 1);
            assertThat(lengths.get(1).score().totalPoints()).isEqualTo(40);
            assertThat(lengths.get(1).piece().grade()).isEqualTo(Grade.SECOND);
        }

        @Test
        void refusesCutsThatDoNotRunForwardsOrASheetWithoutYards() {
            assertThatThrownBy(() -> cut(75, 75)).hasMessageContaining("run forwards");
            assertThatThrownBy(() -> Roll.cutAndGrade(
                            PIECE, List.of(), List.of(Json.obj("inches", 2)), FourPoint.Scheme.FOUR_POINT, 40))
                    .isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void stillNamesTheTwoHalvesOfASingleCutWithTheirSuffixes() {
            assertThat(Roll.cutPiece(PIECE, 30)).extracting(Piece::reference).containsExactly("P1001A", "P1001B");
            assertThat(Roll.cutPiece(PIECE, 30)).extracting(Piece::yards).containsExactly(30.0, 70.0);
        }
    }
}
