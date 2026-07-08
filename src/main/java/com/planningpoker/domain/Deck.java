package com.planningpoker.domain;

import jakarta.persistence.*;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "decks")
public class Deck {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Convert(converter = StringListConverter.class)
    @Column(name = "card_values", nullable = false, columnDefinition = "text")
    private List<String> values;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getValues() {
        return values;
    }

    public void setValues(List<String> values) {
        this.values = values;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }
}
