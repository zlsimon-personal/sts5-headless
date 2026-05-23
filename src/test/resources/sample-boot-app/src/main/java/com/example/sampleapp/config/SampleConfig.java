package com.example.sampleapp.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds one explicitly-declared {@code @Bean} method so the bean-graph
 * tools report a factory-method bean alongside the stereotype beans.
 */
@Configuration
public class SampleConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
