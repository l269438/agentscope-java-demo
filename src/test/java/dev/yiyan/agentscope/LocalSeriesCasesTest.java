package dev.yiyan.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import dev.yiyan.agentscope.runtime.DemoConfig;
import dev.yiyan.agentscope.runtime.RunTrace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSeriesCasesTest {
    @TempDir Path root;
    @Test void absentOrBatchArgumentsDoNotConstructModels() {
        assertThrows(IllegalStateException.class, () -> LocalSeriesCases.main(new String[0]));
        assertThrows(IllegalStateException.class, () -> LocalSeriesCases.main(new String[]{"all"}));
    }
    @Test void SubagentExecutionIsNotAnAllowedCase() {
        assertThrows(IllegalStateException.class, () -> LocalSeriesCases.main(new String[]{"08"}));
        assertThrows(IllegalStateException.class, () -> LocalSeriesCases.main(new String[]{"08-run"}));
    }
    @Test void missingResumeOrImageArgumentFailsBeforeConfiguration() {
        for (String scenario : new String[]{"04-resume", "05-read", "10"}) {
            assertThrows(IllegalStateException.class, () -> LocalSeriesCases.main(new String[]{scenario}));
        }
    }
    @Test void scenarioMetadataIsExplicitAndOldConstructorPreserves01() throws Exception {
        var config = new DemoConfig("", 0, 8, 120, root);
        var current = new RunTrace(config, "03", "local-series-model-run", Map.of(), Map.of());
        var old = new RunTrace(config, "local-tool-check", Map.of(), Map.of());
        assertTrue(Files.readString(current.directory().resolve("metadata.json")).contains("\"scenario\":\"03\""));
        assertTrue(Files.readString(old.directory().resolve("metadata.json")).contains("\"scenario\":\"01-react-ecommerce\""));
        assertEquals(0, current.modelCalls()); assertEquals(0, old.modelCalls());
        assertNotEquals(current.directory(), old.directory());
    }
}
