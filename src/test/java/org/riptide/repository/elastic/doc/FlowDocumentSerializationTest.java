package org.riptide.repository.elastic.doc;

import co.elastic.clients.json.JsonpMapper;
import com.google.gson.Gson;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.StringWriter;
import java.time.Instant;

/*
 * This test ensures that when using the Elastic internal serialization it works as
 * intended, as we were facing issues during development.
 */
@SpringBootTest
public class FlowDocumentSerializationTest {

    @Test
    void verifySerialization(@Autowired JsonpMapper jsonpMapper) throws JSONException {
        final var flowDocument = new FlowDocument();
        final var epochMillis = Instant.now().toEpochMilli();
        flowDocument.setTimestamp(epochMillis);
        flowDocument.setVersion(10);
        flowDocument.setIpProtocolVersion(-13);
        flowDocument.setFlowProtocol(FlowProtocol.IPFIX);
        flowDocument.setSrcAddr("127.0.0.1");
        flowDocument.setDstAddr("1.1.1.1");
        final var stringWriter = new StringWriter();
        jsonpMapper.serialize(flowDocument, jsonpMapper.jsonProvider().createGenerator(stringWriter));
        final var jsonString = stringWriter.getBuffer().toString();
        final var actualJsonObject = (JSONObject) new JSONTokener(jsonString).nextValue();
        final var expectedJsonObject = (JSONObject) new JSONTokener("""
                {
                    "@timestamp": %s,
                    "@version": 10,
                    "netflow.ip_protocol_version": -13,
                    "protocol": "IPFIX",
                    "netflow.src_addr": "127.0.0.1",
                    "netflow.dst_addr": "1.1.1.1",
                    "hosts": ["127.0.0.1", "1.1.1.1"],
                    "netflow.flow_records": 0,
                    "netflow.flow_seq_num": 0
                }
                """.formatted(epochMillis)).nextValue();
        JSONAssert.assertEquals(expectedJsonObject, actualJsonObject, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void verifyGsonAndJacksonSerializationAreEqual(@Autowired JsonpMapper jsonpMapper) throws JSONException {
        // Dummy values, but ensure everything is set
        final var flowDocument = new FlowDocument();
        flowDocument.setTimestamp(Instant.now().toEpochMilli());
        flowDocument.setClockCorrection(1234567890L);
        flowDocument.setVersion(13);
        flowDocument.setExporterAddr("1.1.1.1");
        flowDocument.setLocation("Cardiff");
        flowDocument.setApplication("Riptide-ftw");
        flowDocument.setBytes(17L);
        flowDocument.setConvoKey("DUMMY_CONVO_KEY");
        flowDocument.setDirection(Direction.INGRESS);
        flowDocument.setDstAddr("1.1.1.2");
        flowDocument.setDstAs(1337L);
        flowDocument.setDstLocality(Locality.PRIVATE);
        flowDocument.setDstMaskLen(19);
        flowDocument.setDstPort(8080);
        flowDocument.setEngineId(99);
        flowDocument.setEngineType(100);
        flowDocument.setFirstSwitched(9876543210L);
        flowDocument.setFlowLocality(Locality.PRIVATE);
        flowDocument.setFlowRecords(88888);
        flowDocument.setFlowSeqNum(89);
        flowDocument.setInputSnmp(21);
        flowDocument.setInputSnmpIfName("eth0");
        flowDocument.setIpProtocolVersion(-17);
        flowDocument.setLastSwitched(54321098765L);
        flowDocument.setNextHop("flotti-galoppi");
        flowDocument.setNextHopHostname("riptide.island");
        flowDocument.setOutputSnmp(29);
        flowDocument.setOutputSnmpIfName("eth1");
        flowDocument.setProtocol(11);
        flowDocument.setSamplingAlgorithm(SamplingAlgorithm.RandomNOutOfNSampling);
        flowDocument.setSamplingInterval(12.34);
        flowDocument.setSrcAddr("127.0.0.1");
        flowDocument.setSrcAddrHostname("riptide.cloud");
        flowDocument.setSrcAs(31L);
        flowDocument.setSrcLocality(Locality.PUBLIC);
        flowDocument.setSrcMaskLen(99);
        flowDocument.setSrcPort(999);
        flowDocument.setTcpFlags(1234999);
        flowDocument.setDeltaSwitched(444444L);
        flowDocument.setTos(33);
        flowDocument.setEcn(35);
        flowDocument.setDscp(37);
        flowDocument.setFlowProtocol(FlowProtocol.SFLOW);
        flowDocument.setVlan("riptide.vlan.17");
        final var stringWriter = new StringWriter();
        jsonpMapper.serialize(flowDocument, jsonpMapper.jsonProvider().createGenerator(stringWriter));
        final var jacksonJson = stringWriter.getBuffer().toString();
        final var gsonJson = new Gson().toJson(flowDocument);
        JSONAssert.assertEquals(gsonJson, jacksonJson, JSONCompareMode.LENIENT);
    }


}