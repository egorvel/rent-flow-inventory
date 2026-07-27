package com.rentflow.repository;

import com.rentflow.model.InventoryItem;
import com.rentflow.model.InventoryStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

final class InventorySpecifications {

    private InventorySpecifications() {}

    static Specification<InventoryItem> withFilters(InventoryStatus status, String type) {
        List<Specification<InventoryItem>> filters = new ArrayList<>();
        if (status != null) {
            filters.add((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (type != null) {
            var normalizedType = type.toLowerCase(Locale.ROOT);
            filters.add((root, query, builder) -> builder.equal(builder.lower(root.get("type")), normalizedType));
        }
        return Specification.allOf(filters);
    }
}
