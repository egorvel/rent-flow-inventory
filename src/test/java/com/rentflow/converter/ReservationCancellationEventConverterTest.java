package com.rentflow.converter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import com.rentflow.model.ReservationCancellationEvent;
import com.rentflow.model.ReservationCancellationFailureCode;
import com.rentflow.model.ReservationCancellationRejectedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationCancellationEventConverterTest {
    private static final String EVENT_ID = "d880f919-2b5c-4f7e-a56d-e047e7d932a6";
    private static final String SERIAL = "DRILL-001";
    private static final String VALID_JSON = """
            {"eventId":"%s","eventType":"ReservationCancelled","eventVersion":1,
             "occurredAt":"2026-09-15T15:30:00Z","serialNumber":"%s"}
            """.formatted(EVENT_ID, SERIAL);

    private final ReservationCancellationEventConverter converter = new ReservationCancellationEventConverter(4096);

    @Test
    void convertsTheExactContractAndCanonicalizesEquivalentJson() {
        ReservationCancellationEvent first = converter.convert(record(SERIAL, VALID_JSON));
        String equivalent = """
                {"unknown":{"ignored":true},"serialNumber":"DRILL-001",
                 "occurredAt":"2026-09-15T15:30:00.000Z","eventVersion":1,
                 "eventType":"ReservationCancelled","eventId":"D880F919-2B5C-4F7E-A56D-E047E7D932A6"}
                """;
        ReservationCancellationEvent second = converter.convert(record(SERIAL, equivalent));

        assertThat(first.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(first.occurredAt()).isEqualTo(Instant.parse("2026-09-15T15:30:00Z"));
        assertThat(first.serialNumber()).isEqualTo(SERIAL);
        assertThat(first.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(second.lockId()).isEqualTo(first.lockId());
    }

    @Test
    void acceptsTheMaximumPayloadAndSerialLength() {
        String serial = "S".repeat(64);
        String base = VALID_JSON.replace(SERIAL, serial).stripTrailing();
        String payload = base + " ".repeat(4096 - base.getBytes(StandardCharsets.UTF_8).length);

        assertThat(payload.getBytes(StandardCharsets.UTF_8)).hasSize(4096);
        assertThat(converter.convert(record(serial, payload)).serialNumber()).isEqualTo(serial);
    }

    @Test
    void rejectsInvalidPayloadsWithoutIncludingTheirContentInTheException() {
        List<String> invalidPayloads = List.of(
                "",
                "null",
                "[]",
                VALID_JSON + " {}",
                VALID_JSON.replace("\"eventType\":\"ReservationCancelled\",", ""),
                VALID_JSON.replace("\"eventVersion\":1", "\"eventVersion\":\"1\""),
                VALID_JSON.replace("\"occurredAt\":\"2026-09-15T15:30:00Z\"", "\"occurredAt\":\"bad\""),
                VALID_JSON.replace(SERIAL, "/INVALID"),
                VALID_JSON.replaceFirst("\\{", "{\"eventId\":\"" + EVENT_ID + "\","));

        for (String payload : invalidPayloads) {
            assertRejected(record(SERIAL, payload), ReservationCancellationFailureCode.INVALID_EVENT);
        }
        assertRejected(
                new ConsumerRecord<>("source", 0, 0, null, VALID_JSON.getBytes(StandardCharsets.UTF_8)),
                ReservationCancellationFailureCode.INVALID_EVENT);
        assertRejected(record("drill-001", VALID_JSON), ReservationCancellationFailureCode.INVALID_EVENT);
        assertRejected(
                new ConsumerRecord<>(
                        "source", 0, 0, new byte[] {(byte) 0xc3, 0x28}, VALID_JSON.getBytes(StandardCharsets.UTF_8)),
                ReservationCancellationFailureCode.INVALID_EVENT);
        assertRejected(
                new ConsumerRecord<>(
                        "source", 0, 0, SERIAL.getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xc3, 0x28}),
                ReservationCancellationFailureCode.INVALID_EVENT);
    }

    @Test
    void distinguishesUnsupportedContractsAndPreservesAParseableEventId() {
        for (String payload : List.of(
                VALID_JSON.replace("ReservationCancelled", "ReservationCreated"),
                VALID_JSON.replace("\"eventVersion\":1", "\"eventVersion\":2"))) {
            assertThatThrownBy(() -> converter.convert(record(SERIAL, payload)))
                    .isInstanceOfSatisfying(ReservationCancellationRejectedException.class, exception -> {
                        assertThat(exception.getFailureCode())
                                .isEqualTo(ReservationCancellationFailureCode.UNSUPPORTED_EVENT);
                        assertThat(exception.getEventId()).contains(UUID.fromString(EVENT_ID));
                        assertThat(exception.getMessage()).doesNotContain(payload, SERIAL);
                    });
        }
    }

    @Test
    void preservesTheValidatedEventIdWhenALaterFieldIsInvalid() {
        String payload = VALID_JSON.replace("2026-09-15T15:30:00Z", "invalid");

        assertThatThrownBy(() -> converter.convert(record(SERIAL, payload)))
                .isInstanceOfSatisfying(ReservationCancellationRejectedException.class, exception -> {
                    assertThat(exception.getFailureCode()).isEqualTo(ReservationCancellationFailureCode.INVALID_EVENT);
                    assertThat(exception.getEventId()).contains(UUID.fromString(EVENT_ID));
                });
    }

    @Test
    void rejectsNonV4IdentifiersAndOversizeOrTombstoneValues() {
        assertRejected(
                record(SERIAL, VALID_JSON.replace(EVENT_ID, "00000000-0000-1000-8000-000000000000")),
                ReservationCancellationFailureCode.INVALID_EVENT);
        assertRejected(
                new ConsumerRecord<>("source", 0, 0, SERIAL.getBytes(StandardCharsets.UTF_8), null),
                ReservationCancellationFailureCode.INVALID_EVENT);
        assertRejected(record(SERIAL, " ".repeat(4097)), ReservationCancellationFailureCode.INVALID_EVENT);
    }

    @Test
    void semanticChangesProduceDifferentFingerprints() {
        ReservationCancellationEvent original = converter.convert(record(SERIAL, VALID_JSON));
        ReservationCancellationEvent changedTime =
                converter.convert(record(SERIAL, VALID_JSON.replace("15:30:00Z", "15:30:01Z")));
        String changedSerialJson = VALID_JSON.replace(SERIAL, "DRILL-002");
        ReservationCancellationEvent changedSerial = converter.convert(record("DRILL-002", changedSerialJson));

        assertThat(changedTime.fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(changedSerial.fingerprint()).isNotEqualTo(original.fingerprint());
    }

    private ConsumerRecord<byte[], byte[]> record(String key, String value) {
        return new ConsumerRecord<>(
                "source", 0, 0, key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }

    private void assertRejected(
            ConsumerRecord<byte[], byte[]> record, ReservationCancellationFailureCode expectedFailureCode) {
        assertThatThrownBy(() -> converter.convert(record))
                .isInstanceOfSatisfying(ReservationCancellationRejectedException.class, exception -> {
                    assertThat(exception.getFailureCode()).isEqualTo(expectedFailureCode);
                    if (record.value() != null && record.value().length > 0) {
                        String rawValue = new String(record.value(), StandardCharsets.UTF_8);
                        assertThat(exception.getMessage()).doesNotContain(rawValue);
                    }
                });
    }
}
