package org.flowable.eventregistry.spring.nats.channel;

import org.flowable.eventregistry.model.InboundChannelModel;

public class NatsInboundChannelModel extends InboundChannelModel {

    private String subject;
    private String queueGroup;
    private boolean jetstream;

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getQueueGroup() {
        return queueGroup;
    }

    public void setQueueGroup(String queueGroup) {
        this.queueGroup = queueGroup;
    }

    public boolean isJetstream() {
        return jetstream;
    }

    public void setJetstream(boolean jetstream) {
        this.jetstream = jetstream;
    }
}
