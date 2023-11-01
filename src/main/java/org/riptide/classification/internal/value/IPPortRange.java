package org.riptide.classification.internal.value;

import lombok.ToString;

import java.util.Objects;

@ToString
public class IPPortRange {
    private final int begin;
    private final int end;

    public IPPortRange(int begin, int end) {
        if (begin < 0 || end > 65535 || begin > end) throw new IllegalArgumentException("invalid port range - begin: " + begin + "; end: " + end);
        this.begin = begin;
        this.end = end;
    }

    public IPPortRange(int port) {
        this(port, port);
    }

    public int getBegin() {
        return begin;
    }

    public int getEnd() {
        return end;
    }

    public boolean contains(int port) {
        return begin <= port && end >= port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        IPPortRange that = (IPPortRange) o;
        return begin == that.begin && end == that.end;
    }

    @Override
    public int hashCode() {
        return Objects.hash(begin, end);
    }
}
