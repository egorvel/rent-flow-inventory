package com.rentflow.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.rentflow.dto.InventoryItemResponse;
import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import org.junit.jupiter.api.Test;

class InventoryConverterTest {

    private final InventoryConverter converter = new InventoryConverter();

    @Test
    void mapsEveryBusinessField() {
        var item = new InventoryItem("DRILL-001", "Industrial drill", "Bosch GBH", InventoryStatus.AVAILABLE);

        assertThat(converter.toResponse(item))
                .isEqualTo(new InventoryItemResponse(
                        "DRILL-001", "Industrial drill", "Bosch GBH", InventoryStatus.AVAILABLE));
    }
}
