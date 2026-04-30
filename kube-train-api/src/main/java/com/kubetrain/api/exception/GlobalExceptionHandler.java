package com.kubetrain.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Gestion centralisée des exceptions — Toutes les erreurs passent ici.
 *
 * Utilise ProblemDetail (RFC 9457) : le standard moderne Spring pour les réponses d'erreur.
 * Avantages vs un ErrorResponse custom :
 *  - Format standardisé (type, title, status, detail, instance)
 *  - Supporté nativement par Spring Boot (pas besoin de DTO custom)
 *  - Interopérable avec d'autres frameworks/langages qui respectent la RFC
 *
 * 🎯 Question piège entretien :
 *  "Comment gérez-vous les erreurs dans Spring ?"
 *  → @RestControllerAdvice + @ExceptionHandler + ProblemDetail (RFC 9457)
 *  → JAMAIS de stack trace exposée au client (faille de sécurité)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 404 — Ressource non trouvée
     */
    @ExceptionHandler(TrainNotFoundException.class)
    public ProblemDetail handleNotFound(TrainNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Ressource introuvable");
        problem.setType(URI.create("https://api.kube-train.local/errors/not-found"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * 401 — Non autorisé (clé API invalide ou manquante)
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Accès refusé");
        problem.setType(URI.create("https://api.kube-train.local/errors/unauthorized"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * 400 — Validation échouée (@Valid sur le body)
     *
     * Quand Spring reçoit un body invalide (champ manquant, trop court, etc.),
     * il lève MethodArgumentNotValidException AVANT que le code du controller ne s'exécute.
     * On récupère les messages d'erreur de chaque champ pour les renvoyer au client.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errors);
        problem.setTitle("Données invalides");
        problem.setType(URI.create("https://api.kube-train.local/errors/validation"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * 500 — Catch-all pour les erreurs non prévues.
     *
     * ⚠️ JAMAIS exposer ex.getMessage() ou la stack trace au client !
     * En prod, ça peut révéler la structure de la BDD, des chemins internes, etc.
     * On log l'erreur côté serveur et on renvoie un message générique.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        // En prod : logger l'erreur complète (Sentry, Cloud Logging, etc.)
        // log.error("Erreur interne non gérée", ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Une erreur interne est survenue. Contactez l'administrateur."
        );
        problem.setTitle("Erreur serveur");
        problem.setType(URI.create("https://api.kube-train.local/errors/internal"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
