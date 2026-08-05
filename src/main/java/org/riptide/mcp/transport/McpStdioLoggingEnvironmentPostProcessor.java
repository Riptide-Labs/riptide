/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.transport;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Points console logging at stderr when the MCP stdio transport is configured, so log output cannot
 * corrupt the JSON-RPC frames on stdout.
 *
 * <p>This has to run here rather than in the transport itself. Logback's {@code ConsoleAppender}
 * caches its output stream when the appender starts, so a {@code System.setOut} from application
 * code never reaches it, and Spring's own startup lines are written before any
 * {@code CommandLineRunner} executes. An {@link EnvironmentPostProcessor} is the last hook before
 * the logging system initializes: the property it sets is read by {@code logback-spring.xml} when
 * the appender is built, which is early enough to catch every line.
 */
public class McpStdioLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String CONSOLE_TARGET_PROPERTY = "riptide.logging.console-target";

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment, final SpringApplication application) {
        if (environment.containsProperty(CONSOLE_TARGET_PROPERTY)) {
            // An explicit setting wins: the operator may have redirected the console already.
            return;
        }
        if (!environment.getProperty("riptide.mcp.enabled", Boolean.class, false)) {
            return;
        }
        if (!"stdio".equalsIgnoreCase(environment.getProperty("riptide.mcp.transport", "stdio"))) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                "riptideMcpStdioLogging", Map.of(CONSOLE_TARGET_PROPERTY, "System.err")));
    }

    @Override
    public int getOrder() {
        // After config data (application.properties / config.yaml) is loaded, so transport settings
        // from a file are seen, and still before the logging system initializes.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
