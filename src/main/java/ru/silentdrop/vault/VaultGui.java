package ru.silentdrop.vault;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ru.silentdrop.config.ConfigHolder;
import ru.silentdrop.config.Messages;
import ru.silentdrop.config.Settings;
import ru.silentdrop.util.Text;

/**
 * Loot vault GUI (/sd vault).
 *
 * Left click takes an item, the "Take all" button takes everything that fits.
 * Any other interaction (shift click, drag, cursor pickup) is cancelled, so items
 * can neither be duplicated nor taken around the logic.
 */
public final class VaultGui implements Listener {

    private static final int CONTENT_SLOTS = 45;
    private static final int SLOT_TAKE_ALL = 49;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_INFO = 45;

    private final Plugin plugin;
    private final LootVault vault;
    private final ConfigHolder holder;

    /** inventory -> owner/page, used to tell our GUIs from foreign ones */
    private final Map<Inventory, Session> sessions = new ConcurrentHashMap<Inventory, Session>();
    /** player -> currently open inventory */
    private final Map<UUID, Inventory> openBy = new ConcurrentHashMap<UUID, Inventory>();

    public VaultGui(Plugin plugin, LootVault vault, ConfigHolder holder) {
        this.plugin = plugin;
        this.vault = vault;
        this.holder = holder;
    }

    public void open(Player player, int page) {
        Settings cfg = holder.settings();
        Messages msg = holder.messages();
        if (cfg == null || msg == null) return;

        if (!cfg.vaultEnabled) {
            msg.send(player, "vault.disabled");
            return;
        }

        List<VaultEntry> entries = vault.entries(player.getUniqueId());
        int pages = Math.max(1, (entries.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        int currentPage = Math.max(1, Math.min(page, pages));

        Map<String, String> titlePh = new LinkedHashMap<String, String>();
        titlePh.put("page", String.valueOf(currentPage));
        titlePh.put("pages", String.valueOf(pages));
        titlePh.put("player", player.getName());
        titlePh.put("count", String.valueOf(entries.size()));
        String title = Text.strip(msg.format("vault.gui-title", titlePh));
        if (title.length() > 32) title = title.substring(0, 32);

        Inventory inv = Bukkit.createInventory(new Holder(), 54, Text.color(title));
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");

        int start = (currentPage - 1) * CONTENT_SLOTS;
        for (int i = 0; i < CONTENT_SLOTS; i++) {
            int index = start + i;
            if (index >= entries.size()) break;

            VaultEntry entry = entries.get(index);
            ItemStack item = entry.item();
            if (item == null) {
                item = new ItemStack(Material.BARRIER);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(msg.format("vault.broken-entry"));
                    meta.setLore(lore(msg, "vault.broken-entry-lore", null));
                    item.setItemMeta(meta);
                }
            } else {
                ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : Bukkit.getItemFactory().getItemMeta(item.getType());
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
                    Map<String, String> ph = new LinkedHashMap<String, String>();
                    ph.put("time", sdf.format(new Date(entry.storedAt())));
                    ph.put("mob", entry.mobName().isEmpty() ? "?" : entry.mobName());
                    ph.put("reason", entry.reason().isEmpty() ? "?" : entry.reason());
                    lore.addAll(lore(msg, "vault.lore", ph));
                    lore.addAll(lore(msg, "vault.separator", null));
                    lore.addAll(lore(msg, "vault.take-hint", null));
                    meta.setLore(lore);
                    meta.addItemFlags(ItemFlag.values());
                    item.setItemMeta(meta);
                }
            }
            inv.setItem(i, item);
        }

        // navigation buttons never overlap item slots (45..53 are reserved)
        if (currentPage > 1) {
            inv.setItem(SLOT_PREV, button(Material.ARROW, msg.format("vault.prev"), null));
        }
        if (currentPage < pages) {
            inv.setItem(SLOT_NEXT, button(Material.ARROW, msg.format("vault.next"), null));
        }

        Map<String, String> infoPh = new LinkedHashMap<String, String>();
        infoPh.put("count", String.valueOf(entries.size()));
        infoPh.put("max", String.valueOf(cfg.vaultMaxEntries));
        infoPh.put("player", player.getName());
        inv.setItem(SLOT_INFO, button(Material.CHEST, msg.format("vault.info-title"),
                lore(msg, "vault.info-lore", infoPh)));

        inv.setItem(SLOT_TAKE_ALL, button(Material.HOPPER, msg.format("vault.take-all"),
                lore(msg, "vault.take-all-lore", infoPh)));

        Inventory previous = openBy.put(player.getUniqueId(), inv);
        if (previous != null) sessions.remove(previous);
        sessions.put(inv, new Session(player.getUniqueId(), currentPage));
        player.openInventory(inv);
    }

