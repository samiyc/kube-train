package com.kubetrain.api.event;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.HashMap;
import java.util.Map;

/**
 * Propagation du contexte de trace OpenTelemetry (W3C {@code traceparent}) à travers Pub/Sub
 * <em>et</em> à travers la table outbox.
 *
 * <h3>Pourquoi manuel ?</h3>
 * L'agent OTel auto-instrumente Kafka (il injecte {@code traceparent} dans les headers),
 * mais PAS le client Pub/Sub bas-niveau. Sans injection manuelle, le span du consumer
 * démarre une NOUVELLE trace → on perd le lien API → notification.
 *
 * <h3>Les deux frontières franchies</h3>
 * <ol>
 *   <li><b>HTTP → outbox</b> : {@link #currentTraceAttributes()} capture le contexte de la
 *       requête et {@code TrainService} le persiste dans la ligne. Sans ça, le contexte meurt
 *       au commit, car {@code OutboxPoller} publie plus tard dans une autre trace.</li>
 *   <li><b>outbox → Pub/Sub → consumer</b> : {@code OutboxPoller} restaure le contexte via
 *       {@link #extract(Map)}, puis le publisher le réinjecte dans les attributs du message.</li>
 * </ol>
 *
 * <h3>Dégradation propre</h3>
 * Sans agent OTel actif (tests, Minikube), {@code GlobalOpenTelemetry} est no-op : la map
 * retournée reste vide et {@link #extract(Map)} renvoie le contexte courant inchangé.
 */
public final class TracePropagation {

    /** Setter W3C : écrit chaque clé de propagation (traceparent, tracestate) dans la map. */
    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

    /** Getter W3C : lit les clés de propagation depuis une map (ligne outbox ou message). */
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

    /**
     * Attributs de propagation ({@code traceparent}, éventuellement {@code tracestate}) du
     * contexte de trace courant. Utilisé à la fois pour les attributs du message Pub/Sub et
     * pour la persistance dans la ligne outbox.
     */
    public static Map<String, String> currentTraceAttributes() {
        Map<String, String> carrier = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), carrier, SETTER);
        return carrier;
    }

    /**
     * Reconstruit un contexte de trace depuis des attributs W3C.
     * Une map vide (ou sans {@code traceparent}) renvoie le contexte courant inchangé.
     */
    public static Context extract(Map<String, String> attributes) {
        return GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), attributes, GETTER);
    }
}
