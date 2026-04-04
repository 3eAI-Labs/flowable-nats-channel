package com.threeai.nats.camunda.outbound;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.nio.charset.StandardCharsets;

import com.threeai.nats.core.metrics.NatsChannelMetrics;
import io.nats.client.Connection;
import io.nats.client.impl.NatsMessage;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.Expression;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NatsPublishDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(NatsPublishDelegate.class);

    private final Connection connection;
    private final NatsChannelMetrics metrics;

    private Expression subject;
    private Expression payloadVariable;

    public NatsPublishDelegate(Connection connection, NatsChannelMetrics metrics) {
        this.connection = connection;
        this.metrics = metrics;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String subjectVal = getRequiredString(subject, execution, "subject");
        String payloadVar = getString(payloadVariable, execution, "natsPayload");
        byte[] data = serializePayload(execution.getVariable(payloadVar));

        try {
            NatsMessage msg = NatsMessage.builder()
                    .subject(subjectVal)
                    .data(data)
                    .build();
            connection.publish(msg);

            if (metrics != null) {
                metrics.publishCount(subjectVal, "camunda").increment();
            }
            log.debug("Published message to NATS",
                    kv("subject", subjectVal),
                    kv("process_instance", execution.getProcessInstanceId()));
        } catch (Exception e) {
            if (metrics != null) {
                metrics.publishErrorCount(subjectVal, "camunda").increment();
            }
            throw new ProcessEngineException(
                    "Failed to publish to NATS subject '" + subjectVal + "'", e);
        }
    }

    private String getRequiredString(Expression expr, DelegateExecution execution, String fieldName) {
        if (expr == null) {
            throw new ProcessEngineException("NATS publish: '" + fieldName + "' is required");
        }
        String value = (String) expr.getValue(execution);
        if (value == null || value.isBlank()) {
            throw new ProcessEngineException("NATS publish: '" + fieldName + "' resolved to blank");
        }
        return value;
    }

    private String getString(Expression expr, DelegateExecution execution, String defaultValue) {
        if (expr == null) return defaultValue;
        Object value = expr.getValue(execution);
        return value != null ? value.toString() : defaultValue;
    }

    private byte[] serializePayload(Object payload) {
        if (payload == null) return new byte[0];
        if (payload instanceof byte[] bytes) return bytes;
        if (payload instanceof String str) return str.getBytes(StandardCharsets.UTF_8);
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    public void setSubject(Expression subject) { this.subject = subject; }
    public void setPayloadVariable(Expression payloadVariable) { this.payloadVariable = payloadVariable; }
}
