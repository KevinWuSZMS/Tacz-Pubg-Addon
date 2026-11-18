package com.example.examplemod.inventory.client;

import static com.example.examplemod.inventory.client.PubgInventoryInteractionRules.*;

/** Exercises the same routing used by hover previews, drag badges and released actions. */
public final class PubgInventoryInteractionChecks {
    private static int checks;
    public static void main(String[] args) {
        for (int selected = -1; selected < 9; selected++) {
            for (int mask = 0; mask < 8; mask++) {
                int eligible = mask;
                int expected = selected >= 0 && selected < 3 && (mask & 1 << selected) != 0 ? selected
                        : (mask & 1) != 0 ? 0 : (mask & 2) != 0 ? 1 : (mask & 4) != 0 ? 2 : -1;
                Target inventory = attachmentDefault(selected, false, true,
                        slot -> (eligible & 1 << slot) != 0, slot -> true);
                require(inventory.slot() == expected, "hover preference diverges for selected=" + selected + ", mask=" + mask);
                require(inventory.operation() == (expected < 0 ? Operation.INVALID : Operation.ATTACH), "inventory attachment fallback");
                Target ground = attachmentDefault(selected, true, true,
                        slot -> (eligible & 1 << slot) != 0, slot -> false);
                require(ground.operation() == (expected < 0 ? Operation.STORE : Operation.ATTACH), "ground attachment fallback");
                require(ground.slot() == expected, "ground preview selects another gun");
            }
        }
        Target collect = attachmentDefault(1, true, false,
                slot -> { throw new AssertionError("collect-only setting must not inspect equipment targets"); }, slot -> true);
        require(collect.operation() == Operation.STORE, "collect-only right click must enter storage");
        require(attachmentDefault(1, false, false, slot -> true, slot -> true).slot() == 1,
                "collect-only setting must not disable inventory attachment use");
        require(attachmentDefault(1, false, true, slot -> true, slot -> slot == 1).replacement(),
                "a selected occupied compatible gun remains the explicit replacement target");
        require(attachmentDefault(1, false, true, slot -> slot != 1, slot -> false).slot() == 0,
                "same-component or locked selected attachment must skip to the next candidate");

        Source groundPart = new Source(true, false, 400, true, true);
        Source inventoryPart = new Source(false, false, 15, true, true);
        Source mountedPart = new Source(false, true, 0, true, true);
        Destination inventoryColumn = new Destination(false, true, -1, false, false, false);
        Destination nearbyColumn = new Destination(true, false, -1, false, false, false);
        require(drag(groundPart, inventoryColumn).operation() == Operation.STORE,
                "dragging from ground into the list must collect, never perform default equipment selection");
        require(drag(groundPart, inventoryColumn).badge() == Badge.BACKPACK, "ground collection badge");
        require(drag(inventoryPart, nearbyColumn).operation() == Operation.DROP, "inventory-to-nearby drop");
        require(drag(mountedPart, nearbyColumn).badge() == Badge.MINUS, "mounted attachment-to-nearby drop");
        require(drag(groundPart, nearbyColumn).operation() == Operation.RETURN, "returning ground loot must not duplicate it");
        require(drag(inventoryPart, inventoryColumn).operation() == Operation.RETURN, "same inventory drag must not reorder backing slots");
        require(drag(mountedPart, inventoryColumn).operation() == Operation.STORE, "mounted attachment must unload to storage");

        for (int gun = 0; gun < 3; gun++) {
            Destination body = new Destination(false, false, gun, true, false, false);
            Target install = drag(groundPart, body);
            require(install.operation() == Operation.ATTACH && install.slot() == gun && install.badge() == Badge.EQUIP,
                    "attachment must route by its own type throughout the weapon card");
            Target replace = drag(inventoryPart, new Destination(false, false, gun, true, false, true));
            require(replace.operation() == Operation.ATTACH && replace.badge() == Badge.SWAP, "occupied attachment slot must announce replacement");
            require(!drag(inventoryPart, new Destination(false, false, gun, false, true, true)).valid(),
                    "an incompatible attachment must never fall through and replace the whole gun");
        }
        require(drag(mountedPart, new Destination(false, false, 0, false, false, true)).operation() == Operation.RETURN,
                "returning an attachment to its source gun must not uninstall it");
        require(drag(mountedPart, new Destination(false, false, 1, true, false, true)).badge() == Badge.SWAP,
                "cross-gun attachment swap must retain attachment semantics");
        require(!drag(mountedPart, new Destination(false, false, 39, false, true, true)).valid(),
                "mounted attachment cannot become an armor item");
        require(drag(groundPart, new Destination(false, false, -1, false, false, false)).badge() == Badge.INVALID,
                "invalid areas need the red X instead of a successful action badge");
        require(!drag(new Source(true, false, 600, false, false), inventoryColumn).valid(), "guns cannot enter the inventory list");
        require(drag(new Source(false, false, 38, false, true), inventoryColumn).operation() == Operation.STORE,
                "armor dragged into inventory must be removed from its real equipment slot");

        require(groundWeaponDefault(1, slot -> slot < 2, slot -> slot == 0).slot() == 0, "an empty matching weapon slot precedes replacing the held gun");
        require(groundWeaponDefault(1, slot -> slot < 2, slot -> false).slot() == 1, "full weapon slots replace only the selected matching gun");
        require(!groundWeaponDefault(2, slot -> slot < 2, slot -> false).valid(), "a held pistol must not authorize replacing a full primary slot");
        require(!groundWeaponDefault(5, slot -> slot < 2, slot -> false).valid(), "holding medicine must not authorize a full-gun replacement");
        require(groundWeaponDefault(0, slot -> slot == 2, slot -> slot == 2).slot() == 2, "handguns select only their matching slot");
        System.out.println("PUBG inventory interaction: " + checks + " routing checks passed");
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
