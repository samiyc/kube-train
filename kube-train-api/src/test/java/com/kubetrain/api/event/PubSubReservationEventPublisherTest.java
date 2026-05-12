package com.kubetrain.api.event;

import com.google.api.core.ApiFutures;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.PubsubMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de PubSubReservationEventPublisher.
 *
 * 🎯 Stratégie :
 *  - @PostConstruct init() contourne via ReflectionTestUtils.setField() qui injecte
 *    directement un mock Publisher (évite la connexion réelle à GCP)
 *  - ObjectMapper réel (Jackson 3) pour valider la sérialisation JSON
 *
 * 🎯 Cas couverts :
 *  1. Publication réussie → attribut reservationId présent, payload JSON valide
 *  2. Pub/Sub indisponible → exception absorbée (best-effort, pas de rethrow)
 */
class PubSubReservationEventPublisherTest {

    private Publisher pubSubPublisher;
    private PubSubReservationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        pubSubPublisher = mock(Publisher.class);
        // Constructeur sans @PostConstruct — init() non appelé en dehors du contexte Spring
        publisher = new PubSubReservationEventPublisher(new ObjectMapper());
        // Injection directe du mock pour bypasser la création du Publisher GCP réel
        ReflectionTestUtils.setField(publisher, "publisher", pubSubPublisher);
    }

    private ReservationEvent sampleEvent() {
        return ReservationEvent.builder()
                .eventId("evt-pubsub-001")
                .reservationId("RES-PUBSUB-001")
                .trainId("TGV-7042")
                .passengerName("Alice Dupont")
                .price(new BigDecimal("29.90"))
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Publie un message avec le reservationId en attribut et le payload JSON")
    void shouldPublishMessageWithCorrectAttributeAndPayload() {
        when(pubSubPublisher.publish(any(PubsubMessage.class)))
                .thenReturn(ApiFutures.immediateFuture("msg-id-gcp-123"));

        publisher.publish(sampleEvent());

        ArgumentCaptor<PubsubMessage> captor = ArgumentCaptor.forClass(PubsubMessage.class);
        verify(pubSubPublisher).publish(captor.capture());
        PubsubMessage sent = captor.getValue();

        assertThat(sent.getAttributesMap()).containsEntry("reservationId", "RES-PUBSUB-001");
        assertThat(sent.getData().toStringUtf8())
                .contains("RES-PUBSUB-001")
                .contains("TGV-7042")
                .contains("Alice Dupont");
    }

    @Test
    @DisplayName("Appelle publisher.publish() exactement une fois par événement")
    void shouldPublishExactlyOnce() {
        when(pubSubPublisher.publish(any(PubsubMessage.class)))
                .thenReturn(ApiFutures.immediateFuture("msg-id-gcp-456"));

        publisher.publish(sampleEvent());

        verify(pubSubPublisher, times(1)).publish(any(PubsubMessage.class));
    }

    @Test
    @DisplayName("Ne lève pas d'exception si Pub/Sub est indisponible (best-effort)")
    void shouldNotThrowWhenPubSubFails() {
        when(pubSubPublisher.publish(any(PubsubMessage.class)))
                .thenReturn(ApiFutures.immediateFailedFuture(new RuntimeException("Pub/Sub unreachable")));

        assertThatNoException().isThrownBy(() -> publisher.publish(sampleEvent()));
    }
}
