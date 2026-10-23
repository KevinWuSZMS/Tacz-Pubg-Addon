package com.example.examplemod.localization;

import com.example.examplemod.zones.match.MatchConfigPayloads;
import com.example.examplemod.zones.MapZoneConfigPayloads;
import com.example.examplemod.spawn.SpawnRegionConfigPayloads;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.regex.Pattern;

/** Locale switching, native components, placeholders and real packet codecs; never starts a game. */
public final class LocalizationChecks {
    private static int checks;
    private static final String CANCEL = "screen.tacz_pubg.map_editor.cancel_editing";
    private static final String FAILED = "message.tacz_pubg.configuration.save_failed";
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        var zh = TestLanguages.catalog("zh_cn"); var en = TestLanguages.catalog("en_us");
        nativeArgumentChecks(zh, en);
        check(zh.keySet().equals(en.keySet()), "locale keys must agree");
        Pattern parameter = Pattern.compile("%(\\d+)\\$s");
        for (String key : en.keySet()) {
            check(!en.get(key).matches("(?s).*[\\x{3400}-\\x{9fff}].*"), "English catalog contains Chinese: " + key);
            check(parameters(parameter, zh.get(key)).equals(parameters(parameter, en.get(key))), "placeholder mismatch: " + key);
            if (!(key.startsWith("screen.tacz_pubg.") || key.startsWith("message.tacz_pubg.") || key.startsWith("error.tacz_pubg."))) continue;
            int count = parameter.matcher(en.get(key)).results().mapToInt(m -> Integer.parseInt(m.group(1))).max().orElse(0);
            Object[] values = new Object[count]; for(int i=0;i<count;i++) values[i]="ARG"+(i+1);
            String encoded = Texts.tr(key, values);
            for(var catalog : List.of(zh,en)) {
                Language.inject(TestLanguages.language(catalog));
                String rendered = Texts.resolve(encoded);
                check(rendered.indexOf('\ue000')<0, "transport marker leaked: " + key);
                check(!parameter.matcher(rendered).find(), "unsubstituted placeholder: " + key);
                for(Object value:values) check(rendered.contains(value.toString()), "lost argument: " + key);
            }
        }
        Language.inject(TestLanguages.language(zh));
        String wire = Texts.tr(FAILED, Texts.tr(CANCEL));
        Component component = Texts.component(wire);
        check(component.getString().equals("保存失败：取消编辑"), "Chinese nested message");
        Language.inject(TestLanguages.language(en));
        check(component.getString().equals("Save failed: Cancel Editing"), "same component changes language lazily");
        check(Texts.tr(FAILED, Texts.tr(CANCEL)).equals(wire), "server wire text cannot depend on language");
        check(Texts.of(CANCEL).getContents() instanceof TranslatableContents, "native translatable content");
        check(component.getSiblings().stream().anyMatch(c -> c.getContents() instanceof TranslatableContents t && t.getKey().equals(FAILED)),
                "chat boundary retains a native translation key rather than rendered server text");
        var overridden = new HashMap<>(en); overridden.put(CANCEL,"Pack Override");
        Language.inject(TestLanguages.language(overridden));
        check(component.getString().equals("Save failed: Pack Override"), "resource pack override and language reload");
        Language.inject(TestLanguages.language(Map.of()));
        check(Texts.resolve(Texts.tr(CANCEL)).equals("Cancel Editing"), "missing language uses English fallback");
        for(String code:List.of("zh_tw","zh_hk","zh_sg","zh_hant","lzh","yue_hk"))
            check(ChineseLanguageFallback.needsFallback("tacz_pubg","lang/"+code+".json"), "Chinese variant " + code);
        for(String code:List.of("en_us","de_de","ja_jp","fr_fr","zh_cn"))
            check(!ChineseLanguageFallback.needsFallback("tacz_pubg","lang/"+code+".json"), "no extra fallback " + code);
        check(!ChineseLanguageFallback.needsFallback("minecraft","lang/zh_tw.json"), "vanilla is untouched");
        check(!ChineseLanguageFallback.needsFallback("other_mod","lang/zh_hk.json"), "other addons are untouched");
        for(String value:List.of("My 地图 100%", "minecraft:stone", "A \"quoted\" \\ name\n😀", "\ue000unknown\ue001", "\ue000unfinished"))
            check(Texts.resolve(value).equals(value), "ordinary and malformed input stays literal");
        for(String value:List.of("世界限高", "World Height Limit", Texts.tr("screen.tacz_pubg.zone_settings.world_height_limit")))
            check(Texts.matches("screen.tacz_pubg.zone_settings.world_height_limit",value), "height alias " + value);
        check(!Texts.matches("screen.tacz_pubg.zone_settings.world_height_limit","123"), "numeric input is not an alias");
        String longMessage=Texts.tr(FAILED, "Detail ".repeat(350));
        check(longMessage.length()>1024, "test exceeds old string packet bound");
        var snapshot=new MatchConfigPayloads.Snapshot(UUID.randomUUID(),0,List.of(),0,null,null,MatchConfigPayloads.FAILED,longMessage);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
        try {
            MatchConfigPayloads.Snapshot.CODEC.encode(buffer,snapshot);
            var received=MatchConfigPayloads.Snapshot.CODEC.decode(buffer);
            check(received.message().equals(longMessage), "real snapshot codec preserves key and all arguments");
            Language.inject(TestLanguages.language(zh));
            check(Texts.resolve(received.message()).startsWith("保存失败：Detail"), "received message resolves on Chinese client");
            Language.inject(TestLanguages.language(en));
            check(Texts.resolve(received.message()).startsWith("Save failed: Detail"), "same packet resolves on English client");
        } finally { buffer.release(); }
        for(int limit=1;limit<wire.length();limit++) {
            String limited=Texts.limit(wire,limit);
            check(limited.length()<=limit && Texts.resolve(limited).indexOf('\ue000')<0,"bounded messages do not cut tokens");
        }
        System.out.println("Localization checks passed: " + checks + "; catalog keys=" + en.size());
    }
    private static void nativeArgumentChecks(Map<String, String> zh, Map<String, String> en) {
        var dimension = ResourceLocation.parse("minecraft:overworld");
        var map = new MapZoneConfigPayloads.Summary(1, "Test Map", dimension, -10, -20, 30, 40, true, 2);
        Component mapTooltip = Texts.of("screen.tacz_pubg.map_config.x_z_safe_zone_bombardment_zones",
                map.id(), map.name(), map.dimension(), "-10", "30", "-20", "40",
                Texts.tr("screen.tacz_pubg.map_config.set"), map.bombingZoneCount());
        var region = new SpawnRegionConfigPayloads.Summary(2, dimension, 400, BlockPos.ZERO, new BlockPos(19, 0, 19), 1);
        Component regionTooltip = Texts.of("screen.tacz_pubg.spawn_region_config.id_m",
                region.id(), region.dimension(), region.area());
        for (Component tooltip : List.of(mapTooltip, regionTooltip)) {
            check(tooltip.getContents() instanceof TranslatableContents, "tooltip retains native translation");
            for (var catalog : List.of(zh, en)) {
                Language.inject(TestLanguages.language(catalog));
                String expected = tooltip == mapTooltip
                        ? (catalog == zh
                            ? "#1 Test Map\nminecraft:overworld\nX -10–30 · Z -20–40\n安全区：已设置 · 轰炸区 2 个"
                            : "#1 Test Map\nminecraft:overworld\nX -10–30 · Z -20–40\nSafe Zone: Set · 2 Bombardment Zones")
                        : (catalog == zh
                            ? "编号 #2 · minecraft:overworld · 400 平方米"
                            : "ID #2 · minecraft:overworld · 400 m²");
                check(tooltip.getString().equals(expected), "map/region tooltip renders real dimension and nested status");
            }
        }
        Component styled = Texts.of(CANCEL).withStyle(ChatFormatting.RED);
        for (Object value : Arrays.asList(dimension, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Thread.State.RUNNABLE, 'X', null, "literal", 7, 2.5, true, styled)) {
            Object[] input = {value};
            Component message = Texts.of(FAILED, input);
            Object nativeValue = ((TranslatableContents) message.getContents()).getArgs()[0];
            boolean nativeType = value instanceof Component || value instanceof Number
                    || value instanceof Boolean || value instanceof String;
            check(nativeType ? nativeValue == value : Objects.equals(nativeValue, String.valueOf(value)),
                    "preserve supported values; stringify other argument types");
            check(input[0] == value, "normalization does not mutate the caller's argument array");
            for (var catalog : List.of(zh, en)) {
                Language.inject(TestLanguages.language(catalog));
                String detail = value instanceof Component c ? c.getString() : String.valueOf(value);
                check(message.getString().equals((catalog == zh ? "保存失败：" : "Save failed: ") + detail),
                        "normalized values render correctly and remain language-neutral");
            }
        }
    }
    private static List<String> parameters(Pattern pattern,String text) { return pattern.matcher(text).results().map(m->m.group(1)).sorted().toList(); }
}
