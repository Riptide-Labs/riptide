package org.riptide.flows.parser.ipfix.proto;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Field;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.Template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.slice;
import static org.riptide.flows.utils.BufferUtils.uint16;
import static org.riptide.flows.utils.BufferUtils.uint8;

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

    /*
      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     | Length (< 255)|          Information Field                  |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                      ... continuing as needed                 |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      255      |      Length (0 to 65535)      |       IE      |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                      ... continuing as needed                 |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */

    public static final int VARIABLE_SIZED = 0xFFFF;
    public static final int VARIABLE_SIZED_EXTENDED = 0xFF;

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
        for (final Field scope : this.template.scopes) {
            scopes.add(parseField(scope, resolver, buffer));
        }

        final List<Value<?>> fields = new ArrayList<>(this.template.fields.size());
        for (final Field field : this.template.fields) {
            fields.add(parseField(field, resolver, buffer));
        }

        this.scopes = Collections.unmodifiableList(scopes);
        this.fields = Collections.unmodifiableList(fields);

        // Expand the data record by appending values from
        // TODO fooker: extend fields with packet metadata
        //   At a minimum, Collecting Processes SHOULD support as scope the
        //   observationDomainId, exportingProcessId, meteringProcessId,
        //   templateId, lineCardId, exporterIPv4Address, exporterIPv6Address,
        //   and ingressInterface Information Elements.
        this.options = resolver.lookupOptions(this.fields);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("scopes", this.scopes)
                .add("fields", this.fields)
                .add("options", this.options)
                .toString();
    }

    public static Value<?> parseField(final Field field,
                                      final Session.Resolver resolver,
                                      final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
        int length = field.length();
        if (length == VARIABLE_SIZED) {
            length = uint8(buffer);
            if (length == VARIABLE_SIZED_EXTENDED) {
                length = uint16(buffer);
            }
        }

        return field.parse(resolver, slice(buffer, length));
    }

    public List<Value<?>> getValues() {
        final var list = new ArrayList<>(options);
        list.addAll(fields);
        list.addAll(scopes);
        return list;
    }
}
