package dev.extraction.core.rules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShapeTest {
    @Test
    void rotatesOnlyOccupiedCellsWithinTheOriginalCanvas() {
        Shape hook = Shape.of(List.of("  X  ", "XXXXX"));
        assertEquals(List.of("X ", "X ", "XX", "X ", "X "), hook.rotate(1).rows());
        assertEquals(List.of("XXXXX", "  X  "), hook.rotate(2).rows());
        assertEquals(List.of(" X", " X", "XX", " X", " X"), hook.rotate(3).rows());
        assertEquals(hook, hook.rotate(1).rotate(3));
        assertFalse(Grid.overlaps(0, 0, hook, 0, 0, Shape.SINGLE));
        assertTrue(Grid.overlaps(0, 0, hook, 2, 0, Shape.SINGLE));
    }

    @Test
    void validatesResourceFieldsAndKeepsTheStoredLayout() {
        JsonObject example = JsonParser.parseString("""
            {"sections":[{"id":"main","shape":[" X","XX"]}],"access":{"routes":[{"steps":[{"type":"rolling_lock","positions":3}]}]}}
            """).getAsJsonObject();
        RuleBook.Storage storage = RuleBook.restoreStorage(example, false);
        example.getAsJsonArray("sections").remove(0);
        assertEquals(List.of(" X", "XX"), storage.sections().getFirst().shape().rows());
        assertEquals(1, storage.definition().getAsJsonArray("sections").size());
        JsonObject invalid = storage.definition();
        invalid.getAsJsonObject("access").getAsJsonArray("routes").get(0).getAsJsonObject()
            .getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("positions", 2);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> RuleBook.restoreStorage(invalid, false));
        assertTrue(error.getMessage().contains(".access.routes[0].steps[0].positions"));
    }

    @Test
    void rejectsMalformedShapesAndRetainsPadding() {
        assertThrows(IllegalArgumentException.class, () -> Shape.of(List.of("  ")));
        assertThrows(IllegalArgumentException.class, () -> Shape.of(List.of("X ", "X")));
        assertThrows(IllegalArgumentException.class, () -> Shape.of(List.of("x")));
        assertEquals(List.of("  X "), Shape.of(List.of("  X ")).rows());
    }
}
