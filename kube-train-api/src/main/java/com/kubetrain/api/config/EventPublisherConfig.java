package com.kubetrain.api.config;

import com.kubetrain.api.event.ReservationEvent;
import com.kubetrain.api.event.ReservationEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fallback : publisher no-op quand Kafka n'est pas activé.
 *
 * 🎯 Pattern ConditionalOnMissingBean :
 *  - Si KafkaConfig a créé un ReservationEventPublisher → ce bean est IGNORÉ
 *  - Si Kafka est désactivé (tests, local sans Docker) → ce bean est créé
 *  - L'application fonctionne dans tous les cas, avec ou sans Kafka
 *
 * C'est le même pattern que Spring Boot utilise en interne :
 * "fournir un comportement par défaut qui est remplacé quand l'infra est disponible"
 */
@Slf4j
@Configuration
public class EventPublisherConfig {

    @Bean
    @ConditionalOnMissingBean(ReservationEventPublisher.class)
    public ReservationEventPublisher noOpPublisher() {
        return new ReservationEventPublisher() {
            @Override
            public void publish(ReservationEvent event) {
                log.debug("📭 Kafka non configuré — événement ignoré : {}", event.reservationId());
            }
        };
    }
}
