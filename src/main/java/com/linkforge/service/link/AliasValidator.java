package com.linkforge.service.link;

import com.linkforge.domain.link.exception.InvalidAliasException;
import java.util.regex.Pattern;

/**
 * Dedicated validator for custom link aliases enforcing length bounds and character constraints.
 */
public final class AliasValidator {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 30;
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,30}$");

    private AliasValidator() {}

    public static void validate(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new InvalidAliasException("Custom alias cannot be null or blank.");
        }
        if (alias.length() < MIN_LENGTH || alias.length() > MAX_LENGTH || !ALIAS_PATTERN.matcher(alias).matches()) {
            throw new InvalidAliasException("Custom alias must be between " + MIN_LENGTH + " and " + MAX_LENGTH
                    + " characters and contain only alphanumeric characters, underscores, or hyphens.");
        }
    }
}
