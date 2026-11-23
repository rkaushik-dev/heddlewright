package io.github.rkaushikdev.heddlewright.works;

import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Collation;
import io.github.rkaushikdev.heddlewright.util.ConflictException;
import io.github.rkaushikdev.heddlewright.util.Group;
import io.github.rkaushikdev.heddlewright.util.Ids;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.NotFoundException;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import io.github.rkaushikdev.heddlewright.util.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Despatch.
 *
 * <p>A note lists pieces, not metres. The metres follow from the pieces, and the moment a note is
 * allowed to carry a length of its own the two stop agreeing and nobody can say which is right. So
 * the length is always derived, and a piece can only be on one open note at a time.
 *
 * <p>A contract quantity is never exact. The terms carry a tolerance either way, three per cent being
 * usual, and say which grades the buyer takes against the order: a second sent with a first quality
 * order goes on the note but does not fill the order. Inside the tolerance an order is covered short
 * or long; past it the buyer may refuse the excess.
 *
 * <p>The pieces go out in bales. A bale is packed to a length the carrier and the warehouse agree on,
 * and the packing list says which pieces are in which bale.
 */
public final class Despatch {

    /** A piece as a note carries it. A grade that is not text is kept as its text. */
    public record NotePiece(String reference, double yards, String grade) {}

    /**
     * A despatch note. Its length is the sum of its pieces, and its id is a content id of everything
     * else. A carrier or a date that is not text is kept as its text.
     *
     * @param reference the note number
     * @param customer who it goes to
     * @param carrier who takes it, or empty
     * @param sentOn the day it went, if it has
     * @param pieces what it carries
     * @param yards the length of the pieces
     * @param id the content id
     */
    public record Note(
            String reference,
            String customer,
            String carrier,
            Optional<String> sentOn,
            List<NotePiece> pieces,
            double yards,
            String id)
            implements Json.Writable {

        /** Copies the pieces. */
        public Note {
            pieces = List.copyOf(pieces);
        }

        private JsonObject content() {
            return Json.obj(
                    "reference", reference,
                    "customer", customer,
                    "carrier", carrier,
                    "sentOn", sentOn.<Object>map(day -> day).orElse(Json.NULL),
                    "pieces", pieces,
                    "yards", yards);
        }

        /** The note as it is stored and sent, with a null date until it goes. */
        @Override
        public Json toJson() {
            return content().with("id", id);
        }
    }

    /**
     * What has gone against an order: the yards that count, whether that covers the order inside its
     * tolerance, how far short or how far past the tolerance it is, and the yards sent in grades the
     * order does not take.
     */
    public record Coverage(
            double orderedYards,
            double sentYards,
            boolean covered,
            double shortYards,
            double overYards,
            double uncountedYards) {}

    /** A contract's delivery terms: the tolerance either way in per cent, and the grades it takes. */
    public record Terms(double tolerancePercent, Optional<Set<String>> grades) {
        /** Every grade counts and the quantity is exact. */
        public static final Terms EXACT = new Terms(0, Optional.empty());

        /** Checks the tolerance and copies the grades. */
        public Terms {
            if (!(Check.number(tolerancePercent, "tolerancePercent") >= 0 && tolerancePercent <= 50)) {
                throw new InvalidArgumentException(
                        "a delivery tolerance runs from nothing to fifty per cent",
                        Json.obj("tolerancePercent", tolerancePercent));
            }
            grades = grades.map(Set::copyOf);
        }

        /** The usual terms: three per cent either way, first quality only. */
        public static Terms usual() {
            return new Terms(3, Optional.of(Set.of("first")));
        }
    }

    /** One bale of a packing list: its number, the pieces in it, and their length. */
    public record Bale(int number, List<String> pieces, double yards) {
        /** Copies the pieces. */
        public Bale {
            pieces = List.copyOf(pieces);
        }
    }

    private Despatch() {}

    /**
     * Build a despatch note from its record: a reference, a customer and pieces already in the
     * register, with an optional carrier and the day it was sent.
     */
    public static Note createNote(Object input) {
        JsonObject record = Check.plainObject(input, "note");
        String reference = Check.nonEmptyString(record.get("reference"), "reference");
        String customer = Check.nonEmptyString(record.get("customer"), "customer");
        List<Object> given = Check.array(record.get("pieces"), "pieces", 1);
        List<NotePiece> pieces = new ArrayList<>(given.size());
        for (int index = 0; index < given.size(); index += 1) {
            String at = "pieces[" + index + "]";
            JsonObject piece = Check.plainObject(given.get(index), at);
            String ref = Check.nonEmptyString(piece.get("reference"), at + ".reference");
            double yards = Check.positiveNumber(piece.get("yards"), at + ".yards");
            Json grade = piece.get("grade");
            pieces.add(new NotePiece(ref, yards, grade.isNullish() ? "first" : Text.string(grade)));
        }
        Json sent = record.get("sentOn");
        Optional<String> sentOn = sent.isNullish() ? Optional.empty() : Optional.of(Text.string(sent));
        Json carrierGiven = record.get("carrier");
        String carrier = carrierGiven.isMissing() ? "" : Text.string(carrierGiven);
        double yards = Numeric.roundTo(Numeric.sumBy(pieces, NotePiece::yards, "pieces"), 3);
        Note draft = new Note(reference, customer, carrier, sentOn, pieces, yards, "");
        return new Note(reference, customer, carrier, sentOn, pieces, yards, Ids.contentId("note", draft.content()));
    }

    /** The lengths on a note, split by grade, most first. */
    public static List<JsonObject> noteByGrade(Note note) {
        return yardsBy(note.pieces(), "grade", NotePiece::grade, NotePiece::yards);
    }

