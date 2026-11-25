package com.leads.dto.settings;

/** Module 05 P1: what {@code DELETE /products/{id}} did. */
public record ProductDeleteResult(Outcome outcome) {

    public enum Outcome {
        /** Not used by any lead or quotation: removed. */
        DELETED,
        /** Used by a lead or quotation: kept for their history and set inactive (hidden from the catalogue and pickers). */
        DEACTIVATED
    }
}
