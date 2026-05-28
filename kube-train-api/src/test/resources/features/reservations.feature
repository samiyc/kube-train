Feature: Test sur les apis de reservation et de consultation des trains
  1) Reservation reussie - TrainId OK
  2) Train inexistant - TrainId KO
  3) Consulter la liste des trains

  Scenario: Reservation reussie
    Given le train "TGV-7042" existe avec des places disponibles
    When je reserve un billet pour "Jean Dupont" sur le train "TGV-7042"
    Then la reponse HTTP est 201
    And la reservation a le statut "CONFIRMED"
    And le prix est de 29.90

  Scenario: Train inexistant
    When je reserve un billet pour "Jean Dupont" sur le train "FAKE-9999"
    Then la reponse HTTP est 404

  Scenario: Consulter la liste des trains
    When je consulte la liste des trains
    Then la reponse HTTP est 200
    And la liste contient 3 trains