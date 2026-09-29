package io.julienmetral.tasks.identity.services;

/**
 * @param anonymized deleted users whose personal data was erased
 * @param warned     inactive accounts marked for deletion; only the enabled ones are emailed
 * @param deleted    warned accounts deleted because they stayed inactive
 */
public record UserRetentionReport(
        int anonymized,
        int warned,
        int deleted
) {
}
