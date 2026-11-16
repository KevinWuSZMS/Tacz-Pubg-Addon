package com.example.examplemod.keybindings;

import com.example.examplemod.ExampleMod;
import com.tacz.guns.client.input.AimKey;
import com.tacz.guns.client.input.ConfigKey;
import com.tacz.guns.client.input.CrawlKey;
import com.tacz.guns.client.input.InspectKey;
import com.tacz.guns.client.input.RefitKey;
import com.tacz.guns.client.input.ReloadKey;
import java.util.function.Consumer;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.xjqsh.lrtactical.client.input.AttackKeys;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Device adapters for registered actions whose native mod only listens to one device's events. */
final class BindingInputCompatibility {
    static final int FORWARDED_SCAN = 0x6fffff3, FORWARDED_BUTTON = -777;
    private static String forwarded;
    private record OptionalAdapter(Set<String> ids, String type, String method) { }
    private static final List<OptionalAdapter> OPTIONAL = List.of(
            new OptionalAdapter(Set.of("key.configured.open_mod_list"), "com.mrcrayfish.configured.client.NeoForgeClientEvents", "onKeyPress"),
            new OptionalAdapter(Set.of("key.jade.config", "key.jade.show_overlay", "key.jade.toggle_liquid", "key.jade.narrate", "key.jade.show_recipes", "key.jade.show_uses"), "snownee.jade.util.ClientProxy", "onKeyPressed"),
            new OptionalAdapter(Set.of("placebo.toggleTrails"), "dev.shadowsoffire.placebo.patreon.TrailsManager", "keys"),
            new OptionalAdapter(Set.of("placebo.toggleWings"), "dev.shadowsoffire.placebo.patreon.WingsManager", "keys"));
    private static final Map<OptionalAdapter, Method> METHODS = new HashMap<>();
    private static final Set<OptionalAdapter> FAILED = new HashSet<>();
    private BindingInputCompatibility() { }
    static String forwardedId() { return forwarded; }

    static void releaseHeld() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.player == null || !BindingInput.managed(CrawlKey.CRAWL_KEY)) return;
        // Crawl has no held-state tick: resetting physical input must also end a held crawl.
        if (BindingInput.physicallyHeld(CrawlKey.CRAWL_KEY) && com.tacz.guns.config.client.KeyConfig.HOLD_TO_CRAWL.get())
            com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator.fromLocalPlayer(mc.player).crawl(false);
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BindingInputCompatibility::mouse);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BindingInputCompatibility::keyboard);
    }

    private static void mouse(InputEvent.MouseButton.Post event) {
        String id = BindingKeys.fromMouse(event.getButton());
        var forwardedEvent = new InputEvent.Key(GLFW.GLFW_KEY_UNKNOWN, FORWARDED_SCAN, event.getAction(), event.getModifiers());
        // These r7 handlers have no MouseButton listener. Calling the native methods retains their
        // gun/aim/screen/state checks and network behavior, without broadcasting duplicate events.
        keyOnly(ConfigKey.OPEN_CONFIG_KEY, id, forwardedEvent, ConfigKey::onOpenConfig);
        keyOnly(CrawlKey.CRAWL_KEY, id, forwardedEvent, CrawlKey::onCrawlPress);
        keyOnly(InspectKey.INSPECT_KEY, id, forwardedEvent, InspectKey::onInspectPress);
        keyOnly(RefitKey.REFIT_KEY, id, forwardedEvent, RefitKey::onRefitPress);
        keyOnly(ReloadKey.RELOAD_KEY, id, forwardedEvent, ReloadKey::onReloadPress);
        for (var adapter : OPTIONAL) if (BindingInput.registeredMatch(adapter.ids(), id)) optional(adapter, id, forwardedEvent);
    }

    private static void keyOnly(KeyMapping mapping, String id, InputEvent.Key event, Consumer<InputEvent.Key> handler) {
        if (!BindingInput.managed(mapping) || !BindingInput.matches(mapping, id)) return;
        String previous = forwarded;
        forwarded = id;
        try { handler.accept(event); } finally { forwarded = previous; }
    }

    /** Optional mods remain optional; dispatch is limited to their normal registered Controls keys. */
    private static void optional(OptionalAdapter adapter, String id, InputEvent.Key event) {
        if (FAILED.contains(adapter)) return;
        String previous = forwarded;
        forwarded = id;
        try {
            Method method = METHODS.get(adapter);
            if (method == null) {
                method = Class.forName(adapter.type()).getDeclaredMethod(adapter.method(), InputEvent.Key.class);
                method.setAccessible(true);
                METHODS.put(adapter, method);
            }
            method.invoke(null, event);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            FAILED.add(adapter);
            ExampleMod.LOGGER.error("Could not dispatch registered mouse binding to {}.{}", adapter.type(), adapter.method(), exception);
        } finally { forwarded = previous; }
    }

    private static void keyboard(InputEvent.Key event) {
        String id = BindingInput.keyboardId(event.getKey(), event.getScanCode());
        if (BindingInput.managed(AttackKeys.NORMAL_ATTACK) && BindingInput.matches(AttackKeys.NORMAL_ATTACK, id)) {
            // LR's tick keeps held attacks running; this consumes the keyboard click immediately.
            AttackKeys.onNormalAttack(new InputEvent.MouseButton.Post(FORWARDED_BUTTON, event.getAction(), event.getModifiers()));
        }
        // TaCZ's hold-to-aim tick handles held state, while toggle-to-aim requires its press handler.
        if (!BindingInput.managed(AimKey.AIM_KEY) || !BindingInput.matches(AimKey.AIM_KEY, id)) return;
        String previous = forwarded;
        forwarded = id;
        try { AimKey.onAimPress(new InputEvent.MouseButton.Post(FORWARDED_BUTTON, event.getAction(), event.getModifiers())); }
        finally { forwarded = previous; }
    }
}
