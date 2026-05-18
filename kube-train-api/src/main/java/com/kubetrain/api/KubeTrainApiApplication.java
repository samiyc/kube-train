package com.kubetrain.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KubeTrainApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(KubeTrainApiApplication.class, args);
	}

}
