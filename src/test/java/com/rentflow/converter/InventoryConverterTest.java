package com.rentflow.converter;

import org.junit.jupiter.api.Test;

import com.rentflow.dto.InventoryItemDTO;
import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryConverterTest {

    private final InventoryConverter converter = new InventoryConverter();

    @Test
    void mapsEveryBusinessField() {
        InventoryItem item = new InventoryItem("DRILL-001", "Industrial drill", "Bosch GBH", InventoryStatus.AVAILABLE);

        assertThat(converter.toResponse(item))
                .isEqualTo(
                        new InventoryItemDTO("DRILL-001", "Industrial drill", "Bosch GBH", InventoryStatus.AVAILABLE));
    }
}
