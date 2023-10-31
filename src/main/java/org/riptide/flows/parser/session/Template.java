package org.riptide.flows.parser.session;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.common.base.Preconditions;

public final class Template implements Iterable<Field> {

    public enum Type {
        TEMPLATE,
        OPTIONS_TEMPLATE,
    }

    public final int id; //uint16
    public final Type type;

    public final List<Scope> scopes;
    public final List<Field> fields;
    public final Set<String> scopeNames;

    private Template(final int id,
                     final Type type,
                     final List<Scope> scopes,
                     final List<Field> fields) {
        this.id = id;
        this.type = Objects.requireNonNull(type);
        this.scopes = Objects.requireNonNull(scopes);
        this.fields = Objects.requireNonNull(fields);
        // The set of scope names are used when processing packets - so we build it here once
        // instead of having to re-compute this everytime
        this.scopeNames = scopes.stream().map(Scope::getName).collect(Collectors.toSet());
    }

    public int count() {
        return this.scopes.size() + this.fields.size();
    }

    @Override
    public Iterator<Field> iterator() {
        return this.fields.iterator();
    }

    public Stream<Field> stream() {
        return StreamSupport.stream(this.spliterator(), false);
    }

    public static final class Builder {
        private final int id;
        private final Type type;

        private final List<Scope> scopes = new LinkedList<>();
        private final List<Field> fields = new LinkedList<>();

        private Builder(final int id,
                        final Type type) {
            this.id = id;
            this.type = Objects.requireNonNull(type);
        }

        public Builder withScopes(final List<? extends Scope> scopes) {
            assert this.type == Type.OPTIONS_TEMPLATE;

            this.scopes.addAll(scopes);
            return this;
        }

        public Builder withFields(final List<? extends Field> fields) {
            this.fields.addAll(fields);
            return this;
        }

        public Template build() {
            Preconditions.checkNotNull(this.scopes);
            Preconditions.checkNotNull(this.fields);

            return new Template(this.id, this.type, this.scopes, this.fields);
        }
    }

    public static Builder builder(final int id, final Type type) {
        return new Builder(id, type);
    }
}
