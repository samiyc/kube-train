package com.kubetrain.api;

import org.springframework.beans.factory.annotation.Value; // 👈 Import important
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.InetAddress;
import java.net.UnknownHostException;

@RestController
public class TrainController {

    // On injecte la valeur depuis la config Spring (application.properties ou Env Var)
    // Si la valeur n'existe pas, on met un défaut après les deux-points
    @Value("${train.message:Message par défaut}")
    private String welcomeMessage;

    @Value("${train.api.key:Pas de clé}")
    private String apiKey;

    @GetMapping("/")
    public String welcome() throws UnknownHostException {
        String podName = InetAddress.getLocalHost().getHostName();
        // On utilise la variable dynamique
        return welcomeMessage + " (Wagon : " + podName + ")";
    }

    @GetMapping("/reserver")
    public String book() {
        return "🎫 Billet réservé ! (1€ débité)";
    }

    @GetMapping("/secure")
    public String secureZone() {
        // En vrai, on ne l'affiche jamais ! C'est juste pour le TP.
        return "🔐 Zone Sécurisée. Clé utilisée : " + apiKey;
    }
}
