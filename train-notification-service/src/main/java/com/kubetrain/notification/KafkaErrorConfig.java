package com.kubetrain.notification;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Configuration du DLT (Dead Letter Topic) pour le consumer Kafka.
 *
 * 🎯 Problème résolu :
 *  Sans DLT, un message "poison" (JSON invalide, erreur métier) est retenté
 *  indéfiniment → le consumer est bloqué, les autres messages s'accumulent.
 *
 * 🎯 Solution — DefaultErrorHandler + DeadLetterPublishingRecoverer :
 *  1. Le message est retenté N fois (ici 2 retries, donc 3 tentatives au total)
 *  2. Si toujours en erreur → envoyé sur le topic "train-reservations.DLT"
 *  3. Le DLT est consommé par un @KafkaListener dédié (alerting, stockage)
 *  4. Le consumer principal continue avec les messages suivants
 *
 * 🎯 Question entretien :
 *  "Comment gérer un message Kafka qui échoue systématiquement ?"
 *  → Dead Letter Topic : on l'isole, on alerte, on analyse plus tard.
 *  → JAMAIS de retry infini en prod (bloque toute la partition).
 *
 * 🎯 En prod, variantes courantes :
 *  - ExponentialBackOff au lieu de FixedBackOff (évite de surcharger un service down)
 *  - Retry conditionnel : certaines exceptions ne méritent pas de retry
 *    (ex: JSON invalide → inutile de retenter, envoyer direct au DLT)
 *  - Monitoring sur le DLT : alerte si le topic n'est pas vide
 */
@Slf4j
@Configuration
public class KafkaErrorConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * KafkaTemplate pour le DLT — envoie les messages en erreur sur le topic .DLT
     * Utilise StringSerializer car les messages arrivent déjà en String.
     */
    @Bean
    public KafkaTemplate<String, String> dltKafkaTemplate() {
        ProducerFactory<String, String> pf = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
        return new KafkaTemplate<>(pf);
    }

    /**
     * Error handler global pour tous les @KafkaListener.
     *
     * FixedBackOff(1000, 2) = 2 retries avec 1s d'intervalle.
     * Après 3 tentatives (1 initiale + 2 retries) → message envoyé au DLT.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> dltKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltKafkaTemplate);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));

        log.info("🛡️ Kafka DLT configuré : 2 retries → Dead Letter Topic");
        return errorHandler;
    }
}
