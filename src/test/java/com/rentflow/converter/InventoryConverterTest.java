package com.rentflow.converter;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.rentflow.dto.InventoryItemDTO;
import com.rentflow.dto.InventoryPageResponse;
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

    @Test
    void mapsPageContentAndMetadata() {
        InventoryItem item = new InventoryItem("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE);
        PageImpl<InventoryItem> page = new PageImpl<>(List.of(item), PageRequest.of(2, 1), 4);

        InventoryPageResponse response = converter.toPageResponse(page);

        assertThat(response.items()).containsExactly(converter.toResponse(item));
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(4);
        assertThat(response.totalPages()).isEqualTo(4);
    }
}
