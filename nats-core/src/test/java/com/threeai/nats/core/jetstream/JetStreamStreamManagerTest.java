package com.threeai.nats.core.jetstream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JetStreamStreamManagerTest {

    private Connection connection;
    private JetStreamManagement jsm;
    private JetStreamStreamManager manager;

    @BeforeEach
    void setUp() throws IOException, JetStreamApiException {
        connection = mock(Connection.class);
        jsm = mock(JetStreamManagement.class);
        when(connection.jetStreamManagement()).thenReturn(jsm);
        manager = new JetStreamStreamManager();
    }

    @Test
    void ensureStream_exists_noAction() throws Exception {
        when(jsm.getStreamInfo("ORDERS")).thenReturn(mock(StreamInfo.class));

        assertThatCode(() -> manager.ensureStream("ORDERS", "order.>", connection))
                .doesNotThrowAnyException();

        verify(jsm, never()).addStream(any(StreamConfiguration.class));
    }

    @Test
    void ensureStream_notFound_creates() throws Exception {
        JetStreamApiException notFound = mock(JetStreamApiException.class);
        when(notFound.getErrorCode()).thenReturn(404);
        when(jsm.getStreamInfo("ORDERS")).thenThrow(notFound);

        manager.ensureStream("ORDERS", "order.>", connection);

        verify(jsm).addStream(any(StreamConfiguration.class));
    }

    @Test
    void ensureStream_apiFails_throwsIllegalStateException() throws Exception {
        JetStreamApiException serverError = mock(JetStreamApiException.class);
        when(serverError.getErrorCode()).thenReturn(500);
        when(jsm.getStreamInfo("ORDERS")).thenThrow(serverError);

        assertThatThrownBy(() -> manager.ensureStream("ORDERS", "order.>", connection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORDERS");
    }
}
