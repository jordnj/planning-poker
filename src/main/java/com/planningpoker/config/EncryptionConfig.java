package com.planningpoker.config;

import com.planningpoker.crypto.EncryptionKeys;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Installs the at-rest data key before any entity is read or written.
 * Runs during context startup, ahead of the first repository call.
 */
@Configuration
public class EncryptionConfig {

    private final AppProperties props;

    public EncryptionConfig(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    void installKeys() {
        EncryptionKeys.install(props.dataKey());
    }
}
