package io.github.rkaushikdev.heddlewright.loom;

import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import java.util.List;
import java.util.Optional;

/**
 * What a style asks of a loom: a width, a shedding capacity in shafts or hooks, and a box for every
 * weft colour. A style quoted as a weft pattern carries the pattern too, because the order the
 * colours are picked in decides whether a loom boxed at one end can pick them at all.
 *
 * <p>A figure the style does not state is NaN, which no comparison with a loom's capacity can
 * fail on, so an unstated need never rules a loom out.
 *
 * <p>The width is the cloth's. A cloth pulls in from the reed as it is woven, so the reed space a
 * style needs is its width over one less its {@code pullIn}, and a style that gives no pull-in is
 * taken to need no more reed than its own width.
 */
public record Requirements(
        double widthCm,
        double shafts,
        double hooks,
        double weftColours,
        Optional<List<String>> weftPattern,
        double pullIn) {

    /** Copies the pattern. */
    public Requirements {
        weftPattern = weftPattern.map(List::copyOf);
    }

    /**
     * Read requirements as a run carries them, without the checks {@link Registry#styleRequirements}
     * makes: each figure the record gives as a number is taken, anything else is NaN, and a weft
     * pattern, when there is one, must still name its colours.
     */
    public static Requirements fromRecord(Object input) {
        if (input instanceof Requirements requirements) {
            return requirements;
        }
        JsonObject record = Check.plainObject(input, "requirements");
        Json pattern = record.get("weftPattern");
        return new Requirements(
                figure(record, "widthCm"),
                figure(record, "shafts"),
                figure(record, "hooks"),
                figure(record, "weftColours"),
                pattern.isMissing()
                        ? Optional.empty()
                        : Optional.of(BoxMotion.colours(Check.array(pattern, "weftPattern"))),
                figure(record, "pullIn"));
    }

    /** The reed space the style needs, in centimetres: its width over one less its pull-in. */
    public double reedSpaceCm() {
        return Double.isNaN(pullIn) || pullIn == 0 ? widthCm : Temple.reedWidth(widthCm, pullIn);
    }

    private static double figure(JsonObject record, String field) {
        Json value = record.get(field);
        return value.isNumber() ? value.asDouble() : Double.NaN;
    }
}
