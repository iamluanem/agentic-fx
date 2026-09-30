package com.agenticfx.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FxApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FxApiApplication.class, args);
    }
}
