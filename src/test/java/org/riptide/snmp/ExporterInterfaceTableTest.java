/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ExporterInterfaceTableTest {

    private final MetricRegistry metrics = new MetricRegistry();
    private final ExporterInterfaceTable table =
            new ExporterInterfaceTable(config(), admissionConfig(), metrics);

    private static SnmpOptionsConfig config() {
        final SnmpOptionsConfig config = new SnmpOptionsConfig();
        config.setRetentionMs(60_000);
        return config;
    }

    private static SessionAdmissionConfig admissionConfig() {
        return new SessionAdmissionConfig();
    }

    private static SessionAdmissionConfig admissionConfig(final int maxIfIndexesPerScope) {
        final SessionAdmissionConfig config = new SessionAdmissionConfig();
        config.setMaxIfIndexesPerScope(maxIfIndexesPerScope);
        return config;
    }

    /**
     * The option table's own half of GHSA-rggj-c47j-46v9. A per-scope cap is not enough on its own
     * for this table: {@code addOptions} runs once per option data record, several hundred fit in a
     * datagram, and {@code ifIndex} is 32 bits — so an attacker inside a single <em>admitted</em>
     * scope can grow it without ever tripping the session bound.
     */
    @Test
    public void ifIndexSprayWithinOneScopeIsBounded() throws Exception {
        final var table = new ExporterInterfaceTable(config(), admissionConfig(8), this.metrics);
        final var identity = identity("10.0.0.1", 1);

        for (int ifIndex = 1; ifIndex <= 500; ifIndex++) {
            table.accept(identity,
                    List.of(new UnsignedValue("SCOPE:INTERFACE", ifIndex)),
                    List.of(new StringValue("IF_NAME", "if" + ifIndex)));
        }

        int retained = 0;
        for (int ifIndex = 1; ifIndex <= 500; ifIndex++) {
            if (table.lookup(identity, ifIndex).isPresent()) {
                retained++;
            }
        }
        assertThat(retained)
                .as("500 sprayed interfaces must not become 500 retained entries")
                .isLessThanOrEqualTo(8);
        assertThat(this.metrics.meter("enrichment.optionInterfaces.rejected").getCount())
                .as("the drop is visible, so a cap set too low for a real chassis can be told from an attack")
                .isPositive();
    }

    /**
     * A spray in one scope must not cost another scope its interface names — the same isolation
     * property the session budget has, one level down.
     */
    @Test
    public void oneScopeSprayingDoesNotEvictAnotherScopesInterfaces() throws Exception {
        final var table = new ExporterInterfaceTable(config(), admissionConfig(4), this.metrics);
        final var victim = identity("10.0.0.2", 1);
        final var attacker = identity("10.0.0.1", 1);

        table.accept(victim, List.of(new UnsignedValue("SCOPE:INTERFACE", 1)),
                List.of(new StringValue("IF_NAME", "victim-if1")));

        for (int ifIndex = 1; ifIndex <= 200; ifIndex++) {
            table.accept(attacker, List.of(new UnsignedValue("SCOPE:INTERFACE", ifIndex)),
                    List.of(new StringValue("IF_NAME", "spray" + ifIndex)));
        }

        assertThat(table.lookup(victim, 1)).map(IfInfo::name)
                .as("the victim's entry survives an unrelated scope filling its own budget")
                .contains("victim-if1");
    }

    /**
     * Refreshing an interface already held is not a new entry, so a device whose budget is full
     * still tracks renames on the interfaces it does hold.
     */
    @Test
    public void refreshingAHeldInterfaceIsNotRefusedByAFullBudget() throws Exception {
        final var table = new ExporterInterfaceTable(config(), admissionConfig(2), this.metrics);
        final var identity = identity("10.0.0.1", 1);

        table.accept(identity, List.of(new UnsignedValue("SCOPE:INTERFACE", 1)),
                List.of(new StringValue("IF_NAME", "before")));
        table.accept(identity, List.of(new UnsignedValue("SCOPE:INTERFACE", 2)),
                List.of(new StringValue("IF_NAME", "other")));
        table.accept(identity, List.of(new UnsignedValue("SCOPE:INTERFACE", 1)),
                List.of(new StringValue("IF_NAME", "after")));

        assertThat(table.lookup(identity, 1)).map(IfInfo::name).contains("after");
    }

    private static ExporterIdentity identity(final String host, final long domain) throws UnknownHostException {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(host), domain);
    }

    @Test
    public void shapeA_v9InterfaceScope() throws Exception {
        final var identity = identity("10.0.0.1", 1);
        this.table.accept(identity,
                List.of(new UnsignedValue("SCOPE:INTERFACE", 7)),
                List.of(new StringValue("IF_NAME", "Eth1/0"), new StringValue("IF_DESC", "uplink")));

        assertThat(this.table.lookup(identity, 7)).contains(new IfInfo("Eth1/0", "uplink", null));
    }

    @Test
    public void shapeB_systemScopeWithIfIndexField_descriptionOnly() throws Exception {
        // the real Cisco ASR9k shape: system scope, INPUT_SNMP as field, IF_DESC only
        final var identity = identity("10.0.0.1", 2177);
        this.table.accept(identity,
                List.of(new UnsignedValue("SCOPE:SYSTEM", 0xC1C2C3C4L)),
                List.of(new UnsignedValue("INPUT_SNMP", 74), new StringValue("IF_DESC", "TenGigE0_0_1_0")));

        assertThat(this.table.lookup(identity, 74)).contains(new IfInfo(null, "TenGigE0_0_1_0", null));
    }

    @Test
    public void shapeC_ipfixIngressInterfaceScope() throws Exception {
        final var identity = identity("10.0.0.2", 0);
        this.table.accept(identity,
                List.of(new UnsignedValue("ingressInterface", 5)),
                List.of(new StringValue("interfaceName", "ge-0/0/0"), new StringValue("interfaceDescription", "core")));

        assertThat(this.table.lookup(identity, 5)).contains(new IfInfo("ge-0/0/0", "core", null));
    }

    @Test
    public void mappingsAreScopedPerExporterIdentity() throws Exception {
        final var a = identity("10.0.0.1", 1);
        final var b = identity("10.0.0.1", 2); // same address, different domain
        this.table.accept(a, List.of(new UnsignedValue("SCOPE:INTERFACE", 5)),
                List.of(new StringValue("IF_NAME", "a-if5")));
        this.table.accept(b, List.of(new UnsignedValue("SCOPE:INTERFACE", 5)),
                List.of(new StringValue("IF_NAME", "b-if5")));

        assertThat(this.table.lookup(a, 5)).map(IfInfo::name).contains("a-if5");
        assertThat(this.table.lookup(b, 5)).map(IfInfo::name).contains("b-if5");
    }

    @Test
    public void nonInterfaceOptionsAreIgnoredWithoutMetrics() throws Exception {
        // sampler table (opttpl257 shape): no IE 82/83 → not even counted as skipped
        this.table.accept(identity("10.0.0.1", 1),
                List.of(new UnsignedValue("SCOPE:SYSTEM", 1)),
                List.of(new UnsignedValue("SAMPLER_ID", 1), new StringValue("SAMPLER_NAME", "s1")));

        assertThat(this.metrics.meter("enrichment.optionInterfaces.consumed").getCount()).isZero();
        assertThat(this.metrics.meter("enrichment.optionInterfaces.skipped").getCount()).isZero();
    }

    @Test
    public void interfaceRecordWithoutResolvableIfIndexIsCountedSkipped() throws Exception {
        this.table.accept(identity("10.0.0.1", 1),
                List.of(new UnsignedValue("SCOPE:SYSTEM", 1)),
                List.of(new StringValue("IF_NAME", "orphan")));

        assertThat(this.metrics.meter("enrichment.optionInterfaces.skipped").getCount()).isEqualTo(1);
        assertThat(this.metrics.meter("enrichment.optionInterfaces.consumed").getCount()).isZero();
    }

    @Test
    public void wireStringsAreNulTrimmed() throws Exception {
        final var identity = identity("10.0.0.1", 1);
        this.table.accept(identity,
                List.of(new UnsignedValue("SCOPE:INTERFACE", 9)),
                List.of(new StringValue("IF_NAME", "Eth9\0\0\0\0"), new StringValue("IF_DESC", "\0\0\0")));

        assertThat(this.table.lookup(identity, 9)).contains(new IfInfo("Eth9", null, null));
    }

    @Test
    public void entriesExpireOnRetention() throws Exception {
        final SnmpOptionsConfig expiring = new SnmpOptionsConfig();
        expiring.setRetentionMs(0); // immediate expiry
        final ExporterInterfaceTable shortLived = new ExporterInterfaceTable(expiring, new SessionAdmissionConfig(), new MetricRegistry());
        final var identity = identity("10.0.0.1", 1);

        shortLived.accept(identity, List.of(new UnsignedValue("SCOPE:INTERFACE", 3)),
                List.of(new StringValue("IF_NAME", "gone")));

        assertThat(shortLived.lookup(identity, 3)).isEmpty();
    }

    @Test
    public void splitTablesMergePerField() throws Exception {
        // exporter sends name and description in separate option tables — arrival
        // order must not clobber the other field
        final var identity = identity("10.0.0.1", 1);
        this.table.accept(identity, List.of(new UnsignedValue("SCOPE:INTERFACE", 4)),
                List.of(new StringValue("IF_NAME", "Eth4")));
        this.table.accept(identity, List.of(new UnsignedValue("SCOPE:SYSTEM", 1)),
                List.of(new UnsignedValue("INPUT_SNMP", 4), new StringValue("IF_DESC", "backbone")));

        assertThat(this.table.lookup(identity, 4)).contains(new IfInfo("Eth4", "backbone", null));

        // and the fresher record wins per field
        this.table.accept(identity, List.of(new UnsignedValue("SCOPE:INTERFACE", 4)),
                List.of(new StringValue("IF_NAME", "Eth4-renamed")));
        assertThat(this.table.lookup(identity, 4)).contains(new IfInfo("Eth4-renamed", "backbone", null));
    }

    @Test
    public void zeroScopeValueFallsThroughToTheFieldIfIndex() throws Exception {
        final var identity = identity("10.0.0.1", 1);
        this.table.accept(identity,
                List.of(new UnsignedValue("SCOPE:INTERFACE", 0)),
                List.of(new UnsignedValue("INPUT_SNMP", 11), new StringValue("IF_NAME", "via-field")));

        assertThat(this.table.lookup(identity, 11)).map(IfInfo::name).contains("via-field");
    }

    @Test
    public void egressKeyedRecordsAreAccepted() throws Exception {
        final var identity = identity("10.0.0.1", 1);
        this.table.accept(identity,
                List.of(new UnsignedValue("egressInterface", 12)),
                List.of(new StringValue("interfaceName", "egress-if")));
        this.table.accept(identity,
                List.of(new UnsignedValue("SCOPE:SYSTEM", 1)),
                List.of(new UnsignedValue("OUTPUT_SNMP", 13), new StringValue("IF_DESC", "out-desc")));

        assertThat(this.table.lookup(identity, 12)).map(IfInfo::name).contains("egress-if");
        assertThat(this.table.lookup(identity, 13)).map(IfInfo::alias).contains("out-desc");
    }
}
