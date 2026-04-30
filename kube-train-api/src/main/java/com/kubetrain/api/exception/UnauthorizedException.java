package com.kubetrain.api.exception;

/**
 * Levée quand la clé API est manquante ou invalide.
 * → Interceptée par GlobalExceptionHandler → 401 UNAUTHORIZED
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
