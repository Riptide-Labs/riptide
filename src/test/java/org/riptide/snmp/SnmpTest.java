package org.riptide.snmp;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snmp4j.fluent.TargetBuilder;

import inet.ipaddr.IPAddressString;

public class SnmpTest {

    public static SnmpEndpoint communityV1(final IPAddressString ipAddressString, final int port, final String community) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
        snmpDefinition.setSnmpVersion(SnmpVersion.v1);
        snmpDefinition.setCommunity(community);
        snmpDefinition.setSecurityName(null);
        snmpDefinition.setAuthProtocol(null);
        snmpDefinition.setAuthPassphrase(null);
        snmpDefinition.setPrivProtocol(null);
        snmpDefinition.setPrivPassphrase(null);
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    public static SnmpEndpoint communityV2c(final IPAddressString ipAddressString, final int port, final String community) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
        snmpDefinition.setSnmpVersion(SnmpVersion.v2c);
        snmpDefinition.setCommunity(community);
        snmpDefinition.setSecurityName(null);
        snmpDefinition.setAuthProtocol(null);
        snmpDefinition.setAuthPassphrase(null);
        snmpDefinition.setPrivProtocol(null);
        snmpDefinition.setPrivPassphrase(null);
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    public static SnmpEndpoint noAuthNoPriv(final IPAddressString ipAddressString, final int port, final String securityName) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
        snmpDefinition.setSnmpVersion(SnmpVersion.v3);
        snmpDefinition.setCommunity(null);
        snmpDefinition.setSecurityName(securityName);
        snmpDefinition.setAuthProtocol(null);
        snmpDefinition.setAuthPassphrase(null);
        snmpDefinition.setPrivProtocol(null);
        snmpDefinition.setPrivPassphrase(null);
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    public static SnmpEndpoint authNoPriv(final IPAddressString ipAddressString, final int port, final String securityName, final TargetBuilder.AuthProtocol authProtocol, final String authPassphrase) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
        snmpDefinition.setSnmpVersion(SnmpVersion.v3);
        snmpDefinition.setCommunity(null);
        snmpDefinition.setSecurityName(securityName);
        snmpDefinition.setAuthProtocol(authProtocol);
        snmpDefinition.setAuthPassphrase(authPassphrase);
        snmpDefinition.setPrivProtocol(null);
        snmpDefinition.setPrivPassphrase(null);
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    public static SnmpEndpoint authPriv(final IPAddressString ipAddressString, final int port, final String securityName, final TargetBuilder.AuthProtocol authProtocol, final String authPassphrase, final TargetBuilder.PrivProtocol privProtocol, final String privPassphrase) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
        snmpDefinition.setSnmpVersion(SnmpVersion.v3);
        snmpDefinition.setCommunity(null);
        snmpDefinition.setSecurityName(securityName);
        snmpDefinition.setAuthProtocol(authProtocol);
        snmpDefinition.setAuthPassphrase(authPassphrase);
        snmpDefinition.setPrivProtocol(privProtocol);
        snmpDefinition.setPrivPassphrase(privPassphrase);
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    @Test
    public void testSnmpV2(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        final SnmpEndpoint snmpEndpoint = communityV2c(new IPAddressString("127.0.0.1"), 12345, TestSnmpAgent.COMMUNITY);
        final Map<Integer, String> ifMap = SnmpUtils.getSnmpInterfaceMap(snmpEndpoint);

        snmpAgent.stop();

        assertThat(ifMap.get(1)).isEqualTo("eth0-x");
        assertThat(ifMap.get(2)).isEqualTo("lo0-x");
    }

    @Test
    public void testSnmpV3_noAuthNoPriv(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        final SnmpEndpoint snmpEndpoint = noAuthNoPriv(new IPAddressString("127.0.0.1"), 12345, TestSnmpAgent.NOAUTHNOPRIV_USERNAME);
        final Map<Integer, String> ifMap = SnmpUtils.getSnmpInterfaceMap(snmpEndpoint);

        snmpAgent.stop();

        assertThat(ifMap.get(1)).isEqualTo("eth0-x");
        assertThat(ifMap.get(2)).isEqualTo("lo0-x");
    }

    @Test
    public void testIfTableFallback(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();

        final SnmpEndpoint snmpEndpoint = noAuthNoPriv(new IPAddressString("127.0.0.1"), 12345, TestSnmpAgent.NOAUTHNOPRIV_USERNAME);
        final Map<Integer, String> ifMap = SnmpUtils.getSnmpInterfaceMap(snmpEndpoint);

        snmpAgent.stop();

        assertThat(ifMap.get(1)).isEqualTo("eth0");
        assertThat(ifMap.get(2)).isEqualTo("lo0");
    }

    @Test
    public void testSnmpV3_authNoPriv(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        final SnmpEndpoint snmpEndpoint = authNoPriv(new IPAddressString("127.0.0.1"), 12345, TestSnmpAgent.AUTHNOPRIV_USERNAME, TargetBuilder.AuthProtocol.sha1, TestSnmpAgent.AUTHNOPRIV_AUTH_PASSHRASE);
        final Map<Integer, String> ifMap = SnmpUtils.getSnmpInterfaceMap(snmpEndpoint);

        snmpAgent.stop();

        assertThat(ifMap.get(1)).isEqualTo("eth0-x");
        assertThat(ifMap.get(2)).isEqualTo("lo0-x");
    }

    @Test
    public void testSnmpV3_authPriv(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        final SnmpEndpoint snmpEndpoint = authPriv(new IPAddressString("127.0.0.1"), 12345, TestSnmpAgent.AUTHPRIV_USERNAME, TargetBuilder.AuthProtocol.sha1, TestSnmpAgent.AUTHPRIV_AUTH_PASSHRASE, TargetBuilder.PrivProtocol.aes128, TestSnmpAgent.AUTHPRIV_PRIV_PASSHRASE);
        final Map<Integer, String> ifMap = SnmpUtils.getSnmpInterfaceMap(snmpEndpoint);

        snmpAgent.stop();

        assertThat(ifMap.get(1)).isEqualTo("eth0-x");
        assertThat(ifMap.get(2)).isEqualTo("lo0-x");
    }

    @Test
    public void testSnmpCache(@TempDir Path temporaryFolder) throws IOException, ExecutionException {
        final SnmpCache snmpCache = new SnmpCache();
        final SnmpEndpoint snmpEndpoint = communityV2c(new IPAddressString("127.0.0.1"), 12345, TestSnmpAgent.COMMUNITY);

        assertThat(snmpCache.getIfName(snmpEndpoint, 1)).isInstanceOf(Optional.class).isEmpty();

        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();

        assertThat(snmpCache.getIfName(snmpEndpoint, 1)).isInstanceOf(Optional.class).isPresent();
        assertThat(snmpCache.getIfName(snmpEndpoint, 1).get()).isEqualTo("eth0");
        assertThat(snmpCache.getIfName(snmpEndpoint, 2)).isInstanceOf(Optional.class).isPresent();
        assertThat(snmpCache.getIfName(snmpEndpoint, 2).get()).isEqualTo("lo0");

        snmpAgent.stop();

        assertThat(snmpCache.getIfName(snmpEndpoint, 1)).isInstanceOf(Optional.class).isPresent();
        assertThat(snmpCache.getIfName(snmpEndpoint, 1).get()).isEqualTo("eth0");
        assertThat(snmpCache.getIfName(snmpEndpoint, 2)).isInstanceOf(Optional.class).isPresent();
        assertThat(snmpCache.getIfName(snmpEndpoint, 2).get()).isEqualTo("lo0");
    }
}
