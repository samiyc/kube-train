package com.kubetrain.api.event;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.HashMap;
import java.util.Map;

/**
 * Propagation du contexte de trace OpenTelemetry (W3C {@code traceparent}) à travers Pub/Sub.
 *
 * 🎯 Pourquoi manuel ?
 *  L'agent OTel auto-instrumente Kafka (il injecte {@code traceparent} dans les headers),
 *  mais PAS le client Pub/Sub bas-niveau. Sans injection manuelle, le span du consumer
 *  démarre une NOUVELLE trace → on perd le lien API → notification.
 *
 * 🎯 Côté PRODUCER : on « injecte » le contexte de trace courant (traceId/spanId) dans les
 *  attributs du message. Le consumer pourra rattacher son span de traitement au même trace.
 *
 * ⚠️ Sans agent OTel actif (tests, Minikube) → {@code GlobalOpenTelemetry} est no-op →
 *  la map retournée reste vide, aucun effet de bord. Dégradation propre.
 */
final class TracePropagation {

    /** Setter W3C : écrit chaque clé de propagation (traceparent, tracestate) dans la map. */
    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

    private TracePropagation() {
    }

    /**
     * Attributs de propagation ({@code traceparent}, éventuellement {@code tracestate}) du
     * contexte de trace courant, à joindre aux attributs du message Pub/Sub.
     */
    static Map<String, String> currentTraceAttributes() {
        Map<String, String> carrier = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), carrier, SETTER);
        return carrier;
    }
}
