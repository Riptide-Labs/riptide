package org.riptide.snmp;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
@ConfigurationPropertiesBinding
public class SnmpVersionConverter implements Converter<String, SnmpVersion> {
    private final SnmpVersion1 snmpVersion1;
    private final SnmpVersion2c snmpVersion2c;
    private final SnmpVersion3 snmpVersion3;

    public SnmpVersionConverter(final SnmpVersion1 snmpVersion1, final SnmpVersion2c snmpVersion2c, final SnmpVersion3 snmpVersion3) {
        this.snmpVersion1 = snmpVersion1;
        this.snmpVersion2c = snmpVersion2c;
        this.snmpVersion3 = snmpVersion3;
    }

    @Override
    public SnmpVersion convert(String source) {
        switch (source) {
            case "v1":
                return snmpVersion1;
            case "v2c":
                return snmpVersion2c;
            case "v3":
                return snmpVersion3;
            default:
                throw new RuntimeException("Unknown SNMP version");
        }
    }
}
