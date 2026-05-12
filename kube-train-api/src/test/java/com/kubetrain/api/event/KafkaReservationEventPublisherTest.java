package com.kubetrain.api.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de KafkaReservationEventPublisher.
 *
 * 🎯 Cas couverts :
 *  1. Publication réussie → kafkaTemplate.send() appelé avec le bon topic et la bonne clé
 *  2. Kafka indisponible → exception absorbée (best-effort, pas de rethrow)
 */
@SuppressWarnings("unchecked")
class KafkaReservationEventPublisherTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private KafkaReservationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new KafkaReservationEventPublisher(kafkaTemplate);
    }

    private ReservationEvent sampleEvent() {
        return ReservationEvent.builder()
                .eventId("evt-001")
                .reservationId("RES-001")
                .trainId("TGV-7042")
                .passengerName("Jean Dupont")
                .price(new BigDecimal("29.90"))
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Publie sur le bon topic avec le reservationId comme clé de partition")
    void shouldPublishToCorrectTopicWithReservationIdAsKey() {
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ReservationEvent event = sampleEvent();

        publisher.publish(event);

        verify(kafkaTemplate).send(
                eq(KafkaReservationEventPublisher.TOPIC),
                eq("RES-001"),
                eq(event)
        );
    }

    @Test
    @DisplayName("N'envoie pas de réservation sur un autre topic")
    void shouldNotPublishToWrongTopic() {
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(sampleEvent());

        verify(kafkaTemplate, never()).send(eq("wrong-topic"), any(), any());
    }

    @Test
    @DisplayName("Ne lève pas d'exception si Kafka est indisponible (best-effort)")
    void shouldNotThrowWhenKafkaFails() {
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));

        assertThatNoException().isThrownBy(() -> publisher.publish(sampleEvent()));
    }
}
