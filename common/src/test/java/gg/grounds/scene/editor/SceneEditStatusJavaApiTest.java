package gg.grounds.scene.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SceneEditStatusJavaApiTest {
    @Test
    void exposesASamFriendlyBooleanMethodToJava() {
        SceneEditStatus status = worldId -> worldId.equals(new UUID(1L, 2L));

        assertTrue(status.hasUnsavedChanges(new UUID(1L, 2L)));
        assertFalse(status.hasUnsavedChanges(new UUID(2L, 1L)));
    }
}
