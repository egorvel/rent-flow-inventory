package com.rentflow.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.rentflow.dto.InventoryItemResponse;
import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class InventoryConverterTest {

    private final InventoryConverter converter = new InventoryConverter();

    @Test
    void mapsEveryBusinessField() {
        var item = new InventoryItem("DRILL-001", "Industrial drill", "Bosch GBH", InventoryStatus.AVAILABLE);

        assertThat(converter.toResponse(item))
                .isEqualTo(new InventoryItemResponse(
                        "DRILL-001", "Industrial drill", "Bosch GBH", InventoryStatus.AVAILABLE));
    }

    @Test
    void mapsPageContentAndMetadata() {
        var item = new InventoryItem("DRILL-001", "Drill", "Bosch", InventoryStatus.AVAILABLE);
        var page = new PageImpl<>(List.of(item), PageRequest.of(2, 1), 4);

        var response = converter.toPageResponse(page);

        assertThat(response.items()).containsExactly(converter.toResponse(item));
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(4);
        assertThat(response.totalPages()).isEqualTo(4);
    }
}
