package pk.ooad.ntd.web;

import org.junit.jupiter.api.Test;
import pk.ooad.ntd.domain.AttackType;
import pk.ooad.ntd.domain.LowAlert;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamBroadcasterTest {
    @Test
    void plainAlertTriggersAlertAndNetworkEncodingWithoutStoringHistory() {
        StreamBroadcaster broadcaster = new StreamBroadcaster();
        AtomicReference<String> encodedAlert = new AtomicReference<>();
        AtomicInteger networkFrames = new AtomicInteger();
        broadcaster.setEncoders(alert -> {
            encodedAlert.set(alert.getId());
            return "{}";
        }, () -> {
            networkFrames.incrementAndGet();
            return "{}";
        }, (username, role) -> "{}");
        try {
            broadcaster.onAlert(null);
            broadcaster.addClient(null);
            broadcaster.removeClient(null);
            assertEquals(null, encodedAlert.get());
            assertEquals(0, networkFrames.get());
            broadcaster.onAlert(new LowAlert("a1", Instant.EPOCH, AttackType.PORT_SCAN,
                    "source", "target", "PortScanDetector", "Scan detected"));
            assertEquals("a1", encodedAlert.get());
            assertEquals(1, networkFrames.get());
            assertEquals(0, broadcaster.getClientCount());
            assertEquals(0, broadcaster.getEmittedFrames());
        } finally {
            broadcaster.close();
        }
    }
}
