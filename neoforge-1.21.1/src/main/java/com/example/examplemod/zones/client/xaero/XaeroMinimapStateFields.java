package com.example.examplemod.zones.client.xaero;

import com.example.examplemod.localization.Texts;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Bind once to private native projection state, including Xaero's public compatibility subclass. */
final class XaeroMinimapStateFields {
    private final Field ps, pc, zoom, halfWidth, halfHeight, circle;

    XaeroMinimapStateFields(Class<?> handlerType) throws ReflectiveOperationException {
        ps = inheritedField(handlerType, "ps", double.class);
        pc = inheritedField(handlerType, "pc", double.class);
        zoom = inheritedField(handlerType, "zoom", double.class);
        halfWidth = inheritedField(handlerType, "halfViewW", int.class);
        halfHeight = inheritedField(handlerType, "halfViewH", int.class);
        circle = inheritedField(handlerType, "circle", boolean.class);
    }

    Frame read(Object handler) throws IllegalAccessException {
        return new Frame(ps.getDouble(handler), pc.getDouble(handler), zoom.getDouble(handler),
                halfWidth.getInt(handler), halfHeight.getInt(handler), circle.getBoolean(handler));
    }

    record Frame(double ps, double pc, double zoom, int halfWidth, int halfHeight, boolean circle) {}

    static Field inheritedField(Class<?> handlerType, String name, Class<?> expectedType) throws ReflectiveOperationException {
        for (Class<?> owner = handlerType; owner != null; owner = owner.getSuperclass()) {
            final Field field;
            try { field = owner.getDeclaredField(name); }
            catch (NoSuchFieldException absent) { continue; }
            if (field.getType() != expectedType || Modifier.isStatic(field.getModifiers())) {
                throw new NoSuchFieldException(owner.getName() + "." + name + Texts.tr("error.tacz_pubg.xaero_minimap_state_fields.must_be_an_instance") + expectedType.getName());
            }
            if (!field.trySetAccessible()) throw new IllegalAccessException(Texts.tr("error.tacz_pubg.xaero_minimap_state_fields.cannot_read") + owner.getName() + "." + name);
            return field;
        }
        throw new NoSuchFieldException(handlerType.getName() + Texts.tr("error.tacz_pubg.xaero_minimap_state_fields.hierarchy_has_no") + expectedType.getName() + " " + name);
    }
}
