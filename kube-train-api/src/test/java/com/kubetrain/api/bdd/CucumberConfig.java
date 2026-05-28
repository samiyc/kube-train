package com.kubetrain.api.bdd;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
public class CucumberConfig {
	// Pas de code nécessaire — les annotations suffisent
	// Cucumber utilise cette classe pour démarrer le contexte Spring Boot Test
}