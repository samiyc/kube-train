package com.kubetrain.api.controller;

import com.kubetrain.api.dto.*;
import com.kubetrain.api.exception.UnauthorizedException;
import com.kubetrain.api.service.TrainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Controller principal de l'API Kube-Train.
 *
 * 🎯 Bonnes pratiques REST appliquées ici :
 *  - GET  = lecture (idempotent, cacheable)
 *  - POST = création (non-idempotent)
 *  - ResponseEntity<T> pour contrôler le status code et les headers
 *  - @Valid pour valider le body avant d'entrer dans la méthode
 *  - ProblemDetail (RFC 9457) pour les erreurs (via GlobalExceptionHandler)
 *
 * 🎯 Question piège entretien :
 *  "Quelle est la différence entre @Controller et @RestController ?"
 *  → @RestController = @Controller + @ResponseBody sur chaque méthode
 *  → Le retour est sérialisé en JSON (via Jackson), pas résolu comme un nom de vue
 */
@RestController
@Tag(name = "Trains", description = "API de gestion des trains et réservations")
public class TrainController {

    private final TrainService trainService;

    @Value("${train.message:Message par défaut}")
    private String welcomeMessage;

    @Value("${train.api.key:Pas de clé}")
    private String apiKey;

    // Injection par constructeur (recommandé vs @Autowired sur le champ)
    public TrainController(TrainService trainService) {
        this.trainService = trainService;
    }

    // ==================== Page d'accueil ====================

    @Operation(summary = "Page d'accueil", description = "Retourne le message de la ConfigMap et le nom du pod")
    @ApiResponse(responseCode = "200", description = "Message d'accueil")
    @GetMapping("/")
    public ResponseEntity<WelcomeResponse> welcome() {
        String podName = resolvePodName();
        return ResponseEntity.ok(new WelcomeResponse(welcomeMessage, podName));
    }

    // ==================== Trains (lecture) ====================

    @Operation(summary = "Lister les trains", description = "Retourne la liste de tous les trains disponibles")
    @ApiResponse(responseCode = "200", description = "Liste des trains")
    @GetMapping("/trains")
    public ResponseEntity<List<TrainResponse>> listTrains() {
        return ResponseEntity.ok(trainService.getAllTrains());
    }

    @Operation(summary = "Détail d'un train", description = "Retourne les informations d'un train par son identifiant")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Train trouvé"),
            @ApiResponse(responseCode = "404", description = "Train introuvable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/trains/{id}")
    public ResponseEntity<TrainResponse> getTrain(
            @Parameter(description = "Identifiant du train", example = "TGV-7042")
            @PathVariable String id) {
        return ResponseEntity.ok(trainService.getTrainById(id));
    }

    // ==================== Réservations (écriture) ====================

    @Operation(summary = "Réserver un billet", description = "Crée une réservation pour un train donné")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Réservation créée",
                    headers = @Header(name = "Location", description = "URL de la réservation créée")),
            @ApiResponse(responseCode = "400", description = "Données invalides",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Train introuvable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> createReservation(
            @Valid @RequestBody CreateReservationRequest request) {
        ReservationResponse reservation = trainService.createReservation(request);

        // 201 Created + header Location (bonne pratique REST pour les créations)
        return ResponseEntity
                .created(URI.create("/reservations/" + reservation.reservationId()))
                .body(reservation);
    }

    @Operation(summary = "Consulter une réservation")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Réservation trouvée"),
            @ApiResponse(responseCode = "404", description = "Réservation introuvable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/reservations/{id}")
    public ResponseEntity<ReservationResponse> getReservation(
            @Parameter(description = "Identifiant de la réservation", example = "RES-A1B2C3D4")
            @PathVariable String id) {
        return ResponseEntity.ok(trainService.getReservation(id));
    }

    // ==================== Zone sécurisée ====================

    @Operation(summary = "Zone sécurisée",
            description = "Endpoint protégé par clé API. Envoyer le header X-API-KEY.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accès autorisé"),
            @ApiResponse(responseCode = "401", description = "Clé API manquante ou invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/secure")
    public ResponseEntity<WelcomeResponse> secureZone(
            @Parameter(description = "Clé API d'authentification", required = true)
            @RequestHeader(value = "X-API-KEY", required = false) String providedKey) {

        // Phase A : validation manuelle dans le controller
        // Phase B (future) : déplacer dans un Filter ou Spring Security
        if (providedKey == null || providedKey.isBlank()) {
            throw new UnauthorizedException("Header X-API-KEY manquant");
        }
        if (!providedKey.equals(apiKey)) {
            throw new UnauthorizedException("Clé API invalide");
        }

        return ResponseEntity.ok(new WelcomeResponse(
                "🔐 Accès autorisé à la zone sécurisée",
                resolvePodName()
        ));
    }

    // ==================== Utilitaires ====================

    /**
     * Résout le nom du pod via le hostname.
     * Fallback sur la variable d'env HOSTNAME si la résolution DNS échoue
     * (plus fiable dans les conteneurs K8s).
     */
    private String resolvePodName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return System.getenv().getOrDefault("HOSTNAME", "unknown");
        }
    }
}
