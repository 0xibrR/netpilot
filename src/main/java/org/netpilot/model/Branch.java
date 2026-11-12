package org.netpilot.model;

import java.util.List;
import java.util.Objects;

/**
 * A router the user works with, by a name of their choosing ("the shop", "the town branch"). Its
 * records (sales, points of sale, customers) are kept under its address, as they always were; the
 * password, when remembered, is in the encrypted credential store, never here.
 *
 * @param id       stable id, so renaming keeps the branch
 * @param name     the name shown in the top bar and the reports
 * @param endpoint how to reach it
 * @param color    one of {@link #COLORS}, to tell branches apart at a glance
 */
public record Branch(String id, String name, RouterEndpoint endpoint, String color) {

    /** Colours to choose from; clear on the light and dark themes. */
    public static final List<String> COLORS = List.of("#2f7bd8", "#1fa37a", "#e08a00", "#c2185b", "#7b4fd6",
            "#00838f");

    public Branch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(endpoint, "endpoint");
        name = name == null || name.isBlank() ? endpoint.host() : name.strip();
        color = color != null && COLORS.contains(color) ? color : COLORS.getFirst();
    }

    /** The key its records are kept under: the router's address. */
    public String address() {
        return endpoint.address();
    }

    public Branch withName(String newName) {
        return new Branch(id, newName, endpoint, color);
    }
}
