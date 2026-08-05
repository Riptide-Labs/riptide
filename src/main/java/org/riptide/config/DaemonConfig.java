/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.riptide.pipeline.Identity;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@ConfigurationProperties("riptide")
@NoArgsConstructor
public final class DaemonConfig {

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
                bindReceiver(e.getKey(), e.getValue())
        )).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Bind one receiver's properties onto the configuration class its {@code type} names.
     *
     * <p>Bound with Spring's {@link Binder} rather than a JSON mapper so a receiver accepts what
     * every other riptide property accepts: {@code flow-active-timeout-fallback} and
     * {@code flowActiveTimeoutFallback} both bind, and a duration may be written {@code 5m} as well
     * as {@code PT5M}. A JSON mapper handles neither, which left the {@code Duration} fallbacks
     * unreachable by any spelling and the rest camelCase-only (#434). The type dispatch a mapper
     * would drive from an annotation is {@link ReceiverConfig#typeOf}.
     */
    private static ReceiverConfig bindReceiver(final String name, final Map<String, Object> properties) {
        final Binder binder = new Binder(new MapConfigurationPropertySource(
                flatten(properties != null ? properties : Map.of())));
        final String type = binder.bind("type", String.class).orElse(null);
        final Class<? extends ReceiverConfig> target = ReceiverConfig.typeOf(type)
                .orElseThrow(() -> new IllegalArgumentException(
                        "riptide.receivers." + name + ".type "
                                + (type == null || type.isBlank() ? "is not set" : "is '" + type + "'")
                                + "; expected one of " + ReceiverConfig.knownTypes()));
        // NoUnboundElementsBindHandler keeps the one useful thing the JSON mapper did: reject a
        // property that matches no field. Without it a typo binds nothing and says nothing, and a
        // misspelled `port` leaves the primitive at 0, which the listener happily binds as an
        // ephemeral port — a receiver that looks healthy and never sees the exporter's traffic.
        return binder.bind(ConfigurationPropertyName.EMPTY, Bindable.of(target),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                .orElseThrow(() -> new IllegalStateException(
                        "riptide.receivers." + name + " could not be bound to " + target.getSimpleName()));
    }

    /**
     * Collapse the nested maps relaxed binding leaves behind back into one property name per value.
     *
     * <p>Spring hands this setter a {@code Map<String, Object>} per receiver, and how a name was
     * split depends on where it came from: {@code RIPTIDE_RECEIVERS_NF9_FLOW_ACTIVE_TIMEOUT_FALLBACK}
     * arrives as {@code {flow={active={timeout={fallback=5m}}}}}, while the same setting written in
     * a properties file arrives whole. Rejoining the path with hyphens yields the canonical
     * {@code flow-active-timeout-fallback} either way. Every receiver property is a scalar, so a
     * nested map here is always a split name rather than structure worth preserving.
     */
    private static Map<String, Object> flatten(final Map<String, Object> properties) {
        final Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", properties, flat);
        return flat;
    }

    private static void flatten(final String prefix, final Map<?, ?> properties, final Map<String, Object> flat) {
        properties.forEach((key, value) -> {
            final String name = prefix.isEmpty() ? String.valueOf(key) : prefix + "-" + key;
            if (value instanceof Map<?, ?> nested) {
                flatten(name, nested, flat);
            } else {
                flat.put(name, value);
            }
        });
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
