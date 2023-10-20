package org.riptide.flows.parser.session;

import org.riptide.flows.parser.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;

import java.net.InetAddress;
import java.util.Collection;
import java.util.List;

public interface Session {

    interface Resolver {
        Template lookupTemplate(final int templateId) throws MissingTemplateException;
        List<Value<?>> lookupOptions(final List<Value<?>> values);
    }

    void addTemplate(final long observationDomainId, final Template template);

    void removeTemplate(final long observationDomainId, final int templateId);

    void removeAllTemplate(final long observationDomainId, final Template.Type type);

    void addOptions(final long observationDomainId,
                    final int templateId,
                    final Collection<Value<?>> scopes,
                    final List<Value<?>> values);

    Resolver getResolver(final long observationDomainId);

    InetAddress getRemoteAddress();

    boolean verifySequenceNumber(final long observationDomainId,
                                 final long sequenceNumber);
}
