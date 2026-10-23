import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.contents.TranslatableFormatException;
import net.minecraft.util.FormattedCharSequence;

/** Calls Minecraft's actual formatters without constructing Minecraft, a window or a world. */
public final class AttachmentTooltipFormatChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        JsonObject fixture = new Gson().fromJson(Files.readString(Path.of(args[0])), JsonObject.class);
        var map = new java.util.HashMap<String, String>();
        Language.loadFromJson(new ByteArrayInputStream(fixture.getAsJsonObject("translations")
                .toString().getBytes(StandardCharsets.UTF_8)), map::put);
        Language.inject(new Language() {
            @Override public String getOrDefault(String key, String fallback) { return map.getOrDefault(key, fallback); }
            @Override public boolean has(String key) { return map.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
            @Override public Map<String, String> getLanguageData() { return map; }
        });
        // Initializing I18n after injection gives its real static language field this test dictionary.
        Method decompose = TranslatableContents.class.getDeclaredMethod("decomposeTemplate", String.class, Consumer.class);
        decompose.setAccessible(true);
        for (var value : fixture.getAsJsonArray("cases")) {
            JsonObject test = value.getAsJsonObject();
            String key = test.get("key").getAsString();
            String expected = test.get("expected").getAsString();
            Object[] parameters = new Object[test.getAsJsonArray("args").size()];
            for (int i = 0; i < parameters.length; i++) parameters[i] = test.getAsJsonArray("args").get(i).getAsString();
            String translated = I18n.get(key, parameters);
            check(expected.equals(translated), "I18n argument contract: " + key + " => " + translated);
            check(!translated.startsWith("Format error:"), "No format error: " + key);
            check(expected.equals(decompose(decompose, key, map.get(key), parameters)), "TranslatableContents argument contract: " + key);
        }
        int reproductions = 0;
        for (var value : fixture.getAsJsonArray("original_broken_keys")) {
            String key = value.getAsString();
            check(I18n.get(key).equals("Format error: " + map.get(key)), "Original tooltip must reproduce the reported failure: " + key);
            check(rejects(decompose, key, map.get(key), new Object[0]), "Original bare percent must violate TranslatableContents: " + key);
            reproductions++;
        }
        check("15%".equals(decompose(decompose, "literal", "15%%", new Object[0])), "Literal percent takes zero arguments");
        check("value: 3".equals(decompose(decompose, "arg", "value: %s", new Object[]{"3"})), "One string placeholder takes one argument");
        check("second:first".equals(decompose(decompose, "indexed", "%2$s:%1$s", new Object[]{"first", "second"})), "Indexed placeholders retain their argument indices");
        check(rejects(decompose, "missing", "%s", new Object[0]), "Missing parameter is rejected");
        check(rejects(decompose, "missing-index", "%2$s", new Object[]{"first"}), "Out-of-range index is rejected");
        check(rejects(decompose, "numeric", "%d", new Object[]{"3"}), "Raw numeric placeholder is unsupported by TranslatableContents");
        check(rejects(decompose, "unknown", "%q", new Object[0]), "Unsupported placeholder is rejected");
        System.out.println("AttachmentTooltipFormatChecks: " + checks + " native formatter checks passed; " + reproductions + " original failures reproduced; no game launched.");
    }

    private static String decompose(Method method, String key, String text, Object[] parameters) throws Exception {
        var parts = new ArrayList<FormattedText>();
        method.invoke(new TranslatableContents(key, null, parameters), text, (Consumer<FormattedText>) parts::add);
        StringBuilder output = new StringBuilder();
        for (FormattedText part : parts) part.visit((String fragment) -> { output.append(fragment); return Optional.empty(); });
        return output.toString();
    }

    private static boolean rejects(Method method, String key, String text, Object[] parameters) throws Exception {
        try { decompose(method, key, text, parameters); return false; }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof TranslatableFormatException) return true;
            throw error;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
