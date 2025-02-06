package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.visitor.TheVisitor;

import java.util.Objects;

public class NullValue extends Value<Void> {
    public NullValue(final String name,
                     final Semantics semantics) {
        super(name, semantics);
    }

    public NullValue(final String name) {
        this(name, null);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("value", "(null)")
                .toString();
    }

    public static InformationElement parser(final String name, final Semantics semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver,
                                  final ByteBuf buffer) {
                return new NullValue(name, semantics);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 0;
            }

            @Override
            public int getMaximumFieldLength() {
                return 0;
            }
        };
    }

    @Override
    public Void getValue() {
        return null;
    }

    @Override
    public <X> X accept(TheVisitor<X> visitor) {
        return Objects.requireNonNull(visitor).visit(this);
    }
}
