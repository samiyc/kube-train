package com.kubetrain.api.exception;

/**
 * Levée quand un train demandé n'existe pas.
 * → Interceptée par GlobalExceptionHandler → 404 NOT_FOUND
 */
public class TrainNotFoundException extends RuntimeException {
    public TrainNotFoundException(String trainId) {
        super("Train introuvable : " + trainId);
    }
}
