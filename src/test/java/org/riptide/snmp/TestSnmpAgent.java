/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import java.io.IOException;
import java.nio.file.Path;

import org.snmp4j.TransportMapping;
import org.snmp4j.agent.BaseAgent;
import org.snmp4j.agent.CommandProcessor;
import org.snmp4j.agent.DuplicateRegistrationException;
import org.snmp4j.agent.ManagedObject;
import org.snmp4j.agent.mo.DefaultMOMutableRow2PC;
import org.snmp4j.agent.mo.DefaultMOTable;
import org.snmp4j.agent.mo.MOAccessImpl;
import org.snmp4j.agent.mo.MOColumn;
import org.snmp4j.agent.mo.MOMutableColumn;
import org.snmp4j.agent.mo.MOMutableTableModel;
import org.snmp4j.agent.mo.MOMutableTableRow;
import org.snmp4j.agent.mo.MOTableIndex;
import org.snmp4j.agent.mo.MOTableRow;
import org.snmp4j.agent.mo.MOTableSubIndex;
import org.snmp4j.agent.mo.snmp.RowStatus;
import org.snmp4j.agent.mo.snmp.SnmpCommunityMIB;
import org.snmp4j.agent.mo.snmp.SnmpNotificationMIB;
import org.snmp4j.agent.mo.snmp.SnmpTargetMIB;
import org.snmp4j.agent.mo.snmp.StorageType;
import org.snmp4j.agent.mo.snmp.VacmMIB;
import org.snmp4j.agent.security.MutableVACM;
import org.snmp4j.mp.MPv3;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.SMIConstants;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.Variable;
import org.snmp4j.transport.TransportMappings;

public class TestSnmpAgent extends BaseAgent {
    public static final String COMMUNITY = "superSecretC0mmunity";
    public static final String AUTHPRIV_USERNAME = "userAuthPriv";
    public static final String AUTHPRIV_AUTH_PASSHRASE = "authSecret";
    public static final String AUTHPRIV_PRIV_PASSHRASE = "privSecret";

    public static final String AUTHNOPRIV_USERNAME = "userAuthNoPriv";
    public static final String AUTHNOPRIV_AUTH_PASSHRASE = "authSecret";

    public static final String NOAUTHNOPRIV_USERNAME = "userNoAuthNoPriv";
    private String address;
    private DefaultMOTable ifXTable;

    public TestSnmpAgent(final String address, final Path temporaryFolder) throws IOException {
        super(temporaryFolder.resolve("conf.agent").toFile(), temporaryFolder.resolve("bootCounter.agent").toFile(), new CommandProcessor(new OctetString(MPv3.createLocalEngineID())));
        this.address = address;
        SecurityProtocols.getInstance().addPredefinedProtocolSet(SecurityProtocols.SecurityProtocolSet.maxCompatibility);
    }

    @Override
    protected void addCommunities(final SnmpCommunityMIB communityMIB) {
        final Variable[] com2sec = new Variable[]{
                new OctetString(COMMUNITY),
                new OctetString("communityUser"),
                getAgent().getContextEngineID(),
                new OctetString(),
                new OctetString(),
                new Integer32(StorageType.nonVolatile),
                new Integer32(RowStatus.active)
        };
        final SnmpCommunityMIB.SnmpCommunityEntryRow row = communityMIB.getSnmpCommunityEntry().createRow(new OctetString("communityUser").toSubIndex(true), com2sec);
        communityMIB.getSnmpCommunityEntry().addRow(row);
    }

    @Override
    protected void addNotificationTargets(final SnmpTargetMIB snmpTargetMIB, final SnmpNotificationMIB snmpNotificationMIB) {
    }

    @Override
    protected void addUsmUser(final USM usm) {
        usm.addUser(new UsmUser(new OctetString(AUTHPRIV_USERNAME), AuthSHA.ID, new OctetString(AUTHPRIV_AUTH_PASSHRASE), PrivAES128.ID, new OctetString(AUTHPRIV_PRIV_PASSHRASE)));
        usm.addUser(new UsmUser(new OctetString(AUTHNOPRIV_USERNAME), AuthSHA.ID, new OctetString(AUTHNOPRIV_AUTH_PASSHRASE), null, null));
        usm.addUser(new UsmUser(new OctetString(NOAUTHNOPRIV_USERNAME), null, null, null, null));
    }

