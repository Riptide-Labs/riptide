package org.riptide.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@ConfigurationProperties("riptide")
@NoArgsConstructor
public final class DaemonConfig {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Getter
    @Setter
    private String location = "default";

    @Getter
    private Map<String, ReceiverConfig> receivers = new HashMap<>();

    public void setReceivers(final Map<String, Map<String, Object>> receivers) {
        this.receivers = receivers.entrySet().stream().map((e) -> Map.entry(
                e.getKey(),
                objectMapper.convertValue(e.getValue(), ReceiverConfig.class)
        )).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
