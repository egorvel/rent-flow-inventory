package com.rentflow.converter;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rentflow.dto.InventoryStatusHistoryDTO;
import com.rentflow.model.InventoryStatus;
import com.rentflow.model.InventoryStatusHistory;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryStatusHistoryConverterTest {

    private final InventoryStatusHistoryConverter converter = new InventoryStatusHistoryConverter();

    @Test
    void mapsOnlyTheFourPublicHistoryFields() {
        InventoryStatusHistory history =
                new InventoryStatusHistory("DRILL-001", InventoryStatus.RESERVED, InventoryStatus.RENTED);
        Instant timestamp = Instant.parse("2026-08-04T19:10:30.123456Z");
        ReflectionTestUtils.setField(history, "id", 42L);
        ReflectionTestUtils.setField(history, "timestamp", timestamp);

        InventoryStatusHistoryDTO response = converter.toResponse(history);

        assertThat(response)
                .isEqualTo(new InventoryStatusHistoryDTO(
                        "DRILL-001", InventoryStatus.RESERVED, InventoryStatus.RENTED, timestamp));
        assertThat(InventoryStatusHistoryDTO.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("serialNumber", "statusFrom", "statusTo", "timestamp");
    }
}
