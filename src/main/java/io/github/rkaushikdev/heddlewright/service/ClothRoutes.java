package io.github.rkaushikdev.heddlewright.service;

import io.github.rkaushikdev.heddlewright.cloth.CutPlan;
import io.github.rkaushikdev.heddlewright.cloth.FourPoint;
import io.github.rkaushikdev.heddlewright.cloth.Inspection;
import io.github.rkaushikdev.heddlewright.cloth.Roll;
import io.github.rkaushikdev.heddlewright.service.Router.Response;
import io.github.rkaushikdev.heddlewright.store.Repository;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonArray;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.Text;
import java.util.List;

/**
 * The cloth side of the service.
 *
 * <p>Pieces are stored; scores are not. A grading is worked out from the defect sheet every time it
 * is asked for, so the day the acceptance limit changes, every piece is regraded by the next request
 * rather than by a migration.
 */
public final class ClothRoutes {

    /** The collection pieces are stored in. */
    public static final String PIECES = "pieces";

    /** The collection defect sheets are stored in, one per piece. */
    public static final String DEFECTS = "defects";

    private ClothRoutes() {}

    /** Add the cloth routes to a router. */
    public static Router mountClothRoutes(Router router, Repository repository) {
        Router.route(router, "GET", "/pieces", call -> Repository.list(repository, PIECES));

        // This one comes before /pieces/:reference on purpose. The router takes the first route that
        // fits, and "totals" is a perfectly good piece reference as far as the pattern is concerned.
        Router.route(router, "GET", "/pieces/totals", call -> Roll.registerTotals(Repository.list(repository, PIECES)));

        Router.route(
                router,
                "GET",
                "/pieces/:reference",
                call -> Repository.get(repository, PIECES, call.params().get("reference")));

        Router.route(router, "POST", "/pieces", call -> {
            Check.plainObject(call.body(), "body");
            Roll.Piece piece = Roll.createPiece(call.body());
            Repository.insert(repository, PIECES, piece.reference(), piece);
            return Response.of(201, piece);
        });

        Router.route(router, "POST", "/pieces/:reference/defects", call -> {
            JsonObject body = Check.plainObject(call.body(), "body");
            Json piece = Repository.get(repository, PIECES, call.params().get("reference"));
            Json given = body.get("defects");
            List<Inspection.Defect> sheet =
                    Inspection.recordSheet(Check.array(given.isNullish() ? Json.arr() : given, "entries"));
            Repository.put(repository, DEFECTS, call.params().get("reference"), Json.obj("defects", sheet));
            return Response.of(
                    201,
                    Inspection.inspectionSheet(
                            sheet,
                            Check.number(piece.get("widthInches"), "widthInches"),
                            Check.number(piece.get("yards"), "yards")));
        });

        Router.route(router, "GET", "/pieces/:reference/grade", call -> {
            String reference = call.params().get("reference");
            Json piece = Repository.get(repository, PIECES, reference);
            FourPoint.Score score = FourPoint.scoreRoll(Json.obj(
                    "defects",
                    defectsFor(repository, reference),
                    "widthInches",
                    piece.get("widthInches"),
                    "yards",
                    piece.get("yards")));
            String given = call.query().get("acceptance");
            FourPoint.RollGrade grade = given == null
                    ? FourPoint.gradeRoll(score)
                    : FourPoint.gradeRoll(score, Check.positiveNumber(Text.toNumber(given), "acceptance"));
            return Json.obj("reference", piece.get("reference"), "score", score, "grade", grade);
        });

        Router.route(router, "POST", "/pieces/:reference/cut", call -> {
            JsonObject body = Check.plainObject(call.body(), "body");
            String reference = call.params().get("reference");
            Json piece = Repository.get(repository, PIECES, reference);
            List<Json> yards = defectsFor(repository, reference).stream()
                    .map(defect -> defect.get("through").isMissing()
                            ? defect.get("yard")
                            : Json.obj("yard", defect.get("yard"), "through", defect.get("through")))
                    .toList();
            Json lengths = body.get("lengths");
            return CutPlan.cutRoll(Json.obj(
                    "rollYards",
                    piece.get("yards"),
                    "defectYards",
                    yards,
                    "wantedLengths",
                    lengths.isNullish() ? Json.arr() : lengths,
                    "clearance",
                    body.get("clearance")));
        });

        Router.route(router, "GET", "/cloth/summary", call -> {
            List<JsonObject> pieces = Repository.list(repository, PIECES);
            String woven = call.query().get("woven");
            return Json.obj(
                    "totals",
                    Roll.registerTotals(pieces),
                    "firstQualityShare",
                    pieces.isEmpty() ? 0 : Roll.firstQualityShare(pieces),
                    "reconciliation",
                    woven == null ? Json.NULL : Roll.reconcile(pieces, Text.toNumber(woven)));
        });

        return router;
    }

    /** The defect sheet stored against a piece, or an empty one. */
    public static List<Json> defectsFor(Repository repository, String reference) {
        return Repository.find(repository, DEFECTS, reference)
                .map(stored -> stored.get("defects") instanceof JsonArray sheet ? sheet.items() : List.<Json>of())
                .orElse(List.of());
    }
}
