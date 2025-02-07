package org.riptide.configuration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RiptideConfigurationTest {

    @Test
    void verifyNetflow9ConversionService(@Qualifier("netflow9ValueConversionService") ValueConversionService service) {
        Assertions.assertThat(service.targetType).isEqualTo(Netflow9RawFlow.class);
    }

    @Test
    void verifyIpfixConversionService(@Qualifier("ipfixValueConversionService") ValueConversionService service) {
        Assertions.assertThat(service.targetType).isEqualTo(IpfixRawFlow.class);
    }
}