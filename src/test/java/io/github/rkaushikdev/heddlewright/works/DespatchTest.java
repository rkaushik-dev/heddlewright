package io.github.rkaushikdev.heddlewright.works;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.heddlewright.util.ConflictException;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.NotFoundException;
import io.github.rkaushikdev.heddlewright.works.Despatch.Note;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DespatchTest {

    private static final JsonObject P1 = Json.obj("reference", "P1", "yards", 60);
    private static final JsonObject P2 = Json.obj("reference", "P2", "yards", 40, "grade", "second");

    private static final Note NOTE = note("DN-1", "Ravensward", P1, P2);

    private static Note note(String reference, String customer, Object... pieces) {
        return Despatch.createNote(Json.obj("reference", reference, "customer", customer, "pieces", List.of(pieces)));
    }

    @Nested
    class CreateNote {

        @Test
        void derivesTheLengthFromThePiecesRatherThanTakingOne() {
            assertThat(NOTE.yards()).isEqualTo(100);
        }

        @Test
        void defaultsAPieceWithNoGradeToFirstQuality() {
            assertThat(NOTE.pieces().get(0).grade()).isEqualTo("first");
        }

        @Test
        void givesTheNoteAnIdAndFreezesIt() {
            assertThat(NOTE.id()).matches("note-[0-9a-f]{8}");
            // A record has no setters, so nothing can change a note once it is written.
            assertThat(Note.class.isRecord()).isTrue();
        }

        @Test
        void refusesAnEmptyNoteOrAPieceWithNoLength() {
            assertThatThrownBy(() -> note("DN-2", "Ravensward")).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> note("DN-2", "Ravensward", Json.obj("reference", "P1", "yards", 0)))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class NoteByGrade {

        @Test
        void splitsTheLengthByGradeMostFirst() {
            assertThat(Despatch.noteByGrade(NOTE))
                    .containsExactly(
                            Json.obj("grade", "first", "count", 1, "yards", 60),
                            Json.obj("grade", "second", "count", 1, "yards", 40));
        }
    }

    @Nested
    class AssertPiecesFree {

        @Test
        void passesANoteWhosePiecesAreNowhereElse() {
            assertThat(Despatch.assertPiecesFree(NOTE, List.of())).isSameAs(NOTE);
        }

        @Test
        void refusesANoteThatSendsAPieceTwice() {
            Note doubled = note("DN-2", "Ravensward", P1, P1);
            assertThatThrownBy(() -> Despatch.assertPiecesFree(doubled, List.of()))
                    .hasMessageContaining("on this note twice");
        }

        @Test
        void refusesAPieceAlreadyOnAnotherNote() {
            Note other = note("DN-3", "Other", P1);
            ConflictException err = catchThrowableOfType(
                    ConflictException.class, () -> Despatch.assertPiecesFree(NOTE, List.of(other)));
            assertThat(err).isNotNull();
            assertThat(err.detail().get("note")).isEqualTo(Json.of("DN-3"));
        }
    }

    @Nested
    class SendNote {

        @Test
        void marksANoteSentOnADate() {
            assertThat(Despatch.sendNote(NOTE, "2026-03-01").sentOn()).contains("2026-03-01");
        }

        @Test
        void refusesToSendOneTwice() {
            Note gone = Despatch.sendNote(NOTE, "2026-03-01");
            assertThatThrownBy(() -> Despatch.sendNote(gone, "2026-03-02"))
                    .hasMessageContaining("went out on 2026-03-01");
        }

        @Test
        void keepsThePiecesAndTheLength() {
            Note gone = Despatch.sendNote(NOTE, "2026-03-01");
            assertThat(gone.yards()).isEqualTo(NOTE.yards());
            assertThat(gone.pieces()).isEqualTo(NOTE.pieces());
        }
    }

    @Nested
    class PendingAndSent {

        private final Note gone = Despatch.sendNote(NOTE, "2026-03-01");
        private final Note later = Despatch.sendNote(note("DN-4", "Other", P2), "2026-03-05");

        @Test
        void separatesWhatIsWaitingFromWhatHasGone() {
            assertThat(Despatch.pending(List.of(NOTE, gone)))
                    .extracting(Note::reference)
                    .containsExactly("DN-1");
            assertThat(Despatch.sent(List.of(NOTE, gone)))
                    .extracting(Note::reference)
                    .containsExactly("DN-1");
        }

        @Test
        void listsWhatHasGoneMostRecentFirst() {
            assertThat(Despatch.sent(List.of(gone, later)))
                    .extracting(entry -> entry.sentOn().orElseThrow())
                    .containsExactly("2026-03-05", "2026-03-01");
        }
    }

    @Nested
    class NoteByReference {

        @Test
        void findsANoteAndRefusesOneThatIsNotThere() {
            assertThat(Despatch.noteByReference(List.of(NOTE), "DN-1")).isSameAs(NOTE);
            assertThatThrownBy(() -> Despatch.noteByReference(List.of(NOTE), "DN-9"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ByCustomer {

        @Test
        void totalsWhatWentToEachCustomer() {
            Note other = note("DN-5", "Other", P2);
            assertThat(Despatch.byCustomer(List.of(NOTE, other)))
                    .containsExactly(
                            Json.obj("customer", "Ravensward", "count", 1, "yards", 100),
                            Json.obj("customer", "Other", "count", 1, "yards", 40));
        }
    }

    @Nested
    class CoversOrder {

        @Test
        void saysAnOrderIsCoveredOnceEnoughHasGone() {
            assertThat(Despatch.coversOrder(List.of(NOTE), "Ravensward", 100))
                    .isEqualTo(new Despatch.Coverage(100, 100, true, 0, 0, 0));
        }

        @Test
        void saysHowFarShortAPartDeliveryIs() {
            Despatch.Coverage reading = Despatch.coversOrder(List.of(NOTE), "Ravensward", 250);
            assertThat(reading.covered()).isFalse();
            assertThat(reading.shortYards()).isEqualTo(150);
        }

        @Test
        void countsNothingForACustomerWithNoNotes() {
            assertThat(Despatch.coversOrder(List.of(NOTE), "Nobody", 100).sentYards())
                    .isEqualTo(0);
        }
    }

    @Nested
    class CoversOrderOnTerms {

        private final Note firsts = note(
                "DN-2",
                "Ravensward",
                Json.obj("reference", "P3", "yards", 49),
                Json.obj("reference", "P4", "yards", 48));

        @Test
        void takesADeliveryInsideTheToleranceAsCovered() {
            Despatch.Coverage reading =
                    Despatch.coversOrder(List.of(firsts), "Ravensward", 100, Despatch.Terms.usual());
            assertThat(reading.covered()).isTrue();
            assertThat(reading.shortYards()).isEqualTo(3);
            assertThat(Despatch.coversOrder(List.of(firsts), "Ravensward", 100).covered())
                    .isFalse();
        }

        @Test
        void leavesSecondsOffAFirstQualityOrder() {
            Despatch.Coverage reading = Despatch.coversOrder(List.of(NOTE), "Ravensward", 100, Despatch.Terms.usual());
            assertThat(reading.sentYards()).isEqualTo(60);
            assertThat(reading.uncountedYards()).isEqualTo(40);
            assertThat(reading.covered()).isFalse();
        }

        @Test
        void measuresWhatGoesPastTheToleranceAsOver() {
            Despatch.Coverage reading = Despatch.coversOrder(
                    List.of(NOTE, firsts), "Ravensward", 150, new Despatch.Terms(3, Optional.empty()));
            assertThat(reading.sentYards()).isEqualTo(197);
            assertThat(reading.overYards()).isEqualTo(197 - 154.5);
            assertThat(reading.shortYards()).isEqualTo(0);
        }

        @Test
        void refusesAToleranceNoContractCarries() {
            assertThatThrownBy(() -> new Despatch.Terms(60, Optional.empty()))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> new Despatch.Terms(-1, Optional.empty()))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class PackingList {

        private final Note load = note(
                "DN-3",
                "Ravensward",
                Json.obj("reference", "A", "yards", 60),
                Json.obj("reference", "B", "yards", 70),
                Json.obj("reference", "C", "yards", 30),
                Json.obj("reference", "D", "yards", 40));

        @Test
        void packsTheLongestPiecesFirstIntoTheFirstBaleWithRoom() {
            assertThat(Despatch.packingList(load, 100))
                    .containsExactly(
                            new Despatch.Bale(1, List.of("B", "C"), 100), new Despatch.Bale(2, List.of("A", "D"), 100));
        }

        @Test
        void givesALongPieceABaleOfItsOwnAndPairsTheShortOnes() {
            assertThat(Despatch.packingList(load, 70))
                    .extracting(Despatch.Bale::pieces)
                    .containsExactly(List.of("B"), List.of("A"), List.of("D", "C"));
        }

        @Test
        void refusesAPieceLongerThanABale() {
            assertThatThrownBy(() -> Despatch.packingList(load, 65))
                    .isInstanceOf(InvalidArgumentException.class)
                    .hasMessageContaining("B is longer");
        }
    }
}
