package com.kubetrain.notification;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.Map;

/**
 * Propagation du contexte de trace OpenTelemetry (W3C {@code traceparent}) à travers Pub/Sub.
 *
 * 🎯 Côté CONSUMER : on « extrait » le contexte de trace des attributs du message
 *  (injecté par kube-train-api) puis on démarre le span de traitement AVEC ce parent →
 *  la trace est continue : HTTP POST /reservations → publish → process → envoi email.
 *
 * 🎯 Kafka vs Pub/Sub :
 *  - Kafka : l'agent OTel propage déjà le contexte via les headers (rien à faire).
 *  - Pub/Sub : non auto-instrumenté → extraction manuelle ici.
 *
 * ⚠️ Sans agent OTel actif (tests) → {@code GlobalOpenTelemetry} est no-op → spans no-op,
 *  contexte inchangé. Dégradation propre, aucun impact sur la logique métier.
 */
final class TracePropagation {

    private static final String INSTRUMENTATION_SCOPE = "com.kubetrain.notification";

    /** Getter W3C : lit les clés de propagation depuis les attributs (Map) du message Pub/Sub. */
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private TracePropagation() {
    }

    /** Extrait le contexte de trace ({@code traceparent}) des attributs d'un message Pub/Sub. */
    static Context extract(Map<String, String> attributes) {
        return GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), attributes, GETTER);
    }

    /** Démarre un span CONSUMER « notification process » rattaché au contexte parent extrait. */
    static Span startProcessSpan(Context parent) {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE)
                .spanBuilder("notification process")
                .setParent(parent)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();
    }

    /**
     * Exécute l'action (envoi d'email simulé) dans un span enfant nommé « notification send-email ».
     * Rend l'étape « jusqu'à l'envoi du mail » visible dans la trace, sur Kafka comme sur Pub/Sub.
     */
    static void sendEmailSpan(String reservationId, Runnable action) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE)
                .spanBuilder("notification send-email")
                .setAttribute("reservation.id", reservationId)
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            action.run();
        } finally {
            span.end();
        }
    }
}
