package org.riptide.pipeline;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.json.JsonContent;


class UtilsTest {
    private static final String JSON = Utils.getConvoKeyAsJsonString("SomeLoc", 1, "1.1.1.1", "2.2.2.2", "ulf");

    @Test
    void verifySerialization() {
        final var jsonContent = new JsonContent<>(getClass(), null, JSON);
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("$.location").isEqualTo("SomeLoc");
        Assertions.assertThat(jsonContent).extractingJsonPathValue("$.protocol").isEqualTo(1);
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("$.lowerIp").isEqualTo("1.1.1.1");
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("$.upperIp").isEqualTo("2.2.2.2");
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("$.application").isEqualTo("ulf");
    }

    @Test
    void verifyDeserialization() {
        final var expectedKey = new ConversationKey(
                "SomeLoc",
                1,
                "1.1.1.1",
                "2.2.2.2",
                "ulf");
        final var actualKey = Utils.fromJsonString(JSON);
        Assertions.assertThat(actualKey).isEqualTo(expectedKey);
    }

}