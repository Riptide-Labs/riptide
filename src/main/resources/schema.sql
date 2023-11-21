DO $$ BEGIN

IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'DIRECTION') THEN
CREATE TYPE DIRECTION AS ENUM ('ingress', 'egress');
END IF;

IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'LOCALITY') THEN
CREATE TYPE LOCALITY AS ENUM ('public', 'private');
END IF;

IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'PROTOCOL') THEN
CREATE TYPE PROTOCOL AS ENUM ('netflow5', 'netflow9', 'ipfix');
END IF;

IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'SAMPLING_ALG') THEN
CREATE TYPE SAMPLING_ALG AS ENUM ();
END IF;

END $$;

CREATE TABLE IF NOT EXISTS flows (
    id UUID,
    receivedAt TIMESTAMP WITHOUT TIME ZONE,
    timestamp TIMESTAMP WITHOUT TIME ZONE,
    bytes INTEGER,
    direction DIRECTION,
    dstAddr INET,
    dstAddrHostname VARCHAR(255),
    dstAs INTEGER,
    dstMaskLen INTEGER,
    dstPort INTEGER,
    engineId INTEGER,
    engineType INTEGER,
    deltaSwitched TIMESTAMP WITHOUT TIME ZONE,
    firstSwitched TIMESTAMP WITHOUT TIME ZONE,
    flowRecords INTEGER,
    flowSeqNum INTEGER,
    inputSnmp INTEGER,
    ipProtocolVersion INTEGER,
    lastSwitched TIMESTAMP WITHOUT TIME ZONE,
    nextHop INET,
    nextHopHostname VARCHAR(255),
    outputSnmp INTEGER,
    packets INTEGER,
    protocol INTEGER,
    samplingAlgorithm SAMPLING_ALG,
    samplingInterval DECIMAL,
    srcAddr INET,
    srcAddrHostname VARCHAR(255),
    srcAs INTEGER,
    srcMaskLen INTEGER,
    srcPort INTEGER,
    tcpFlags INTEGER,
    tos INTEGER,
    flowProtocol PROTOCOL,
    vlan INTEGER,
    application VARCHAR(255),
    exporterAddr VARCHAR(255),
    location VARCHAR(255),
    srcLocality LOCALITY,
    dstLocality LOCALITY,
    flowLocality LOCALITY,
    clockCorrection INTERVAL,
    inputSnmpIfName VARCHAR(255),
    outputSnmpIfName VARCHAR(255),
    convoKey BYTEA,
    dscp INTEGER,
    ecn INTEGER
)