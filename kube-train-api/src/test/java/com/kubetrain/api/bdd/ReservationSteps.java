package com.kubetrain.api.bdd;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class ReservationSteps {

    @Autowired
    private MockMvc mockMvc;

    // Etat partage entre les etapes d un meme scenario (Given → When → Then)
    private ResultActions resultActions;

    // ==================== GIVEN ====================

    @Given("le train {string} existe avec des places disponibles")
    public void leTrainExisteAvecDesPlaces(String trainId) throws Exception {
        // Precondition : verifie que le train est accessible
        // Ne stocke PAS dans resultActions — ce n est pas le resultat a verifier dans le Then
        mockMvc.perform(get("/trains/{id}", trainId))
               .andExpect(status().isOk());
    }

    // ==================== WHEN ====================

    @When("je reserve un billet pour {string} sur le train {string}")
    public void jeReserveUnBillet(String passengerName, String trainId) throws Exception {
        this.resultActions = mockMvc.perform(post("/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"passengerName": "%s", "trainId": "%s"}
                        """.formatted(passengerName, trainId)));
    }

    @When("je consulte la liste des trains")
    public void jeConsulteLaListeDesTrens() throws Exception {
        this.resultActions = mockMvc.perform(get("/trains"));
    }

    // ==================== THEN ====================

    @Then("la reponse HTTP est {int}")
    public void laReponseHTTPEst(int statusCode) throws Exception {
        resultActions.andExpect(status().is(statusCode));
    }

    @And("la reservation a le statut {string}")
    public void laReservationALeStatut(String expectedStatus) throws Exception {
        resultActions.andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @And("le prix est de {double}")
    public void lePrixEstDe(double expectedPrice) throws Exception {
        resultActions.andExpect(jsonPath("$.price").value(expectedPrice));
    }

    @And("la liste contient {int} trains")
    public void laListeContientNTrains(int count) throws Exception {
        resultActions.andExpect(jsonPath("$.length()").value(count));
    }
}