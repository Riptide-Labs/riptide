/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Data
public abstract sealed class ReceiverConfig {

    /**
     * The values {@code riptide.receivers.<name>.type} accepts, and the configuration each one
     * binds to. This is the dispatch a JSON mapper would previously have driven from a type
     * annotation; {@link DaemonConfig} binds against whichever class is named here, and the sealed
     * hierarchy plus {@link Cases} keeps the consuming side exhaustive.
     */
    private static final Map<String, Class<? extends ReceiverConfig>> TYPES = Map.of(
            "netflow5", Neflow5Config.class,
            "netflow9", Neflow9Config.class,
            "ipfix", IpfixConfig.class,
            "sflow", SflowConfig.class,
            "multi", MultiConfig.class);

    /** The configuration class a {@code type} selects, empty when the value names no receiver. */
    public static Optional<Class<? extends ReceiverConfig>> typeOf(final String type) {
        return type == null
                ? Optional.empty()
                : Optional.ofNullable(TYPES.get(type.trim().toLowerCase(Locale.ROOT)));
    }

    /** The accepted {@code type} values, ordered so an error message reads the same every time. */
    public static Set<String> knownTypes() {
        return new TreeSet<>(TYPES.keySet());
    }

    String type;

    /// Listening port
    int port;

    ///  Listening host
    String host;

    public abstract <T> T accept(Cases<T> cases);

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static final class Neflow5Config extends ReceiverConfig {
        /**
         * NetFlow v5 has no options-template mechanism to advertise a sampling rate, so unlike v9
         * and IPFIX this is the only way an operator can state one. Accepting the property here
         * also stops a dedicated v5 receiver failing startup on a setting its siblings take.
         */
        Long flowSamplingIntervalFallback = null;

        /**
         * Whether a header stating algorithm 0 alongside a non-zero interval is read as a rate.
         *
         * <p>Governs that case only. A header stating algorithm 1 or 2 is unambiguous and is always
         * read, so disabling this does not let a configured fallback override an exporter that
         * signalled its mode properly.
         */
        boolean trustHeaderSamplingInterval = true;

        @Override
        public <T> T accept(final Cases<T> cases) {
            return cases.match(this);
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static final class Neflow9Config extends ReceiverConfig {
        Duration flowActiveTimeoutFallback = null;
        Duration flowInactiveTimeoutFallback = null;
        Long flowSamplingIntervalFallback = null;

        @Override
        public <T> T accept(final Cases<T> cases) {
            return cases.match(this);
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static final class IpfixConfig extends ReceiverConfig {
        public enum Transport {
            UDP,
            TCP,
        }

        Transport transport = Transport.UDP;

        Duration flowActiveTimeoutFallback = null;
        Duration flowInactiveTimeoutFallback = null;
        Long flowSamplingIntervalFallback = null;

        @Override
        public <T> T accept(final Cases<T> cases) {
            return cases.match(this);
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static final class SflowConfig extends ReceiverConfig {

        @Override
        public <T> T accept(final Cases<T> cases) {
            return cases.match(this);
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static final class MultiConfig extends ReceiverConfig {
        boolean netflow5 = true;
        boolean netflow9 = true;
        boolean ipfix = true;
        boolean sflow = true;

        Duration flowActiveTimeoutFallback = null;
        Duration flowInactiveTimeoutFallback = null;
        Long flowSamplingIntervalFallback = null;

        /** NetFlow v5 only; see {@link Neflow5Config#trustHeaderSamplingInterval}. */
        boolean trustHeaderSamplingInterval = true;

        @Override
        public <T> T accept(final Cases<T> cases) {
            return cases.match(this);
        }
    }

    public interface Cases<R> {
        R match(Neflow5Config config);

        R match(Neflow9Config config);

        R match(IpfixConfig config);

        R match(SflowConfig config);

        R match(MultiConfig config);
    }
}