    @Override
    protected void addViews(final VacmMIB vacm) {
        vacm.addGroup(SecurityModel.SECURITY_MODEL_SNMPv1, new OctetString("communityUser"), new OctetString("v1v2group"), StorageType.nonVolatile);
        vacm.addGroup(SecurityModel.SECURITY_MODEL_SNMPv2c, new OctetString("communityUser"), new OctetString("v1v2group"), StorageType.nonVolatile);
        vacm.addGroup(SecurityModel.SECURITY_MODEL_USM, new OctetString("userAuthPriv"), new OctetString("v3group"), StorageType.nonVolatile);
        vacm.addGroup(SecurityModel.SECURITY_MODEL_USM, new OctetString("userAuthNoPriv"), new OctetString("v3group"), StorageType.nonVolatile);
        vacm.addGroup(SecurityModel.SECURITY_MODEL_USM, new OctetString("userNoAuthNoPriv"), new OctetString("v3group"), StorageType.nonVolatile);

        vacm.addAccess(new OctetString("v1v2group"),
                new OctetString(""),
                SecurityModel.SECURITY_MODEL_ANY,
                SecurityLevel.NOAUTH_NOPRIV,
                MutableVACM.VACM_MATCH_EXACT,
                new OctetString("fullReadView"),
                new OctetString("fullWriteView"),
                new OctetString("fullNotifyView"),
                StorageType.nonVolatile);

        vacm.addAccess(new OctetString("v3group"),
                new OctetString(""),
                SecurityModel.SECURITY_MODEL_USM,
                SecurityLevel.NOAUTH_NOPRIV,
                MutableVACM.VACM_MATCH_EXACT,
                new OctetString("fullReadView"),
                new OctetString("fullWriteView"),
                new OctetString("fullNotifyView"),
                StorageType.nonVolatile);

        vacm.addViewTreeFamily(new OctetString("fullReadView"), new OID("1.3"),
                new OctetString(), VacmMIB.vacmViewIncluded,
                StorageType.nonVolatile);
    }

    @Override
    protected void unregisterManagedObjects() {
    }

    @Override
    protected void registerManagedObjects() {
    }

    @Override
    protected void initTransportMappings() throws IOException {
        transportMappings = new TransportMapping[1];
        transportMappings[0] = TransportMappings.getInstance().createTransportMapping(GenericAddress.parse(address));
    }

    public void start() throws IOException {
        init();
        addShutdownHook();
        getServer().addContext(new OctetString("public"));
        finishInit();
        run();
        sendColdStartNotification();
    }

