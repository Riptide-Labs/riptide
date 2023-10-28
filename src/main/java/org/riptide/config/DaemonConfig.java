package org.riptide.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("riptide.daemon")
public record DaemonConfig(int port) {

}
