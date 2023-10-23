package org.riptide.pipeline;

import java.net.InetAddress;
import java.util.Objects;

// TODO fooker: Do we need to source by SocketAddr?
public record WithSource<T>(String location, InetAddress source, T value) {
    public WithSource(final String location, final InetAddress source, final T value) {
        this.location = Objects.requireNonNull(location);
        this.source = Objects.requireNonNull(source);
        this.value = Objects.requireNonNull(value);
    }

    public <R> WithSource<R> withValue(final R value) {
        return new WithSource<>(this.location, this.source, value);
    }
}
