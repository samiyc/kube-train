package com.kubetrain.api.controller;

import com.kubetrain.api.config.EventPublisherConfig;
import com.kubetrain.api.service.TrainService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests du TrainController avec MockMvc.
 *
 * @WebMvcTest charge UNIQUEMENT la couche web (pas la BDD, pas les services sauf importés).
 * C'est plus rapide qu'un @SpringBootTest complet.
 *
 * 🎯 Bonnes pratiques de test :
 *  - Tester les cas nominaux ET les cas d'erreur
 *  - Vérifier le status code, le content-type ET le body
 *  - Utiliser @Nested pour grouper les tests par endpoint
 *  - @DisplayName pour des noms lisibles dans le rapport
 */
@WebMvcTest(TrainController.class)
@Import({TrainService.class, EventPublisherConfig.class})
class TrainControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ==================== GET / ====================

    @Nested
    @DisplayName("GET / — Page d'accueil")
    class WelcomeTests {

        @Test
        @DisplayName("Retourne 200 avec le message et le nom du pod")
        void shouldReturnWelcome() throws Exception {
            mockMvc.perform(get("/"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.wagon").exists());
        }
    }

    // ==================== GET /trains ====================

    @Nested
    @DisplayName("GET /trains — Liste des trains")
    class ListTrainsTests {

        @Test
        @DisplayName("Retourne 200 avec la liste des trains")
        void shouldReturnAllTrains() throws Exception {
            mockMvc.perform(get("/trains"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[*].id", hasItems("TGV-7042", "TER-2814", "IC-6734")));
        }
    }

    // ==================== GET /trains/{id} ====================

    @Nested
    @DisplayName("GET /trains/{id} — Détail d'un train")
    class GetTrainTests {

        @Test
        @DisplayName("200 — Train existant")
        void shouldReturnTrain() throws Exception {
            mockMvc.perform(get("/trains/TGV-7042"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("TGV-7042"))
                    .andExpect(jsonPath("$.origin").value("Paris Gare du Nord"))
                    .andExpect(jsonPath("$.price").value(29.90));
        }

        @Test
        @DisplayName("404 — Train inexistant → ProblemDetail")
        void shouldReturn404ForUnknownTrain() throws Exception {
            mockMvc.perform(get("/trains/FAKE-999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Ressource introuvable"))
                    .andExpect(jsonPath("$.detail").value("Train introuvable : FAKE-999"))
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // ==================== POST /reservations ====================

    @Nested
    @DisplayName("POST /reservations — Créer une réservation")
    class CreateReservationTests {

        @Test
        @DisplayName("201 — Réservation créée avec header Location")
        void shouldCreateReservation() throws Exception {
            String body = """
                    {
                        "passengerName": "Jean Dupont",
                        "trainId": "TGV-7042"
                    }
                    """;

            mockMvc.perform(post("/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.reservationId").exists())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.trainId").value("TGV-7042"))
                    .andExpect(jsonPath("$.price").value(29.90));
        }

        @Test
        @DisplayName("400 — Body vide")
        void shouldReturn400ForEmptyBody() throws Exception {
            mockMvc.perform(post("/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Données invalides"))
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("400 — Nom du passager trop court")
        void shouldReturn400ForShortName() throws Exception {
            String body = """
                    {
                        "passengerName": "J",
                        "trainId": "TGV-7042"
                    }
                    """;

            mockMvc.perform(post("/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString("passengerName")));
        }

        @Test
        @DisplayName("404 — Train inexistant dans la réservation")
        void shouldReturn404ForUnknownTrainInReservation() throws Exception {
            String body = """
                    {
                        "passengerName": "Jean Dupont",
                        "trainId": "FAKE-999"
                    }
                    """;

            mockMvc.perform(post("/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Train introuvable : FAKE-999"));
        }
    }

    // ==================== GET /secure ====================

    @Nested
    @DisplayName("GET /secure — Zone sécurisée")
    class SecureTests {

        @Test
        @DisplayName("401 — Sans header X-API-KEY")
        void shouldReturn401WithoutApiKey() throws Exception {
            mockMvc.perform(get("/secure"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.title").value("Accès refusé"))
                    .andExpect(jsonPath("$.detail").value("Header X-API-KEY manquant"));
        }

        @Test
        @DisplayName("401 — Avec mauvaise clé")
        void shouldReturn401WithWrongApiKey() throws Exception {
            mockMvc.perform(get("/secure")
                            .header("X-API-KEY", "mauvaise-cle"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Clé API invalide"));
        }

        @Test
        @DisplayName("200 — Avec la bonne clé (valeur par défaut)")
        void shouldReturn200WithCorrectApiKey() throws Exception {
            // La valeur par défaut de train.api.key est "Pas de clé" (cf @Value)
            mockMvc.perform(get("/secure")
                            .header("X-API-KEY", "Pas de clé"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("🔐 Accès autorisé à la zone sécurisée"));
        }
    }
}
