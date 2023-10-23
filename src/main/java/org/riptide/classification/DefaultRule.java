package org.riptide.classification;

import java.util.Objects;

public class DefaultRule implements Rule {

    private String name;
    private String dstAddress;
    private String dstPort;
    private String srcPort;
    private String srcAddress;
    private String protocol;
    private String exporterFilter;
    private int groupPosition;
    private int position;

    private boolean omnidirectional;

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDstAddress() {
        return dstAddress;
    }

    @Override
    public String getDstPort() {
        return dstPort;
    }

    @Override
    public String getSrcPort() {
        return srcPort;
    }

    @Override
    public String getSrcAddress() {
        return srcAddress;
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public String getExporterFilter() {
        return exporterFilter;
    }

    @Override
    public int getGroupPosition() {
        return groupPosition;
    }

    @Override
    public int getPosition() {
        return position;
    }

    @Override
    public boolean isOmnidirectional() {
        return this.omnidirectional;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDstAddress(String dstAddress) {
        this.dstAddress = dstAddress;
    }

    public void setDstPort(String dstPort) {
        this.dstPort = dstPort;
    }

    public void setSrcPort(String srcPort) {
        this.srcPort = srcPort;
    }

    public void setSrcAddress(String srcAddress) {
        this.srcAddress = srcAddress;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public void setExporterFilter(String exporterFilter) {
        this.exporterFilter = exporterFilter;
    }

    public void setGroupPosition(int groupPosition) {
        this.groupPosition = groupPosition;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public void setOmnidirectional(final boolean omnidirectional) {
        this.omnidirectional = omnidirectional;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DefaultRule that = (DefaultRule) o;
        return Objects.equals(groupPosition, that.groupPosition)
                && Objects.equals(name, that.name)
                && Objects.equals(dstAddress, that.dstAddress)
                && Objects.equals(dstPort, that.dstPort)
                && Objects.equals(srcPort, that.srcPort)
                && Objects.equals(srcAddress, that.srcAddress)
                && Objects.equals(protocol, that.protocol)
                && Objects.equals(exporterFilter, that.exporterFilter)
                && Objects.equals(position, that.position);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, dstAddress, dstPort, srcPort, srcAddress, protocol, exporterFilter, groupPosition, position);
    }

}
