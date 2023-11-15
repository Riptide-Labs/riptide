package org.riptide.dns;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.util.function.ThrowingFunction;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MockDnsServer implements BeforeAllCallback, AfterAllCallback {

    private static final int PKT_SIZE = 512;

    private Thread worker;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final DatagramSocket socket;

    private final ThrowingFunction<Record, List<Record>> callback;

    public MockDnsServer(final ThrowingFunction<Record, List<Record>> callback) {
        try {
            this.socket = new DatagramSocket();
            this.socket.setSoTimeout(100);
        } catch (final SocketException e) {
            throw new RuntimeException(e);
        }

        this.callback = Objects.requireNonNull(callback);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        log.info("Server socket port: {}", this.socket.getLocalPort());

        this.running.set(true);
        this.worker = new Thread(() -> {
            try {
                while (this.running.get()) {
                    final var requestWire = new byte[PKT_SIZE];
                    final var requestPacket = new DatagramPacket(requestWire, requestWire.length);
                    try {
                        socket.receive(requestPacket);
                    } catch (final SocketTimeoutException e) {
                        continue;
                    }

                    final var request = new Message(requestWire);

                    final var response = new Message(request.getHeader().getID());
                    response.getHeader().setFlag(Flags.QR);
                    response.getHeader().setFlag(Flags.AA);

                    response.addRecord(request.getQuestion(), Section.QUESTION);

                    for (Record record : this.callback.apply(request.getQuestion())) {
                        response.addRecord(record, Section.ANSWER);
                    }

                    final var responseWire = response.toWire();
                    final var responsePacket = new DatagramPacket(responseWire, responseWire.length, requestPacket.getAddress(), requestPacket.getPort());

                    log.info("Send response pkt: {}", responsePacket.getSocketAddress());

                    this.socket.send(responsePacket);
                }

            } catch (final IOException e) {
                this.running.set(false);
            }
        });
        this.worker.start();

        Thread.sleep(1000);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        this.running.set(false);
        this.worker.interrupt();
        this.worker.join();
    }

    public int getPort() {
        return this.socket.getLocalPort();
    }
}
