package dev.judge.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.judge.api.JudgeApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/** Port 1 refuses connections: the docker client is present and the daemon behind it is not. */
@SpringBootTest(
        classes = JudgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {IntegrationTest.ADMIN_KEY_PROPERTY, "judge.sandbox.docker-host=tcp://127.0.0.1:1"})
class DaemonDownIT extends IntegrationTest {

    @Test
    void containerDown_yieldsInternalError() throws Exception {
        UUID problem = createSumProblem(2000, 256);

        // A correct solution. Reporting anything but an internal error here would be a false accusation.
        JsonNode python = judge(problem, "PYTHON", "a, b = map(int, input().split())\nprint(a + b)\n");
        JsonNode java = judge(problem, "JAVA", "public class Main { public static void main(String[] a) { } }");

        assertThat(python.get("verdict").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(java.get("verdict").asString()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void serviceStaysUpWithoutADaemon() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }
}
