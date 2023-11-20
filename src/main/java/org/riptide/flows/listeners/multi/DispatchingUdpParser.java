package org.riptide.flows.listeners.multi;

import io.netty.buffer.ByteBuf;
import org.riptide.flows.listeners.UdpParser;
import org.riptide.flows.parser.Parser;
import org.riptide.flows.utils.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

public class DispatchingUdpParser implements UdpParser {
    private static final Logger LOG = LoggerFactory.getLogger(DispatchingUdpParser.class);

    private final String name;

    private final Set<DispatchableUdpParser> parsers;

    public DispatchingUdpParser(final String name,
                                final DispatchableUdpParser... parsers) {
        this(name, Set.of(parsers));
    }

    public DispatchingUdpParser(final String name,
                                final Set<DispatchableUdpParser> parsers) {
        this.name = Objects.requireNonNull(name);
        this.parsers = Set.copyOf(parsers);
    }

    @Override
    public CompletableFuture<?> parse(final Instant receivedAt,
                                      final ByteBuf buffer,
                                      final InetSocketAddress remoteAddress,
                                      final InetSocketAddress localAddress) throws Exception {
        for (final var parser : this.parsers) {
            if (BufferUtils.peek(buffer, parser::handles)) {
                return parser.parse(receivedAt, buffer, remoteAddress, localAddress);
            }
        }

        LOG.warn("Unhandled packet from {}", remoteAddress);

        return null;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getDescription() {
        return String.format("Dispatcher: %s", this.parsers.stream()
                .map(Parser::getDescription)
                .collect(Collectors.joining(", ", "[", "]")));
    }

    @Override
    public Object dumpInternalState() {
        return this.parsers.stream()
                .collect(Collectors.toMap(Parser::getName, Parser::dumpInternalState));
    }

    @Override
    public void start(ScheduledExecutorService executorService) {
        for (final var parser: this.parsers) {
            parser.start(executorService);
        }
    }

    @Override
    public void stop() {
        for (final var parser: this.parsers) {
            parser.stop();
        }
    }
}
