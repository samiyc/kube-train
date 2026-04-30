package com.kubetrain.api.config;

import com.kubetrain.api.event.KafkaReservationEventPublisher;
import com.kubetrain.api.event.ReservationEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Configuration Kafka complète — activée uniquement si app.kafka.enabled=true.
 *
 * 🎯 Pattern conditionnel :
 *  - Toute cette classe est IGNORÉE si Kafka n'est pas activé
 *  - En test : pas de Kafka → NoOp publisher (voir EventPublisherConfig)
 *  - En local avec Docker Compose : KAFKA_ENABLED=true → Kafka publisher actif
 *
 * 🎯 Pourquoi une config manuelle plutôt qu'auto-configuration ?
 *  - Contrôle explicite : on voit exactement ce qui est configuré
 *  - Pas de surprise avec les types génériques de KafkaTemplate
 *  - Désactivation propre via propriété (pas besoin de @MockBean dans chaque test)
 */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                // Désactive les headers de type Java → le consumer n'a pas besoin
                // de la MÊME classe Java (découplage entre microservices)
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false
        ));
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public NewTopic reservationsTopic() {
        return TopicBuilder.name(KafkaReservationEventPublisher.TOPIC)
                .partitions(3)       // 3 partitions = 3 consumers max en parallèle
                .replicas(1)         // 1 réplica (dev). En prod = 3 minimum.
                .build();
    }

    @Bean
    @Primary
    public ReservationEventPublisher kafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new KafkaReservationEventPublisher(kafkaTemplate);
    }
}
