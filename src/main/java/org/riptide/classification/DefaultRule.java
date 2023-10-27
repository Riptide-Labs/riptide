package org.riptide.classification;

import lombok.Data;

import java.util.Objects;

@Data
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
}
