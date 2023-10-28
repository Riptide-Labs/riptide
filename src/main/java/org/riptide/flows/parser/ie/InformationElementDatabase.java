package org.riptide.flows.parser.ie;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.ie.values.NullValue;

import com.google.common.collect.ImmutableMap;

public class InformationElementDatabase {
    public static class Key {
        private final Protocol protocol;
        private final Long enterpriseNumber;
        private final Integer informationElementIdentifier;

        public Key(final Protocol protocol,
                   final Long enterpriseNumber,
                   final Integer informationElementNumber) {
            this.protocol = Objects.requireNonNull(protocol);
            this.enterpriseNumber = enterpriseNumber;
            this.informationElementIdentifier = Objects.requireNonNull(informationElementNumber);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Key that = (Key) o;
            return Objects.equals(this.protocol, that.protocol) &&
                    Objects.equals(this.enterpriseNumber, that.enterpriseNumber) &&
                    Objects.equals(this.informationElementIdentifier, that.informationElementIdentifier);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.protocol, this.enterpriseNumber, this.informationElementIdentifier);
        }
    }

    @FunctionalInterface
    public interface ValueParserFactory {
        InformationElement parser(final String name, final Semantics semantics);
    }

    public interface Adder {
        void add(final InformationElementDatabase.Key key, final InformationElement element);

        default void add(final Protocol protocol,
                         final Long enterpriseNumber,
                         final int informationElementNumber,
                         final ValueParserFactory parserFactory,
                         final String name,
                         final Semantics semantics) {
            this.add(new InformationElementDatabase.Key(protocol, enterpriseNumber, informationElementNumber), parserFactory.parser(name, semantics));
        }

        default void add(final Protocol protocol,
                         final int informationElementNumber,
                         final ValueParserFactory parserFactory,
                         final String name,
                         final Semantics semantics) {
            this.add(protocol, null, informationElementNumber, parserFactory, name, semantics);
        }
    }

    public interface Provider {
        void load(final Adder adder);
    }

    public static final InformationElementDatabase instance = new InformationElementDatabase(
            new org.riptide.flows.parser.ipfix.InformationElementProvider(),
            new org.riptide.flows.parser.netflow9.InformationElementProvider());

    private final Map<Key, InformationElement> elements;

    private InformationElementDatabase(final Provider... providers) {
        final AdderImpl adder = new AdderImpl();

        // Add null element - this derives from the standard but is required by some exporters
        adder.add(Protocol.NETFLOW9, 0, NullValue::parser, "null", null);
        adder.add(Protocol.IPFIX, 0, NullValue::parser, "null", null);

        // Load providers
        for (final Provider provider : providers) {
            provider.load(adder);
        }

        this.elements = adder.build();
    }

    public Optional<InformationElement> lookup(final Protocol protocol, final Long enterpriseNumber, final int informationElementIdentifier) {
        return Optional.ofNullable(this.elements.get(new Key(protocol, enterpriseNumber, informationElementIdentifier)));
    }

    public Optional<InformationElement> lookup(final Protocol protocol, final int informationElementIdentifier) {
        return lookup(protocol, null, informationElementIdentifier);
    }

    private static class AdderImpl implements Adder {
        private final ImmutableMap.Builder<Key, InformationElement> builder = ImmutableMap.builder();

        @Override
        public void add(final Key key, final InformationElement element) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(element);

            builder.put(key, element);
        }

        public Map<Key, InformationElement> build() {
            return this.builder.build();
        }
    }
}
