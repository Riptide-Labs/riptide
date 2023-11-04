package org.riptide.repository.elastic.bulk;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class BulkUtilsTest {

    @Test
    void verifyExceptionParsing() {
        // Parse Error
        final String error = "{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse [timestamp]\",\"caused_by\":{\"type\":\"number_format_exception\",\"reason\":\"For input string: \\\"XXX\\\"\"}}";
        final Exception exception = BulkUtils.convertToException(error);

        // verify exception
        Assertions.assertThat(exception.getMessage()).isEqualTo("mapper_parsing_exception: failed to parse [timestamp]");
        Assertions.assertThat(exception.getCause()).isNotNull();
        Assertions.assertThat(exception.getCause().getMessage()).isEqualTo("number_format_exception: For input string: \"XXX\"");
        Assertions.assertThat(exception.getCause().getCause()).isNull();
    }

    @Test
    void verifyErrorParsing() {
        final var errorString = "{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse [timestamp]\",\"caused_by\":{\"type\":\"number_format_exception\",\"reason\":\"For input string: \\\"XXX\\\"\"}}";
        final var dto = BulkUtils.parse(errorString);
        Assertions.assertThat(dto.getType()).isEqualTo("mapper_parsing_exception");
        Assertions.assertThat(dto.getReason()).isEqualTo("failed to parse [timestamp]");
        Assertions.assertThat(dto.getCause()).isNotNull();
        Assertions.assertThat(dto.getCause().getType()).isEqualTo("number_format_exception");
        Assertions.assertThat(dto.getCause().getReason()).isEqualTo("For input string: \"XXX\"");
        Assertions.assertThat(dto.getCause().getCause()).isNull();
    }

}