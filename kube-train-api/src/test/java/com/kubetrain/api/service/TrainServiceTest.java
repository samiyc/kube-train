package com.kubetrain.api.service;

import com.kubetrain.api.dto.CreateReservationRequest;
import com.kubetrain.api.dto.ReservationResponse;
import com.kubetrain.api.entity.Reservation;
import com.kubetrain.api.event.ReservationEvent;
import com.kubetrain.api.event.ReservationEventPublisher;
import com.kubetrain.api.exception.TrainNotFoundException;
import com.kubetrain.api.repository.ReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de TrainService — aucun contexte Spring, uniquement Mockito.
 *
 * 🎯 Stratégie :
 *  - Profil default (mémoire) : service construit sans repository injecté
 *  - Profil postgres (BDD) : repository injecté via ReflectionTestUtils.setField()
 *    pour simuler l'injection @Autowired(required=false) de Spring
 */
class TrainServiceTest {

    private ReservationEventPublisher eventPublisher;
    private ReservationRepository reservationRepository;
    private TrainService service;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ReservationEventPublisher.class);
        reservationRepository = mock(ReservationRepository.class);
        service = new TrainService(eventPublisher, new SimpleMeterRegistry());
        // reservationRepository = null par défaut → branche mémoire active
    }

    // ==================== createReservation — branche repository (profil postgres) ====================

    @Nested
    @DisplayName("createReservation — profil postgres (repository injecté)")
    class CreateReservationWithRepositoryTests {

        @BeforeEach
        void injectRepository() {
            ReflectionTestUtils.setField(service, "reservationRepository", reservationRepository);
        }

        @Test
        @DisplayName("Persiste la réservation en BDD via reservationRepository.save()")
        void shouldPersistToDatabase() {
            var request = new CreateReservationRequest("Jean Dupont", "TGV-7042");

            ReservationResponse response = service.createReservation(request);

            ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationRepository).save(captor.capture());
            Reservation saved = captor.getValue();

            assertThat(saved.getReservationId()).isEqualTo(response.reservationId());
            assertThat(saved.getStatus()).isEqualTo("CONFIRMED");
            assertThat(saved.getTrainId()).isEqualTo("TGV-7042");
            assertThat(saved.getPassengerName()).isEqualTo("Jean Dupont");
            assertThat(saved.getPrice()).isEqualByComparingTo(new BigDecimal("29.90"));
            assertThat(saved.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Publie un ReservationEvent après la persistance")
        void shouldPublishEventAfterSave() {
            service.createReservation(new CreateReservationRequest("Marie Curie", "TER-2814"));

            ArgumentCaptor<ReservationEvent> captor = ArgumentCaptor.forClass(ReservationEvent.class);
            verify(eventPublisher).publish(captor.capture());
            ReservationEvent event = captor.getValue();

            assertThat(event.trainId()).isEqualTo("TER-2814");
            assertThat(event.passengerName()).isEqualTo("Marie Curie");
            assertThat(event.price()).isEqualByComparingTo(new BigDecimal("15.50"));
            assertThat(event.eventId()).isNotBlank();
            assertThat(event.reservationId()).startsWith("RES-");
        }
    }

    // ==================== getReservation — branche repository (profil postgres) ====================

    @Nested
    @DisplayName("getReservation — profil postgres (repository injecté)")
    class GetReservationWithRepositoryTests {

        @BeforeEach
        void injectRepository() {
            ReflectionTestUtils.setField(service, "reservationRepository", reservationRepository);
        }

        @Test
        @DisplayName("Retourne la réservation mappée depuis l'entité BDD")
        void shouldReturnReservationFromDatabase() {
            var entity = Reservation.builder()
                    .reservationId("RES-ABCD1234")
                    .status("CONFIRMED")
                    .trainId("IC-6734")
                    .wagon("Wagon 3")
                    .departureTime(Instant.now())
                    .price(new BigDecimal("22.00"))
                    .build();
            when(reservationRepository.findById("RES-ABCD1234")).thenReturn(Optional.of(entity));

            ReservationResponse result = service.getReservation("RES-ABCD1234");

            assertThat(result.reservationId()).isEqualTo("RES-ABCD1234");
            assertThat(result.status()).isEqualTo("CONFIRMED");
            assertThat(result.trainId()).isEqualTo("IC-6734");
            assertThat(result.wagon()).isEqualTo("Wagon 3");
            assertThat(result.price()).isEqualByComparingTo(new BigDecimal("22.00"));
        }

        @Test
        @DisplayName("Lève TrainNotFoundException si la réservation est absente de la BDD")
        void shouldThrowWhenNotFoundInDatabase() {
            when(reservationRepository.findById("RES-UNKNOWN")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getReservation("RES-UNKNOWN"))
                    .isInstanceOf(TrainNotFoundException.class);
        }
    }

    // ==================== getReservation — branche mémoire (profil default) ====================

    @Nested
    @DisplayName("getReservation — profil default (mémoire)")
    class GetReservationInMemoryTests {

        @Test
        @DisplayName("Retourne la réservation stockée en mémoire après création")
        void shouldReturnReservationFromMemory() {
            ReservationResponse created = service.createReservation(
                    new CreateReservationRequest("Alice Dupont", "TGV-7042"));

            ReservationResponse found = service.getReservation(created.reservationId());

            assertThat(found.reservationId()).isEqualTo(created.reservationId());
            assertThat(found.status()).isEqualTo("CONFIRMED");
            assertThat(found.trainId()).isEqualTo("TGV-7042");
        }

        @Test
        @DisplayName("Lève TrainNotFoundException si la réservation est absente du cache mémoire")
        void shouldThrowWhenNotFoundInMemory() {
            assertThatThrownBy(() -> service.getReservation("RES-INEXISTANT"))
                    .isInstanceOf(TrainNotFoundException.class);
        }
    }
}
