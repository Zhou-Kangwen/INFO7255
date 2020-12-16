package com.info7255.InfoDemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;

@SpringBootApplication
@Configuration
public class InfoDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(InfoDemoApplication.class, args);
	}

}
