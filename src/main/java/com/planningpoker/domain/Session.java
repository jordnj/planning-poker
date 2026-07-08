package com.planningpoker.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class Session {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 16)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "deck_id")
    private Deck deck;

    @Column(name = "facilitator_participant_id")
    private UUID facilitatorParticipantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status = SessionStatus.ACTIVE;

    @Column(name = "auto_reveal", nullable = false)
    private boolean autoReveal;

    @Column(name = "allow_observers", nullable = false)
    private boolean allowObservers = true;

    @Column(name = "allow_vote_change", nullable = false)
    private boolean allowVoteChange = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastActivityAt == null) {
            lastActivityAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Deck getDeck() {
        return deck;
    }

    public void setDeck(Deck deck) {
        this.deck = deck;
    }

    public UUID getFacilitatorParticipantId() {
        return facilitatorParticipantId;
    }

    public void setFacilitatorParticipantId(UUID facilitatorParticipantId) {
        this.facilitatorParticipantId = facilitatorParticipantId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public boolean isAutoReveal() {
        return autoReveal;
    }

    public void setAutoReveal(boolean autoReveal) {
        this.autoReveal = autoReveal;
    }

    public boolean isAllowObservers() {
        return allowObservers;
    }

    public void setAllowObservers(boolean allowObservers) {
        this.allowObservers = allowObservers;
    }

    public boolean isAllowVoteChange() {
        return allowVoteChange;
    }

    public void setAllowVoteChange(boolean allowVoteChange) {
        this.allowVoteChange = allowVoteChange;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void touch() {
        this.lastActivityAt = Instant.now();
    }
}
