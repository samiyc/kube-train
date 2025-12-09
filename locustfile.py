from locust import HttpUser, task, between

class TrainUser(HttpUser):
    # Temps d'attente aléatoire entre chaque action (1 à 3 secondes)
    wait_time = between(1, 3)

    @task(3) # Poids 3 : Tâche fréquente
    def view_welcome(self):
        self.client.get("/")

    @task(1) # Poids 1 : Tâche plus rare
    def book_ticket(self):
        # On tape sur l'endpoint de réservation
        self.client.get("/reserver")
