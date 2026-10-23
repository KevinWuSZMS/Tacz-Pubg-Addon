package com.example.examplemod.vitals;

/** Existing boost item IDs share the interruption and completion rules of all medicines. */
public final class PubgBoostItem extends PubgMedicineItem {
    public PubgBoostItem(Properties properties, PubgMedicineModel.Kind kind) {
        super(properties, kind);
        if (!kind.isBoost()) throw new IllegalArgumentException("Expected a boost medicine");
    }
}
