package org.riptide.flows.parser.netflow9.proto;

import static org.riptide.flows.utils.BufferUtils.slice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Field;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.Template;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class DataRecord implements Record {

    /*
     +--------------------------------------------------+
     | Field Value                                      |
     +--------------------------------------------------+
     | Field Value                                      |
     +--------------------------------------------------+
      ...
     +--------------------------------------------------+
     | Field Value                                      |
     +--------------------------------------------------+
    */

    public final DataSet set;  // Enclosing set

    public final Template template;

    public final List<Value<?>> scopes;
    public final List<Value<?>> fields;
    public final List<Value<?>> options;

    public DataRecord(final DataSet set,
                      final Session.Resolver resolver,
                      final Template template,
                      final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
        this.set = Objects.requireNonNull(set);

        this.template = Objects.requireNonNull(template);

        final List<Value<?>> scopes = new ArrayList<>(this.template.scopes.size());
        for (final Field scope : template.scopes) {
            scopes.add(scope.parse(resolver, slice(buffer, scope.length())));
        }

        final List<Value<?>> fields = new ArrayList<>(this.template.fields.size());
        for (final Field field : template.fields) {
            fields.add(field.parse(resolver, slice(buffer, field.length())));
        }

        this.scopes = Collections.unmodifiableList(scopes);
        this.fields = Collections.unmodifiableList(fields);

        // Expand the data record by appending values from
        this.options = resolver.lookupOptions(ScopeFieldSpecifier.buildScopeValues(this));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("scopes", this.scopes)
                .add("fields", this.fields)
                .add("options", this.options)
                .toString();
    }

    public List<Value<?>> getValues() {
        final var list = new ArrayList<>(options);
        list.addAll(fields);
        list.addAll(scopes);
        return list;
    }
}
