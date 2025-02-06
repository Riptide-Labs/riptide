package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.visitor.ValueVisitor;

import java.time.Instant;
import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.uint32;
import static org.riptide.flows.utils.BufferUtils.uint64;

public class DateTimeValue extends Value<Instant> {

    /**
     * Number of seconds between 1900-01-01 and 1970-01-01 according to RFC 868.
     */
    public static final long SECONDS_TO_EPOCH = 2208988800L;

    private final Instant value;

    public DateTimeValue(final String name,
                         final Semantics semantics,
                         final String unit,
                         final Instant value) {
        super(name, semantics, unit);
        this.value = Objects.requireNonNull(value);
    }

    public DateTimeValue(final String name, Instant value) {
        this(name, null, null, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("value", value)
                .toString();
    }

    public static InformationElement parserWithSeconds(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new DateTimeValue(name, semantics, unit, Instant.ofEpochSecond(uint32(buffer)));
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 4;
            }

            @Override
            public int getMaximumFieldLength() {
                return 4;
            }
        };
    }

    public static InformationElement parserWithMilliseconds(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new DateTimeValue(name, semantics, unit, Instant.ofEpochMilli(uint64(buffer).longValue()));
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 8;
            }

            @Override
            public int getMaximumFieldLength() {
                return 8;
            }
        };
    }

    public static InformationElement parserWithMicroseconds(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                final long seconds = uint32(buffer);
                final long fraction = uint32(buffer) & (0xFFFFFFFF << 11);

                final Instant value = Instant.ofEpochSecond(seconds - SECONDS_TO_EPOCH, fraction * 1_000_000_000L / (1L << 32));

                return new DateTimeValue(name, semantics, unit, value);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 8;
            }

            @Override
            public int getMaximumFieldLength() {
                return 8;
            }
        };
    }

    public static InformationElement parserWithNanoseconds(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                final long seconds = uint32(buffer);
                final long fraction = uint32(buffer);

                final Instant value = Instant.ofEpochSecond(seconds - SECONDS_TO_EPOCH, fraction * 1_000_000_000L / (1L << 32));

                return new DateTimeValue(name, semantics, unit, value);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 8;
            }

            @Override
            public int getMaximumFieldLength() {
                return 8;
            }
        };
    }

    @Override
    public Instant getValue() {
        return this.value;
    }

    @Override
    public <X> X accept(ValueVisitor<X> visitor) {
        return Objects.requireNonNull(visitor).visit(this);
    }
}
