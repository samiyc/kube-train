package com.kubetrain.notification.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Test CONTRACT côté CONSUMER — vérifie que le notification-service
 * peut appeler kube-train-api selon les contrats définis par le producer.
 *
 * 🎯 Comment ça marche :
 *  1. kube-train-api est buildé avec `mvn install` → génère un JAR de stubs WireMock
 *  2. @AutoConfigureStubRunner télécharge ce JAR depuis le repo Maven local
 *  3. Un serveur WireMock démarre sur le port 8181 avec les stubs du producer
 *  4. Ce test appelle WireMock comme s'il appelait la vraie API
 *
 * 🎯 Valeur pédagogique :
 *  - Garantit que si le producer change l'API, les contrats cassent → fail CI
 *  - Permet de tester un consumer sans démarrer la vraie API
 *  - Découplage équipes : le consumer travaille avec les stubs, le producer déploie
 *    uniquement quand les contrats sont validés des deux côtés.
 *
 * ⚠️ Pré-requis : exécuter `mvn install -pl kube-train-api -DskipTests` au préalable
 *    pour installer le JAR de stubs dans le repo Maven local (~/.m2).
 *
 * Kafka est désactivé pour ce test (pas besoin de broker pour vérifier les contrats HTTP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("contract-test")
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
@AutoConfigureStubRunner(
    ids = "com.kubetrain:kube-train-api:0.0.1-SNAPSHOT:stubs:8181",
    stubsMode = StubRunnerProperties.StubsMode.LOCAL
)
class TrainApiContractConsumerTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String BASE_URL = "http://localhost:8181";

    @Test
    @DisplayName("GET /trains/{id} — retourne le train TGV-7042 (contrat producer)")
    @SuppressWarnings("unchecked")
    void shouldGetTrainByIdViaStub() {
        Map<String, Object> train = restTemplate.getForObject(
                BASE_URL + "/trains/TGV-7042", Map.class);

        assertThat(train).containsEntry("id", "TGV-7042");
        assertThat(train).containsEntry("origin", "Paris Gare du Nord");
        assertThat(train).containsEntry("destination", "Lille Europe");
        assertThat(train).containsKey("price");
    }

    @Test
    @DisplayName("GET /trains/{id} — retourne 404 pour un train inconnu (contrat producer)")
    void shouldReturn404ForUnknownTrainViaStub() {
        HttpClientErrorException.NotFound ex = catchThrowableOfType(
                HttpClientErrorException.NotFound.class,
                () -> restTemplate.getForObject(BASE_URL + "/trains/UNKNOWN-001", String.class));

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /reservations — crée une réservation (contrat producer)")
    @SuppressWarnings("unchecked")
    void shouldCreateReservationViaStub() {
        // Le contrat impose passengerEmail dans le corps de la requête
        var requestBody = Map.of(
                "passengerName", "Jean Dupont",
                "trainId", "TGV-7042",
                "passengerEmail", "jean@example.com");

        Map<String, Object> response = restTemplate.postForObject(
                BASE_URL + "/reservations", requestBody, Map.class);

        assertThat(response).isNotNull();
        assertThat(response).containsEntry("status", "CONFIRMED");
        assertThat(response).containsEntry("trainId", "TGV-7042");
        assertThat(response.get("reservationId").toString()).matches("RES-[A-Z0-9]{8}");
    }
}
