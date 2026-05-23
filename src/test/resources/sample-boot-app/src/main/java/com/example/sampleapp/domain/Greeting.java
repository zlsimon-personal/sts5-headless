package com.example.sampleapp.domain;

import org.springframework.data.annotation.Id;

/**
 * Domain type for the Spring Data repository. No {@code equals}/
 * {@code hashCode}: this fixture exists only for the language server's
 * static project model — it is never persisted or compared at runtime.
 */
public class Greeting {

    @Id
    private String id;
    private String text;

    public Greeting() {
    }

    public Greeting(String id, String text) {
        this.id = id;
        this.text = text;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
