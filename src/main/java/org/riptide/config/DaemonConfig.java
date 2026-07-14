/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.riptide.pipeline.Identity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@ConfigurationProperties("riptide")
@NoArgsConstructor
public final class DaemonConfig {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Deprecated flow-placement key. Superseded by {@code riptide.identity.zone}; still
     * bound for one release and mapped to {@code zone} with a warning. Left {@code null}
     * so an explicit {@code riptide.identity.zone} can win over a legacy value.
     *
     * @deprecated use {@code riptide.identity.zone}
     */
    @Deprecated
    @Getter
    @Setter
    private String location;

    @Getter
    @Setter
    private IdentityConfig identity = new IdentityConfig();

    @Getter
    private Map<String, ReceiverConfig> receivers = new HashMap<>();

    public void setReceivers(final Map<String, Map<String, Object>> receivers) {
        this.receivers = receivers.entrySet().stream().map((e) -> Map.entry(
                e.getKey(),
                objectMapper.convertValue(e.getValue(), ReceiverConfig.class)
        )).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Resolve the effective flow identity once at startup. {@code tenant}, {@code
     * organisation} and {@code zone} default to {@code "default"}; {@code zone} also
     * accepts the deprecated {@code riptide.location} (with a warning) when
     * {@code riptide.identity.zone} is not set. {@code system} resolves from config, then
     * the host name, then {@code "default"} — never failing startup.
     */
    public Identity resolveIdentity() {
        final String tenant = orDefault(this.identity.getTenant());
        final String organisation = orDefault(this.identity.getOrganisation());
        return new Identity(tenant, organisation, resolveZone(), resolveSystem());
    }

    private String resolveZone() {
        if (!isBlank(this.identity.getZone())) {
            return this.identity.getZone();
        }
        if (!isBlank(this.location)) {
            log.warn("Config key 'riptide.location' is deprecated; use 'riptide.identity.zone'. "
                    + "Mapping the value '{}' to zone.", this.location);
            return this.location;
        }
        return "default";
    }

    private String resolveSystem() {
        final String configured = this.identity.getSystem();
        if (!isBlank(configured)) {
            return configured;
        }
        final String env = System.getenv("HOSTNAME");
        if (!isBlank(env)) {
            return env;
        }
        try {
            final String host = InetAddress.getLocalHost().getHostName();
            if (!isBlank(host)) {
                return host;
            }
        } catch (final UnknownHostException e) {
            log.debug("Could not resolve local host name for riptide.identity.system", e);
        }
        return "default";
    }

    /** Blank (unset or empty) identity dimensions fall back to their default, uniformly. */
    private static String orDefault(final String value) {
        return isBlank(value) ? "default" : value;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static final class IdentityConfig {
        private String tenant;
        private String organisation;
        private String zone;
        private String system;
    }
}