    /**
     * Message value -> lore lines. The "|" character splits lines, so every text
     * stays inside the language files and can be translated freely.
     */
    private static List<String> lore(Messages msg, String key, Map<String, String> placeholders) {
        String raw = msg.format(key, placeholders);
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\\|")) {
            out.add(line);
        }
        return out;
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Session session = sessions.get(top);
        if (session == null) return;

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!session.player.equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        if (event.getClickedInventory() != top) return; // a click inside the own inventory is simply cancelled

        int slot = event.getSlot();
        Messages msg = holder.messages();

        if (slot == SLOT_TAKE_ALL) {
            takeAll(player, msg);
            return;
        }
        if (slot == SLOT_PREV && session.page > 1) {
            open(player, session.page - 1);
            return;
        }
        if (slot == SLOT_NEXT) {
            int entries = vault.size(player.getUniqueId());
            int pages = Math.max(1, (entries + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
            if (session.page < pages) open(player, session.page + 1);
            return;
        }
        if (slot >= CONTENT_SLOTS) return; // info / navigation slots

        int index = (session.page - 1) * CONTENT_SLOTS + slot;
        ItemStack item = vault.take(player.getUniqueId(), index);
        if (item == null) {
            // corrupted entry - it is already removed by take(), just redraw
            open(player, session.page);
            return;
        }

        Map<Integer, ItemStack> rest = giveSafe(player, item);
        if (rest != null && !rest.isEmpty()) {
            // did not fit - put it back into the vault
            for (ItemStack left : rest.values()) {
                vault.store(player.getUniqueId(), left, "returned from GUI");
            }
            if (msg != null) msg.send(player, "vault.partial", single("count", "0"));
        } else if (msg != null) {
            msg.send(player, "vault.taken", single("count", String.valueOf(item.getAmount())));
        }
        open(player, session.page);
    }

    private Map<Integer, ItemStack> giveSafe(Player player, ItemStack item) {
        return player.getInventory().addItem(item);
    }

    private void takeAll(Player player, Messages msg) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        LootVault.TakeResult result = vault.takeAllReport(player.getUniqueId(), storage);
        player.getInventory().setStorageContents(storage);

        if (msg != null) {
            Map<String, String> ph = new LinkedHashMap<String, String>();
            ph.put("count", String.valueOf(result.taken));
            ph.put("left", String.valueOf(result.remaining));
            if (result.taken == 0 && result.remaining == 0) {
                msg.send(player, "vault.empty", ph);
            } else if (result.remaining > 0) {
                msg.send(player, "vault.partial", ph);
            } else {
                msg.send(player, "vault.taken", ph);
            }
        }
        open(player, 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (sessions.containsKey(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        Session session = sessions.remove(inv);
        if (session != null) {
            openBy.remove(session.player, inv);
        }
    }

    private static Map<String, String> single(String key, String value) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }

    /** Marks the inventory as ours and keeps the session data. */
    private static final class Holder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class Session {
        final UUID player;
        final int page;

        Session(UUID player, int page) {
            this.player = player;
            this.page = page;
        }
    }
}
