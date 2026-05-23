package com.example.sampleapp.repo;

import com.example.sampleapp.domain.Greeting;
import org.springframework.data.repository.CrudRepository;

/**
 * A real Spring Data repository interface (extends {@link CrudRepository}
 * from spring-data-commons). Spring Tools detects and models Spring Data
 * repositories by their interface hierarchy, statically — no enabling
 * module or datasource is needed for it to appear in the bean graph,
 * which keeps this fixture deterministic and DB-free.
 */
public interface GreetingRepository extends CrudRepository<Greeting, String> {
}
