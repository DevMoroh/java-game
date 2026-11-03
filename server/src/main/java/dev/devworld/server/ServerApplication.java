package dev.devworld.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ServerApplication {

	public static void main(String[] args) {
		TimeZoneFix.apply();
		SpringApplication.run(ServerApplication.class, args);
	}

}
