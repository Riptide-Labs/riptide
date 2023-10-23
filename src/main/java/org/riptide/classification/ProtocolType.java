package org.riptide.classification;

// Convenient interface to access often used protocols
public interface ProtocolType {
    Protocol ICMP = Protocols.getProtocol("icmp");
    Protocol TCP = Protocols.getProtocol("tcp");
    Protocol UDP = Protocols.getProtocol("udp");
    Protocol DDP = Protocols.getProtocol("ddp");
    Protocol SCTP = Protocols.getProtocol("sctp");
}
