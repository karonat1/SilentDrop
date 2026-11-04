package ru.silentdrop.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * ItemStack serialization to string and back.
 *
 * The primary way is Bukkit serialization into Base64: it keeps NBT-like meta
 * (enchantments, display names, shulker contents, books, ...).
 * The fallback is {@link ItemStack#serialize()} (loses part of the meta).
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    /** @return Base64 string, or {@code null} when the item cannot be serialized. */
    public static String encode(ItemStack stack) {
        if (stack == null) return null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes);
            try {
                out.writeObject(stack);
            } finally {
                out.close();
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    /** @return the item, or {@code null} when the data is corrupted or outdated. */
    public static ItemStack decode(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream in = new BukkitObjectInputStream(bytes);
            try {
                Object o = in.readObject();
                return o instanceof ItemStack ? (ItemStack) o : null;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Fallback path: the map from ItemStack#serialize(). */
    public static Map<String, Object> encodeMap(ItemStack stack) {
        if (stack == null) return null;
        try {
            return stack.serialize();
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static ItemStack decodeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return ItemStack.deserialize(map);
        } catch (Throwable t) {
            return null;
        }
    }
}
