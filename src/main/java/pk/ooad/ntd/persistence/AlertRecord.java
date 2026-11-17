package pk.ooad.ntd.persistence;

import pk.ooad.ntd.domain.Alert;

import java.time.Instant;

public record AlertRecord(String id, Instant timestamp, String severity, String attackType,
                          String sourceIp, String targetIp, String detectorName, String description,
                          boolean simulated) {
    public static AlertRecord from(Alert alert) {
        return new AlertRecord(alert.getId(), alert.getTimestamp(), alert.getSeverity().name(),
                alert.getAttackType().name(), alert.getSourceIp(), alert.getTargetIp(),
                alert.getDetectorName(), alert.getDescription(), alert.isSimulated());
    }
}