    private void registerManagedObject(final ManagedObject mo) {
        try {
            server.register(mo, null);
        } catch (DuplicateRegistrationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private DefaultMOTable createStaticIfXTable() {
        final MOTableSubIndex[] subIndexes = new MOTableSubIndex[]{new MOTableSubIndex(SMIConstants.SYNTAX_INTEGER)};
        final MOTableIndex indexDef = new MOTableIndex(subIndexes, false);
        final MOColumn[] columns = new MOColumn[19];
        int c = 0;
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_OCTET_STRING, MOAccessImpl.ACCESS_READ_ONLY); // ifName
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER32, MOAccessImpl.ACCESS_READ_ONLY); // ifInMulticastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER32, MOAccessImpl.ACCESS_READ_ONLY); // ifInBroadcastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER32, MOAccessImpl.ACCESS_READ_ONLY); // ifOutMulticastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER32, MOAccessImpl.ACCESS_READ_ONLY); // ifOutBroadcastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER64, MOAccessImpl.ACCESS_READ_ONLY); // ifHCInOctets
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER64, MOAccessImpl.ACCESS_READ_ONLY); // ifHCInUcastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER64, MOAccessImpl.ACCESS_READ_ONLY); // ifHCInMulticastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER64, MOAccessImpl.ACCESS_READ_ONLY); // ifHCInBroadcastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER64, MOAccessImpl.ACCESS_READ_ONLY); // ifHCOutOctets
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER64, MOAccessImpl.ACCESS_READ_ONLY); // ifHCOutUcastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER64, MOAccessImpl.ACCESS_READ_ONLY); // ifHCOutMulticastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_COUNTER64, MOAccessImpl.ACCESS_READ_ONLY); // ifHCOutBroadcastPkts
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_INTEGER, MOAccessImpl.ACCESS_READ_WRITE); // ifLinkUpDownTrapEnable
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_GAUGE32, MOAccessImpl.ACCESS_READ_ONLY); // ifHighSpeed
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_INTEGER, MOAccessImpl.ACCESS_READ_WRITE); // ifPromiscuousMode
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_INTEGER, MOAccessImpl.ACCESS_READ_ONLY); // ifConnectorPresent
        columns[c++] = new MOMutableColumn(c, SMIConstants.SYNTAX_OCTET_STRING, MOAccessImpl.ACCESS_READ_WRITE, null); // ifAlias
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_TIMETICKS, MOAccessImpl.ACCESS_READ_ONLY); // ifCounterDiscontinuityTime

        final DefaultMOTable ifXTable = new DefaultMOTable(new OID("1.3.6.1.2.1.31.1.1.1"), indexDef, columns);
        final MOMutableTableModel model = (MOMutableTableModel) ifXTable.getModel();
        final Variable[] rowValues1 = new Variable[]{
                new OctetString("eth0-x"),
                new Integer32(1),
                new Integer32(2),
                new Integer32(3),
                new Integer32(4),
                new Counter64(5),
                new Counter64(6),
                new Counter64(7),
                new Counter64(8),
                new Counter64(9),
                new Counter64(10),
                new Counter64(11),
                new Counter64(12),
                new Integer32(13),
                new Integer32(14),
                new Integer32(15),
                new Integer32(16),
                new OctetString("My ethernet interface"),
                new TimeTicks(1000)
        };
        final Variable[] rowValues2 = new Variable[]{
                new OctetString("lo0-x"),
                new Integer32(21),
                new Integer32(22),
                new Integer32(23),
                new Integer32(24),
                new Counter64(25),
                new Counter64(26),
                new Counter64(27),
                new Counter64(28),
                new Counter64(29),
                new Counter64(30),
                new Counter64(31),
                new Counter64(32),
                new Integer32(33),
                new Integer32(34),
                new Integer32(35),
                new Integer32(36),
                new OctetString("My loopback interface"),
                new TimeTicks(2000)
        };
        model.addRow(new DefaultMOMutableRow2PC(new OID("1"), rowValues1));
        model.addRow(new DefaultMOMutableRow2PC(new OID("2"), rowValues2));
        ifXTable.setVolatile(true);
        return ifXTable;
    }

    private DefaultMOTable createStaticIfTable() {
        final MOTableSubIndex[] subIndexes = new MOTableSubIndex[]{new MOTableSubIndex(SMIConstants.SYNTAX_INTEGER)};
        final MOTableIndex indexDef = new MOTableIndex(subIndexes, false);
        final MOColumn[] columns = new MOColumn[12];
        int c = 0;
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_INTEGER, MOAccessImpl.ACCESS_READ_ONLY); // ifIndex
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_OCTET_STRING, MOAccessImpl.ACCESS_READ_ONLY); // ifDescr
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_INTEGER, MOAccessImpl.ACCESS_READ_ONLY); // ifType
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_INTEGER, MOAccessImpl.ACCESS_READ_ONLY); // ifMtu
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_GAUGE32, MOAccessImpl.ACCESS_READ_ONLY); // ifSpeed
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_OCTET_STRING, MOAccessImpl.ACCESS_READ_ONLY); // ifPhysAddress
        columns[c++] = new MOMutableColumn(c, SMIConstants.SYNTAX_INTEGER, MOAccessImpl.ACCESS_READ_WRITE, null); // ifAdminStatus
        columns[c++] = new MOColumn(c, SMIConstants.SYNTAX_INTEGER, MOAccessImpl.ACCESS_READ_ONLY); // ifOperStatus
        // ifTable columns 9-12 (ifLastChange, ifInOctets, ifInUcastPkts, ifInNUcastPkts) are not
        // modeled: nothing in riptide reads them. 13/14/19/20 are, for CollectionDefinitions'
        // ifInDiscards/ifInErrors/ifOutDiscards/ifOutErrors (32-bit; only the ifXTable "HC"
        // variants are 64-bit), so the gaps below are real, not omissions.
        columns[c] = new MOColumn(13, SMIConstants.SYNTAX_COUNTER32, MOAccessImpl.ACCESS_READ_ONLY); // ifInDiscards
        columns[c + 1] = new MOColumn(14, SMIConstants.SYNTAX_COUNTER32, MOAccessImpl.ACCESS_READ_ONLY); // ifInErrors
        columns[c + 2] = new MOColumn(19, SMIConstants.SYNTAX_COUNTER32, MOAccessImpl.ACCESS_READ_ONLY); // ifOutDiscards
        columns[c + 3] = new MOColumn(20, SMIConstants.SYNTAX_COUNTER32, MOAccessImpl.ACCESS_READ_ONLY); // ifOutErrors

        final DefaultMOTable ifTable = new DefaultMOTable(new OID("1.3.6.1.2.1.2.2.1"), indexDef, columns);
        final MOMutableTableModel model = (MOMutableTableModel) ifTable.getModel();
        final Variable[] rowValues1 = new Variable[]{
                new Integer32(1),
                new OctetString("eth0"),
                new Integer32(6),
                new Integer32(1500),
                new Gauge32(100000000),
                new OctetString("00:00:00:00:01"),
                new Integer32(1),
                new Integer32(1),
                new Counter32(13),
                new Counter32(14),
                new Counter32(19),
                new Counter32(20)
        };
        final Variable[] rowValues2 = new Variable[]{
                new Integer32(2),
                new OctetString("lo0"),
                new Integer32(24),
                new Integer32(1500),
                new Gauge32(10000000),
                new OctetString("00:00:00:00:02"),
                new Integer32(1),
                new Integer32(1),
                new Counter32(33),
                new Counter32(34),
                new Counter32(39),
                new Counter32(40)
        };
        model.addRow(new DefaultMOMutableRow2PC(new OID("1"), rowValues1));
        model.addRow(new DefaultMOMutableRow2PC(new OID("2"), rowValues2));
        ifTable.setVolatile(true);
        return ifTable;
    }

    public void registerIfTable() {
        registerManagedObject(createStaticIfTable());
    }

    public void registerIfXTable() {
        this.ifXTable = createStaticIfXTable();
        registerManagedObject(this.ifXTable);
    }

    /** Overwrites one ifXTable cell so a test can make a counter move between walks. */
    public void setCounter(final int ifIndex, final int column, final long value) {
        final MOTableRow row = this.ifXTable.getModel().getRow(new OID(new int[]{ifIndex}));
        ((MOMutableTableRow) row).setValue(column - 1, new Counter64(value));
    }
}