package org.riptide.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.xbill.DNS.dnssec.R;

import java.time.Duration;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReceiverConfig.Neflow5Config.class, name = "netflow5"),
        @JsonSubTypes.Type(value = ReceiverConfig.Neflow9Config.class, name = "netflow9"),
        @JsonSubTypes.Type(value = ReceiverConfig.IpfixConfig.class, name = "ipfix"),
        @JsonSubTypes.Type(value = ReceiverConfig.MultiConfig.class, name = "multi"),
})
@Data
public abstract sealed class ReceiverConfig {
    String type;

    /// Listening port
    int port;

    ///  Listening host
    String host;

    public abstract <T> T accept(Cases<T> cases);

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static final class Neflow5Config extends ReceiverConfig {

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
    public static final class MultiConfig extends ReceiverConfig {
        boolean netflow5 = true;
        boolean netflow9 = true;
        boolean ipfix = true;

        Duration flowActiveTimeoutFallback = null;
        Duration flowInactiveTimeoutFallback = null;
        Long flowSamplingIntervalFallback = null;

        @Override
        public <T> T accept(final Cases<T> cases) {
            return cases.match(this);
        }
    }

    public interface Cases<R> {
        R match(Neflow5Config config);

        R match(Neflow9Config config);

        R match(IpfixConfig config);

        R match(MultiConfig config);
    }
}
