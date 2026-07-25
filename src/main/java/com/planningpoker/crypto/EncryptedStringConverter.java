package com.planningpoker.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Transparently encrypts a String column at rest. Applied explicitly with
 * {@code @Convert} on the fields that carry sensitive values, so nothing is
 * encrypted by accident and columns used for lookups stay queryable.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return EncryptionKeys.data().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return EncryptionKeys.data().decrypt(dbData);
    }
}