    private static <T> List<JsonObject> yardsBy(
            List<T> items, String label, Function<T, String> key, Function<T, Double> yards) {
        return Group.tally(
                items, Group.<T>by(key).label(label).field("yards", yards).sortBy("yards"));
    }

    /** Refuse a note that sends a piece twice, or one already on another note. */
    public static Note assertPiecesFree(Note note, List<Note> otherNotes) {
        Check.array(otherNotes, "otherNotes");
        Set<String> seen = new HashSet<>();
        for (NotePiece piece : note.pieces()) {
            if (!seen.add(piece.reference())) {
                throw new ConflictException(
                        piece.reference() + " is on this note twice", Json.obj("piece", piece.reference()));
            }
        }
        for (Note other : otherNotes) {
            for (NotePiece piece : other.pieces()) {
                if (seen.contains(piece.reference())) {
                    throw new ConflictException(
                            piece.reference() + " is already on " + other.reference(),
                            Json.obj("piece", piece.reference(), "note", other.reference()));
                }
            }
        }
        return note;
    }

    /**
     * Pack a note into bales of at most {@code maxYards}, longest pieces first, each into the first
     * bale with room for it. A piece longer than a bale cannot be packed and is refused.
     */
    public static List<Bale> packingList(Note note, double maxYards) {
        Check.positiveNumber(maxYards, "maxYards");
        List<NotePiece> pieces = new ArrayList<>(note.pieces());
        pieces.sort(Comparator.comparingDouble(NotePiece::yards).reversed());
        List<List<NotePiece>> bales = new ArrayList<>();
        for (NotePiece piece : pieces) {
            if (piece.yards() > maxYards) {
                throw new InvalidArgumentException(
                        piece.reference() + " is longer than a bale holds",
                        Json.obj("piece", piece.reference(), "yards", piece.yards(), "maxYards", maxYards));
            }
            bales.stream()
                    .filter(bale -> Numeric.sumBy(bale, NotePiece::yards, "bale") + piece.yards() <= maxYards)
                    .findFirst()
                    .orElseGet(() -> {
                        bales.add(new ArrayList<>());
                        return bales.get(bales.size() - 1);
                    })
                    .add(piece);
        }
        List<Bale> out = new ArrayList<>(bales.size());
        for (int index = 0; index < bales.size(); index += 1) {
            List<NotePiece> bale = bales.get(index);
            out.add(new Bale(
                    index + 1,
                    bale.stream().map(NotePiece::reference).toList(),
                    Numeric.roundTo(Numeric.sumBy(bale, NotePiece::yards, "bale"), 3)));
        }
        return List.copyOf(out);
    }

    /** Mark a note sent on a day, which it cannot be twice. */
    public static Note sendNote(Note note, String on) {
        Check.nonEmptyString(on, "on");
        if (note.sentOn().isPresent()) {
            throw new ConflictException(
                    note.reference() + " went out on " + note.sentOn().get(),
                    Json.obj(
                            "reference",
                            note.reference(),
                            "sentOn",
                            note.sentOn().get()));
        }
        return createNote(((JsonObject) Json.of(note)).with("sentOn", on));
    }

    /** What is still waiting to go. */
    public static List<Note> pending(List<Note> notes) {
        Check.array(notes, "notes");
        return notes.stream().filter(note -> note.sentOn().isEmpty()).toList();
    }

    /** What has gone, most recent first, then by reference. */
    public static List<Note> sent(List<Note> notes) {
        Check.array(notes, "notes");
        return notes.stream()
                .filter(note -> note.sentOn().isPresent())
                .sorted(Comparator.comparing((Note note) -> note.sentOn().get(), Collation.ORDER.reversed())
                        .thenComparing(Note::reference, Collation.ORDER))
                .toList();
    }

    /** Find a note by reference. */
    public static Note noteByReference(List<Note> notes, String reference) {
        return notes.stream()
                .filter(note -> note.reference().equals(reference))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "no note called \"" + reference + "\"", Json.obj("reference", reference)));
    }

    /** What went out to each customer, most first. */
    public static List<JsonObject> byCustomer(List<Note> notes) {
        Check.array(notes, "notes");
        return yardsBy(notes, "customer", Note::customer, Note::yards);
    }

    /** Whether the notes to a customer cover an order exactly, every grade counting. */
    public static Coverage coversOrder(List<Note> notes, String order, double orderedYards) {
        return coversOrder(notes, order, orderedYards, Terms.EXACT);
    }

    /**
     * Whether the notes to a customer cover what an order asked for on its terms: the yards in the
     * grades it takes are held against the ordered length less the tolerance, and anything past the
     * ordered length plus the tolerance is over.
     */
    public static Coverage coversOrder(List<Note> notes, String order, double orderedYards, Terms terms) {
        Check.positiveNumber(orderedYards, "orderedYards");
        Check.required(terms, "terms");
        List<NotePiece> pieces = notes.stream()
                .filter(note -> note.customer().equals(order))
                .flatMap(note -> note.pieces().stream())
                .toList();
        List<NotePiece> counted = pieces.stream()
                .filter(piece -> terms.grades()
                        .map(grades -> grades.contains(piece.grade()))
                        .orElse(true))
                .toList();
        double sentYards = Numeric.roundTo(Numeric.sumBy(counted, NotePiece::yards, "pieces"), 3);
        double share = terms.tolerancePercent() / 100;
        return new Coverage(
                orderedYards,
                sentYards,
                sentYards >= orderedYards * (1 - share) - 1e-9,
                Numeric.roundTo(Math.max(0, orderedYards - sentYards), 3),
                Numeric.roundTo(Math.max(0, sentYards - orderedYards * (1 + share)), 3),
                Numeric.roundTo(Numeric.sumBy(pieces, NotePiece::yards, "pieces") - sentYards, 3));
    }
}
