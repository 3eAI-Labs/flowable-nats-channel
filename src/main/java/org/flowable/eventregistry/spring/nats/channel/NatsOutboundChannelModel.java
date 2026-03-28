package org.flowable.eventregistry.spring.nats.channel;

import org.flowable.eventregistry.model.OutboundChannelModel;

public class NatsOutboundChannelModel extends OutboundChannelModel {

    private String subject;
    private boolean jetstream;

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public boolean isJetstream() {
        return jetstream;
    }

    public void setJetstream(boolean jetstream) {
        this.jetstream = jetstream;
    }
}
