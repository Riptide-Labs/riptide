/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.net.InetAddresses;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The dead-letter table's {@code payload} column: one {@link EnrichedFlow} as JSON, and the only
 * thing that reads it back (#548).
 *
 * <p><b>Why this exists as its own format at all.</b> Nothing in riptide serialised an
 * {@code EnrichedFlow} before, so a dead letter needed one to be chosen. It is not the
 * {@code ClickhouseFlow} the insert path builds, deliberately: that shape exists to satisfy the
 * {@code flows} table, and the whole point of a dead letter is to hold a row that table refused.
 *
 * <p><b>Round-tripping is the contract, not a nicety.</b> A payload that cannot be read back is not
 * a dead letter, it is a slower drop — so {@code DeadLetterPayloadTest} asserts equality over a flow
 * with every field populated, and asserts that <em>every</em> field is populated before it starts,
 * so a field added to {@link EnrichedFlow} cannot join the payload untested.
 *
 * <p><b>Three types Jackson cannot do on its own here</b>, handled explicitly rather than by adding
 * {@code jackson-datatype-jsr310}:
 * <ul>
 *   <li>{@link Instant} and {@link Duration} — written as their ISO-8601 {@code toString()} and read
 *       with {@code parse}. Without a module Jackson would render an {@code Instant} as
 *       {@code {"epochSecond":…,"nano":…}} and then fail to read it back, which the round-trip test
 *       would catch, but as a build failure rather than as a decision.</li>
 *   <li>{@link InetAddress} — written as the numeric literal and read with Guava's
 *       {@code InetAddresses.forString}, which <em>never resolves</em>. Jackson's own built-in
 *       deserialiser calls {@code InetAddress.getByName}, and reading a stored payload must not put
 *       a DNS lookup — for a value some exporter supplied — on the path of an operator's SELECT.</li>
 *   <li>{@link Flow.SamplingProvenance} — written as its {@code token()}, not its constant name. The
 *       enum's own javadoc makes the token the stable identifier precisely because renaming a
 *       constant must not rewrite what stored rows mean, and a payload under a TTL is a stored row.
 *       The other four enums have no token, so their constant name <em>is</em> their stable
 *       identifier (the insert path's {@code Enum8} mapping switches on it), and Jackson's default
 *       name-based handling is already that.</li>
 * </ul>
 *
 * <p><b>Construction goes through the Lombok builder</b> via a mix-in rather than an annotation on
 * {@link EnrichedFlow}: {@code @Data} + {@code @Builder} leaves that class with only a
 * package-private all-args constructor, so field-based binding cannot work. Keeping the wiring here
 * keeps the dead-letter format's decisions in the dead-letter format's file.
 *
 * <p>Unknown properties are ignored on read, which is what lets {@code getDscp()}/{@code getEcn()} —
 * derived getters with no field behind them — be written without being demanded back. It also means
 * a payload written by a newer riptide is readable by an older one, minus what it does not know.
 */
public final class DeadLetterPayload {

    private DeadLetterPayload() {
    }

    /** One flow as the JSON stored in the {@code payload} column. */
    public static String serialise(final EnrichedFlow flow) throws IOException {
        return MAPPER.writeValueAsString(flow);
    }

    /** The flow a stored {@code payload} came from. */
    public static EnrichedFlow deserialise(final String payload) throws IOException {
        return MAPPER.readValue(payload, EnrichedFlow.class);
    }

    /**
     * A whole refused batch as {@code JSONEachRow} for {@code flows_dead_letter}: one line per flow,
     * each carrying its own {@code tenant}.
     *
     * <p>Streamed as bytes rather than rendered into an {@code INSERT … VALUES} statement, because a
     * flusher batch is up to {@code max-rows} (10,000) flows and the statement form would be refused
     * for exceeding {@code max_query_size} long before it was refused for anything about the rows.
     *
     * <p>{@code failedAt} is written in ClickHouse's own {@code 'yyyy-MM-dd HH:mm:ss.SSS'} form
     * against a UTC clock, which is what {@code date_time_input_format = 'basic'} — the shipped
     * default — parses. An ISO-8601 instant with its {@code T} and {@code Z} is <em>not</em> accepted
     * by that setting, so the column's own {@code toString()} is not usable here.
     *
     * <p>A flow with no {@code tenant} writes {@code ''}, which is the {@code String} column's own
     * default and matches no tenant's row policy: such a row is visible to an admin and to nobody
     * else, which is the right answer for a row whose owner cannot be named.
     */
    public static byte[] jsonEachRow(final List<EnrichedFlow> flows, final Instant failedAt,
            final Throwable cause) throws IOException {
        final String when = FAILED_AT.format(failedAt.atOffset(ZoneOffset.UTC));
        final String error = cause == null ? "" : cause.toString();
        final var body = new ByteArrayOutputStream();
        for (final EnrichedFlow flow : flows) {
            final Map<String, String> row = new LinkedHashMap<>();
            row.put("tenant", flow.getTenant() == null ? "" : flow.getTenant());
            row.put("failedAt", when);
            row.put("error", error);
            row.put("payload", serialise(flow));
            // Written through the mapper, so every value — the payload's own JSON included — is
            // escaped by the thing that produced it rather than by a second, hand-rolled rule.
            body.write(MAPPER.writeValueAsBytes(row));
            body.write('\n');
        }
        return body.toByteArray();
    }

    /**
     * {@code DateTime64(3, 'UTC')} as {@code date_time_input_format = 'basic'} reads it.
     *
     * <p>Millisecond precision, matching the column's declared scale: more digits would be truncated
     * silently, fewer would read as a coarser instant than the column can hold.</p>
     */
    private static final DateTimeFormatter FAILED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /**
     * The mapper this format is, built once.
     *
     * <p>{@code WRITE_DATES_AS_TIMESTAMPS} is irrelevant here — every temporal type has an explicit
     * serialiser below — and {@code FAIL_ON_EMPTY_BEANS} is left on: a type that reached this mapper
     * with no properties would silently become {@code {}} otherwise, which is a field that vanished
     * from the payload without failing anything.
     */
    private static ObjectMapper mapper() {
        final SimpleModule module = new SimpleModule("riptide-dead-letter");
        module.addSerializer(Instant.class, toStringSerializer());
        module.addDeserializer(Instant.class, fromString(Instant::parse));
        module.addSerializer(Duration.class, toStringSerializer());
        module.addDeserializer(Duration.class, fromString(Duration::parse));
        module.addSerializer(InetAddress.class, new JsonSerializer<InetAddress>() {
            @Override
            public void serialize(final InetAddress value, final JsonGenerator json,
                    final SerializerProvider provider) throws IOException {
                // getHostAddress(), not toString(): the latter renders "<host>/<literal>" and would
                // put whatever hostname happened to be cached into the stored row.
                json.writeString(value.getHostAddress());
            }
        });
        module.addDeserializer(InetAddress.class, fromString(InetAddresses::forString));
        module.addSerializer(Flow.SamplingProvenance.class,
                new JsonSerializer<Flow.SamplingProvenance>() {
                    @Override
                    public void serialize(final Flow.SamplingProvenance value, final JsonGenerator json,
                            final SerializerProvider provider) throws IOException {
                        json.writeString(value.token());
                    }
                });
        module.addDeserializer(Flow.SamplingProvenance.class, fromString(PROVENANCE_BY_TOKEN::get));
        return new ObjectMapper()
                .registerModule(module)
                .addMixIn(EnrichedFlow.class, BuiltFlow.class)
                .addMixIn(EnrichedFlow.EnrichedFlowBuilder.class, LombokBuilder.class)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Every provenance rung by its stored token.
     *
     * <p>Built from the enum rather than spelled out, so a rung added there is readable here without
     * a second edit — the failure mode otherwise being a payload that writes a token nothing maps
     * back, which is a null field rather than an error.</p>
     */
    private static final Map<String, Flow.SamplingProvenance> PROVENANCE_BY_TOKEN =
            Arrays.stream(Flow.SamplingProvenance.values())
                    .collect(Collectors.toUnmodifiableMap(Flow.SamplingProvenance::token,
                            Function.identity()));

    /**
     * The one mapper, built once.
     *
     * <p>Declared <em>after</em> every static field {@link #mapper()} reads, and it has to be:
     * static initialisers run in textual order, so declaring this first left
     * {@link #PROVENANCE_BY_TOKEN} null at the moment the bound method reference was taken and every
     * call failed with {@code ExceptionInInitializerError} — a class that cannot load at all rather
     * than a codec that mis-serialises one field.</p>
     */
    private static final ObjectMapper MAPPER = mapper();

    private static <T> JsonSerializer<T> toStringSerializer() {
        return new JsonSerializer<T>() {
            @Override
            public void serialize(final T value, final JsonGenerator json,
                    final SerializerProvider provider) throws IOException {
                json.writeString(value.toString());
            }
        };
    }

    private static <T> JsonDeserializer<T> fromString(final Function<String, T> parse) {
        return new JsonDeserializer<T>() {
            @Override
            public T deserialize(final JsonParser json, final DeserializationContext context)
                    throws IOException {
                return parse.apply(json.getValueAsString());
            }
        };
    }

    /** Binds {@link EnrichedFlow} to its Lombok builder; see the class javadoc for why. */
    @JsonDeserialize(builder = EnrichedFlow.EnrichedFlowBuilder.class)
    private abstract static class BuiltFlow {
    }

    /** Lombok's builder setters carry no {@code with} prefix. */
    @JsonPOJOBuilder(withPrefix = "")
    private abstract static class LombokBuilder {
    }
}
