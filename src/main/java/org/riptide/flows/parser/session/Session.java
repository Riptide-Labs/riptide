package org.riptide.flows.parser.session;

import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;

import java.net.InetAddress;
import java.util.Collection;
import java.util.List;

public interface Session {

    interface Resolver {
        Template lookupTemplate(int templateId) throws MissingTemplateException;
        List<Value<?>> lookupOptions(List<Value<?>> values);
    }

    void addTemplate(long observationDomainId, Template template);

    void removeTemplate(long observationDomainId, int templateId);

    void removeAllTemplate(long observationDomainId, Template.Type type);

    void addOptions(long observationDomainId, int templateId, Collection<Value<?>> scopes, List<Value<?>> values);

    Resolver getResolver(long observationDomainId);

    InetAddress getRemoteAddress();

    boolean verifySequenceNumber(long observationDomainId, long sequenceNumber);
}
