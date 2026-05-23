package com.example.sampleapp.service;

import com.example.sampleapp.domain.Greeting;
import com.example.sampleapp.repo.GreetingRepository;
import org.springframework.stereotype.Service;

/**
 * Business logic bean. Constructor-injects {@link GreetingRepository}
 * (a Spring Data repository) — a concrete injection point for the MCP
 * bean-graph tools to report.
 */
@Service
public class GreetingService {

    private final GreetingRepository repository;

    public GreetingService(GreetingRepository repository) {
        this.repository = repository;
    }

    public String greet(String name) {
        return repository.findById(name)
                .map(Greeting::getText)
                .orElse("Hello, %s!".formatted(name));
    }
}
