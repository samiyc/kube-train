package com.kubetrain.api;

import com.kubetrain.api.config.EventPublisherConfig;
import com.kubetrain.api.controller.TrainController;
import com.kubetrain.api.service.TrainService;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.context.WebApplicationContext;

/**
 * Classe de base pour les tests générés par Spring Cloud Contract.
 *
 * 🎯 Comment ça marche :
 *  1. On écrit des contrats YAML dans src/test/resources/contracts/
 *  2. Le plugin Maven génère des classes de test qui ÉTENDENT cette classe
 *  3. Chaque test vérifie que notre API respecte le contrat
 *
 * @WebMvcTest → charge uniquement le controller (pas toute l'app)
 * @Import → injecte le service + le NoOp publisher (pas de Kafka en test)
 * RestAssuredMockMvc → librairie qui fait les appels HTTP simulés
 */
@WebMvcTest(TrainController.class)
@Import({TrainService.class, EventPublisherConfig.class})
public abstract class BaseContractTest {

    @Autowired
    WebApplicationContext context;

    @BeforeEach
    void setup() {
        RestAssuredMockMvc.webAppContextSetup(context);
    }
}
