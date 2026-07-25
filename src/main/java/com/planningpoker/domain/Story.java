package com.planningpoker.domain;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "stories")
public class Story {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private Session session;

    // title, description and referenceUrl arrive already encrypted by the
    // client and are stored verbatim — the server holds no key for them and
    // never reads their contents (see docs/PRIVACY.md)
    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "reference_url", columnDefinition = "text")
    private String referenceUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StoryStatus status = StoryStatus.PENDING;

    // a card value like a vote, so encrypted at rest with the server-held key
    @Convert(converter = com.planningpoker.crypto.EncryptedStringConverter.class)
    @Column(name = "final_estimate", columnDefinition = "text")
    private String finalEstimate;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getReferenceUrl() {
        return referenceUrl;
    }

    public void setReferenceUrl(String referenceUrl) {
        this.referenceUrl = referenceUrl;
    }

    public StoryStatus getStatus() {
        return status;
    }

    public void setStatus(StoryStatus status) {
        this.status = status;
    }

    public String getFinalEstimate() {
        return finalEstimate;
    }

    public void setFinalEstimate(String finalEstimate) {
        this.finalEstimate = finalEstimate;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }
}
