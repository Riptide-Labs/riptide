/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.session.OptionListener.Verdict;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.snmp.SnmpOptionsConfig;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExporterApplicationTableTest {

    private static final long HTTP = 0x03000050L;

    private final MetricRegistry metrics = new MetricRegistry();
    private final ExporterApplicationTable table =
            new ExporterApplicationTable(config(60_000), new SessionAdmissionConfig(), this.metrics);

    private static SnmpOptionsConfig config(final long retentionMs) {
        final SnmpOptionsConfig config = new SnmpOptionsConfig();
        config.setRetentionMs(retentionMs);
        return config;
    }

    private static SessionAdmissionConfig admission(final int perScope) {
        final SessionAdmissionConfig config = new SessionAdmissionConfig();
        config.setMaxIfIndexesPerScope(perScope);
        return config;
    }

    private static ExporterIdentity identity(final String host, final long domain) throws UnknownHostException {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(host), domain);
    }

    /** The c8000v shape: applicationId in the scope, name and description as fields. */
    @Test
    void aCiscoApplicationTableRowIsClaimed() throws Exception {
        final var identity = identity("10.10.3.1", 6);

        final Verdict verdict = this.table.accept(identity,
                List.of(new UnsignedValue("applicationId", HTTP)),
                List.of(new StringValue("applicationName", "http\0\0\0\0"),
                        new StringValue("applicationDescription", "World Wide Web traffic\0")));

        assertThat(verdict).isEqualTo(Verdict.CLAIMED);
        assertThat(this.table.lookup(identity, HTTP))
                .contains(new ApplicationInfo("http", "World Wide Web traffic"));
        assertThat(this.metrics.meter("enrichment.optionApplications.consumed").getCount()).isEqualTo(1);
    }

    @Test
    void anIdCarriedAsAFieldRatherThanAScopeIsAlsoClaimed() throws Exception {
        final var identity = identity("10.10.3.1", 6);

        this.table.accept(identity, List.of(),
                List.of(new UnsignedValue("applicationId", HTTP), new StringValue("applicationName", "http")));

        assertThat(this.table.lookup(identity, HTTP)).map(ApplicationInfo::name).contains("http");
    }

    @Test
    void aRecordWithoutNameOrDescriptionIsNotThisTablesShape() throws Exception {
        final Verdict verdict = this.table.accept(identity("10.10.3.1", 6),
                List.of(new UnsignedValue("ingressInterface", 2)),
                List.of(new StringValue("interfaceName", "Gi2")));

        assertThat(verdict).isEqualTo(Verdict.UNRECOGNISED);
    }

    @Test
    void aNamedRowWithNoUsableIdIsRecognisedButUnusable() throws Exception {
        final Verdict verdict = this.table.accept(identity("10.10.3.1", 6),
                List.of(),
                List.of(new StringValue("applicationName", "http")));

        assertThat(verdict).isEqualTo(Verdict.RECOGNISED_BUT_UNUSABLE);
        assertThat(this.metrics.meter("enrichment.optionApplications.skipped").getCount()).isEqualTo(1);
    }

    @Test
    void anIdOfZeroIsNotUsable() throws Exception {
        final Verdict verdict = this.table.accept(identity("10.10.3.1", 6),
                List.of(new UnsignedValue("applicationId", 0)),
                List.of(new StringValue("applicationName", "nothing")));

        assertThat(verdict).isEqualTo(Verdict.RECOGNISED_BUT_UNUSABLE);
    }

    /** The c8000v sends tables under domain 6 and flows under domain 256. */
    @Test
    void lookupFallsBackFromTheExactIdentityToTheDeviceAddress() throws Exception {
        this.table.accept(identity("10.10.3.1", 6),
                List.of(new UnsignedValue("applicationId", HTTP)),
                List.of(new StringValue("applicationName", "http")));

        assertThat(this.table.lookup(identity("10.10.3.1", 256), HTTP)).map(ApplicationInfo::name)
                .as("the flow's domain has no table of its own; the device's does")
                .contains("http");
        assertThat(this.table.lookup(identity("10.10.3.2", 256), HTTP))
                .as("another device's table is never consulted")
                .isEmpty();
    }

    @Test
    void anExactIdentityWinsOverAnotherDomainOfTheSameDevice() throws Exception {
        this.table.accept(identity("10.10.3.1", 6),
                List.of(new UnsignedValue("applicationId", HTTP)),
                List.of(new StringValue("applicationName", "from-domain-6")));
        this.table.accept(identity("10.10.3.1", 256),
                List.of(new UnsignedValue("applicationId", HTTP)),
                List.of(new StringValue("applicationName", "from-domain-256")));

        assertThat(this.table.lookup(identity("10.10.3.1", 256), HTTP)).map(ApplicationInfo::name)
                .contains("from-domain-256");
    }

    @Test
    void aSprayWithinOneScopeIsBoundedAndCounted() throws Exception {
        final var bounded = new ExporterApplicationTable(config(60_000), admission(8), this.metrics);
        final var identity = identity("10.0.0.1", 1);

        for (int id = 1; id <= 500; id++) {
            bounded.accept(identity,
                    List.of(new UnsignedValue("applicationId", 0x03000000L + id)),
                    List.of(new StringValue("applicationName", "app" + id)));
        }

        int retained = 0;
        for (int id = 1; id <= 500; id++) {
            if (bounded.lookup(identity, 0x03000000L + id).isPresent()) {
                retained++;
            }
        }
        assertThat(retained).isLessThanOrEqualTo(8);
        assertThat(this.metrics.meter("enrichment.optionApplications.rejected").getCount()).isPositive();
    }

    @Test
    void entriesExpireOnTheOptionRetention() throws Exception {
        final var shortLived = new ExporterApplicationTable(config(1), new SessionAdmissionConfig(), this.metrics);
        final var identity = identity("10.10.3.1", 6);
        shortLived.accept(identity,
                List.of(new UnsignedValue("applicationId", HTTP)),
                List.of(new StringValue("applicationName", "http")));

        Thread.sleep(20);

        assertThat(shortLived.lookup(identity, HTTP)).isEmpty();
    }

    @Test
    void aFreshRowFillsOnlyTheFieldsItCarries() throws Exception {
        final var identity = identity("10.10.3.1", 6);
        this.table.accept(identity, List.of(new UnsignedValue("applicationId", HTTP)),
                List.of(new StringValue("applicationName", "http"),
                        new StringValue("applicationDescription", "World Wide Web traffic")));

        this.table.accept(identity, List.of(new UnsignedValue("applicationId", HTTP)),
                List.of(new StringValue("applicationName", "http")));

        assertThat(this.table.lookup(identity, HTTP))
                .contains(new ApplicationInfo("http", "World Wide Web traffic"));
    }
}
