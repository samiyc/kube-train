Feature: Reservations et consultation des trains
  Tests BDD pour les endpoints /reservations et /trains.
  Les donnees de trains sont en memoire (TrainService.TRAINS).

  Scenario: Reservation reussie sur un train existant
    Given le train "TGV-7042" existe avec des places disponibles
    When je reserve un billet pour "Jean Dupont" sur le train "TGV-7042"
    Then la reponse HTTP est 201
    And la reservation a le statut "CONFIRMED"
    And le prix est de 29.90

  Scenario: Reservation echouee - train inexistant
    When je reserve un billet pour "Jean Dupont" sur le train "FAKE-9999"
    Then la reponse HTTP est 404

  Scenario: Consulter la liste des trains
    When je consulte la liste des trains
    Then la reponse HTTP est 200
    And la liste contient 3 trains

  Scenario Outline: Validation du statut HTTP par identifiant de train
    When je reserve un billet pour "Passager Test" sur le train "<trainId>"
    Then la reponse HTTP est <status>

    Examples:
      | trainId   | status |
      | TGV-7042  | 201    |
      | TER-2814  | 201    |
      | IC-6734   | 201    |
      | FAKE-9999 | 404    |