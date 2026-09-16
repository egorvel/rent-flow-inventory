package com.rentflow.converter;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.rentflow.model.ReservationCancellationEvent;
import com.rentflow.model.ReservationCancellationFailureCode;
import com.rentflow.model.ReservationCancellationRejectedException;
import com.rentflow.util.LengthPrefixedSha256;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ReservationCancellationEventConverter {
    private static final String EVENT_TYPE = "ReservationCancelled";
    private static final int EVENT_VERSION = 1;
    private static final String EVENT_NAMESPACE = "rentflow.reservation.cancelled.v1";
    private static final String LOCK_NAMESPACE = "inventory.reservation-cancellation.event-lock";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern SERIAL_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final int maxPayloadBytes;

    public ReservationCancellationEventConverter(
            @Value("${inventory.kafka.reservation-cancellation.max-payload-bytes}") int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public ReservationCancellationEvent convert(ConsumerRecord<byte[], byte[]> record) {
        byte[] valueBytes = record.value();
        if (valueBytes == null || valueBytes.length > maxPayloadBytes) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, null);
        }

        String value = decode(valueBytes, null);
        JsonNode root = parse(value);
        if (root == null || !root.isObject()) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, null);
        }

        UUID eventId = parseEventId(requiredText(root, "eventId", null));
        String eventType = requiredText(root, "eventType", eventId);
        int eventVersion = requiredInteger(root, "eventVersion", eventId);
        String occurredAtValue = requiredText(root, "occurredAt", eventId);
        String serialNumber = requiredText(root, "serialNumber", eventId);
        if (!EVENT_TYPE.equals(eventType) || eventVersion != EVENT_VERSION) {
            throw rejected(ReservationCancellationFailureCode.UNSUPPORTED_EVENT, eventId);
        }
        Instant occurredAt = parseInstant(occurredAtValue, eventId);
        if (!SERIAL_PATTERN.matcher(serialNumber).matches()) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, eventId);
        }
        if (record.key() == null) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, eventId);
        }
        String key = decode(record.key(), eventId);
        if (!SERIAL_PATTERN.matcher(key).matches() || !key.equals(serialNumber)) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, eventId);
        }

        String fingerprint = fingerprint(eventId, occurredAt, serialNumber, key);
        return new ReservationCancellationEvent(eventId, occurredAt, serialNumber, fingerprint, lockId(eventId));
    }

    public Optional<UUID> extractEventId(byte[] valueBytes) {
        if (valueBytes == null || valueBytes.length > maxPayloadBytes) {
            return Optional.empty();
        }
        try {
            String value = decode(valueBytes, null);
            return parseEventIdIfPresent(parse(value));
        } catch (ReservationCancellationRejectedException exception) {
            return Optional.empty();
        }
    }

    private JsonNode parse(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (JacksonException exception) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, null);
        }
    }

    private Optional<UUID> parseEventIdIfPresent(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode node = root.get("eventId");
        if (node == null || !node.isString()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parseEventId(node.stringValue()));
        } catch (ReservationCancellationRejectedException exception) {
            return Optional.empty();
        }
    }

    private UUID parseEventId(String value) {
        if (!UUID_PATTERN.matcher(value).matches()) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, null);
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, null);
        }
        if (eventId.version() != 4 || eventId.variant() != 2) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, null);
        }
        return eventId;
    }

    private Instant parseInstant(String value, UUID eventId) {
        if (!value.endsWith("Z")) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, eventId);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, eventId);
        }
    }

    private String requiredText(JsonNode root, String field, UUID eventId) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString()) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, eventId);
        }
        return node.stringValue();
    }

    private int requiredInteger(JsonNode root, String field, UUID eventId) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, eventId);
        }
        return node.intValue();
    }

    private String decode(byte[] bytes, UUID eventId) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw rejected(ReservationCancellationFailureCode.INVALID_EVENT, eventId);
        }
    }

    private ReservationCancellationRejectedException rejected(
            ReservationCancellationFailureCode failureCode, UUID eventId) {
        return new ReservationCancellationRejectedException(failureCode, eventId);
    }

    private static String fingerprint(UUID eventId, Instant occurredAt, String serialNumber, String key) {
        return LengthPrefixedSha256.hex(List.of(
                EVENT_NAMESPACE,
                eventId.toString(),
                EVENT_TYPE,
                Integer.toString(EVENT_VERSION),
                occurredAt.toString(),
                serialNumber,
                key));
    }

    private static long lockId(UUID eventId) {
        return LengthPrefixedSha256.firstLong(List.of(LOCK_NAMESPACE, eventId.toString()));
    }
}
