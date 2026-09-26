package io.julienmetral.tasks.identity.services;

/**
 * @param skipped    another instance held the retention lock, so nothing was done
 * @param anonymized deleted users whose personal data was erased
 * @param warned     inactive accounts that were sent the deletion warning
 * @param deleted    warned accounts deleted because they stayed inactive
 */
public record UserRetentionReport(
        boolean skipped,
        int anonymized,
        int warned,
        int deleted
) {

    public static UserRetentionReport skippedRun() {
        return new UserRetentionReport(true, 0, 0, 0);
    }
}
