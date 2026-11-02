package org.qiyuan.macesurvival.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.game.GameState;
import org.qiyuan.macesurvival.game.Squad;
import org.qiyuan.macesurvival.data.StatsManager;
import org.qiyuan.macesurvival.loadout.LayoutStore;
import org.qiyuan.macesurvival.text.Text;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configured chest menus. Each view owns its holder, actions and preview cursor. */
public final class MenuManager {
    private static final List<String> IDS = List.of("main", "team", "stats", "spectate", "layout");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    private final MaceSurvival plugin;
    private final Map<String, FileConfiguration> configs = new HashMap<>();
    private final Map<StatsManager.Board, List<StatsManager.Record>> leaderboardCache = new HashMap<>();
    private int leaderboardCacheTick = Integer.MIN_VALUE;
    private final NamespacedKey previewSession;
    private final NamespacedKey previewIdentity;

    public static final class View implements InventoryHolder {
        private final UUID owner;
        private final UUID token = UUID.randomUUID();
        private final String id;
        private final Map<Integer, String> actions = new HashMap<>();
        private Inventory inventory;
        private int page;
        private int lastActionTick = Integer.MIN_VALUE;
        private List<String> order;
        private List<Integer> editSlots = List.of();
        private String held = "";
        private int returnSlot = -1;
        private boolean closed;
        private boolean layoutPending;
        private boolean staticPreview;
        private StatsManager.Board board = StatsManager.Board.WINS;
        private View(UUID owner, String id, int page) { this.owner = owner; this.id = id; this.page = page; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
        public UUID owner() { return owner; }
        public String id() { return id; }
    }

    public MenuManager(MaceSurvival plugin) {
        this.plugin = plugin;
        previewSession = new NamespacedKey(plugin, "layout_preview_session");
        previewIdentity = new NamespacedKey(plugin, "layout_preview_identity");
        reload();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, 20L, 20L);
    }

    public void reload() {
        configs.clear();
        for (String id : IDS) {
            String path = "menu/" + id + ".yml";
            var config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), path));
            var resource = plugin.getResource(path);
            if (resource != null) {
                try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                    config.setDefaults(YamlConfiguration.loadConfiguration(reader));
                } catch (java.io.IOException exception) {
                    plugin.getLogger().severe("Could not read default menu " + id + ": " + exception.getMessage());
                }
            }
            configs.put(id, config);
        }
    }

    public void open(Player player, String id) { open(player, id, 0); }
    private void open(Player player, String id, int page) {
        if (!configs.containsKey(id)) return;
        if ((id.equals("team") || id.equals("layout")) && !editable()) { feedback(player, id, "round-active"); return; }
        if (id.equals("spectate") && !plugin.game().getPlayer(player).isSpectator()) { feedback(player, id, "not-spectator"); return; }
        String permission = "macesurvival." + switch (id) { case "main" -> "menu"; default -> id; };
        if (!player.hasPermission(permission)) { feedback(player, id, "no-permission"); return; }
        if (id.equals("layout") && !empty(player.getItemOnCursor()) && previewKey(player.getItemOnCursor()) == null) {
            feedback(player, id, "cursor-busy"); return;
        }
        View view = new View(player.getUniqueId(), id, page);
        int size = Math.max(id.equals("layout") ? 27 : 9, Math.min(54, config(id).getInt("size", id.equals("layout") ? 45 : 54)));
        size -= size % 9;
        view.inventory = Bukkit.createInventory(view, size, Text.parse(resolve(config(id).getString("title", "重锤生存"), values(player))));
        if (id.equals("layout")) {
            view.order = plugin.layouts().playerSlots(player.getUniqueId());
            view.editSlots = editingSlots(view);
        }
        render(player, view);
        player.openInventory(view.inventory);
        playSound(player, "open_sound", id);
    }

    public boolean isMenu(Inventory inventory) { return inventory.getHolder(false) instanceof View; }
    public View view(Inventory inventory) { return inventory.getHolder(false) instanceof View view ? view : null; }
    public boolean isPreview(ItemStack stack) { return previewKey(stack) != null; }
    public void openLootPreview(Player player, org.qiyuan.macesurvival.arena.ChestTier tier) {
        View view = new View(player.getUniqueId(), "main", 0); view.staticPreview = true;
        view.inventory = Bukkit.createInventory(view, 36, plugin.messages().get("command.loot.title"));
        Inventory sample = Bukkit.createInventory(null, 27);
        plugin.loot().fillChest(sample, tier);
        for (int slot = 0; slot < 27; slot++) view.inventory.setItem(slot, sample.getItem(slot));
        var navigation = config("main").getConfigurationSection(plugin.configs().main().getBoolean("menu.close-as-back", false) ? "root-back" : "items.close");
        put(view, 31, build(navigation, values(player)), "close");
        player.openInventory(view.inventory);
    }
    private boolean editable() { return plugin.game().state() == GameState.WAITING || plugin.game().state() == GameState.COUNTDOWN; }
    private FileConfiguration config(String id) { return configs.get(id); }

    private void render(Player player, View view) {
        view.inventory.clear();
        view.actions.clear();
        var items = config(view.id).getConfigurationSection("items");
        Map<String, String> values = values(player);
        if (items != null) for (String key : items.getKeys(false)) {
            if (key.equals("back") && view.id.equals("main") || key.equals("close") && !view.id.equals("main")) continue;
            var item = items.getConfigurationSection(key);
            if (item == null) continue;
            for (int slot : slots(item)) put(view, slot, build(item, values), key);
        }
        if (view.id.equals("main") && plugin.configs().main().getBoolean("menu.close-as-back", false)) {
            var rootBack = config("main").getConfigurationSection("root-back");
            int slot = config("main").getInt("items.close.slot", view.inventory.getSize() - 1);
            if (rootBack != null) put(view, slot, build(rootBack, values), "close");
        }
        switch (view.id) {
            case "team" -> renderTeams(player, view);
            case "spectate" -> renderSpectators(player, view);
            case "layout" -> renderLayout(player, view);
            case "stats" -> renderStats(player, view);
            default -> { }
        }
    }

    private void renderTeams(Player player, View view) {
        var teams = plugin.game().squads().stream().filter(s -> !s.members().isEmpty()).sorted(Comparator.comparingInt(Squad::id)).toList();
        List<Integer> content = contentSlots(view);
        if (content.isEmpty()) return;
        int pages = Math.max(1, (teams.size() + content.size() - 1) / content.size());
        view.page = Math.max(0, Math.min(view.page, pages - 1));
        if (teams.isEmpty()) placeTemplate(view, "empty", values(player), null);
        for (int i = 0; i < content.size() && view.page * content.size() + i < teams.size(); i++) {
            Squad team = teams.get(view.page * content.size() + i);
            Map<String, String> vars = new HashMap<>(values(player));
            vars.put("team", safe(team.name()));
            vars.put("team_id", String.valueOf(team.id()));
            vars.put("members", String.valueOf(team.members().size()));
            vars.put("leader", playerName(team.leader()));
            vars.put("member_names", String.join("、", team.members().stream().map(this::playerName).toList()));
            vars.put("join_state", team.members().contains(player.getUniqueId()) ? config("team").getString("labels.current", "当前队伍") : team.size() >= squadSize() ? config("team").getString("labels.full", "队伍已满") : config("team").getString("labels.join", "点击加入队伍"));
            put(view, content.get(i), build(config("team").getConfigurationSection("entry"), vars), "join:" + team.id());
        }
        pagination(view, pages);
    }

    private void renderSpectators(Player player, View view) {
        var alive = plugin.game().getAlivePlayers().stream().map(gp -> Bukkit.getPlayer(gp.uuid())).filter(p -> p != null && p.isOnline()).sorted(Comparator.comparing(Player::getName)).toList();
        List<Integer> content = contentSlots(view);
        if (content.isEmpty()) return;
        int pages = Math.max(1, (alive.size() + content.size() - 1) / content.size());
        view.page = Math.max(0, Math.min(view.page, pages - 1));
        if (alive.isEmpty()) placeTemplate(view, "empty", values(player), null);
        for (int i = 0; i < content.size() && view.page * content.size() + i < alive.size(); i++) {
            Player target = alive.get(view.page * content.size() + i);
            Map<String, String> vars = new HashMap<>(values(player));
            vars.put("target", safe(target.getName()));
            vars.put("health", String.format(Locale.ROOT, "%.1f", target.getHealth()));
            var gp = plugin.game().getPlayer(target);
            vars.put("target_team", gp.squad() == null ? config("spectate").getString("labels.solo", "单人生存") : safe(gp.squad().name()));
            vars.put("target_kills", String.valueOf(gp.roundKills()));
            ItemStack stack = build(config("spectate").getConfigurationSection("entry"), vars);
            if (stack.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull) { skull.setOwningPlayer(target); stack.setItemMeta(skull); }
            put(view, content.get(i), stack, "watch:" + target.getUniqueId());
        }
        pagination(view, pages);
    }

    private void pagination(View view, int pages) {
        Map<String, String> vars = Map.of("page", String.valueOf(view.page + 1), "pages", String.valueOf(pages));
        if (view.page > 0) placeTemplate(view, "previous", vars, "previous");
        if (view.page + 1 < pages) placeTemplate(view, "next", vars, "next");
    }

    /** The original five leaderboards share one cycling selector and real records. */
    private void renderStats(Player player, View view) {
        if (leaderboardCacheTick != Bukkit.getCurrentTick()) { leaderboardCache.clear(); leaderboardCacheTick = Bukkit.getCurrentTick(); }
        var records = leaderboardCache.computeIfAbsent(view.board, board -> plugin.stats().top(board, Integer.MAX_VALUE).stream().filter(record -> record.games() > 0 || record.kills() > 0 || record.wins() > 0).toList());
        Map<String, String> vars = new HashMap<>(values(player));
        String label = config("stats").getString("board-labels." + view.board.name().toLowerCase(Locale.ROOT), view.board.name());
        vars.put("board", label);
        int rank = -1;
        for (int i = 0; i < records.size(); i++) if (records.get(i).uuid().equals(player.getUniqueId())) { rank = i + 1; break; }
        vars.put("rank", rank < 0 ? config("stats").getString("unranked", "暂无排名") : "#" + rank);
        placeTemplate(view, "record-header", vars, null);
        ItemStack selector = build(config("stats").getConfigurationSection("selector"), vars);
        var meta = selector.getItemMeta();
        List<Component> lore = new ArrayList<>();
        for (StatsManager.Board board : StatsManager.Board.values()) {
            String boardLabel = config("stats").getString("board-labels." + board.name().toLowerCase(Locale.ROOT), board.name());
            String format = config("stats").getString(board == view.board ? "selector-selected" : "selector-idle", "{board}");
            lore.add(Text.parse(resolve(format, Map.of("board", boardLabel))));
        }
        config("stats").getStringList("selector-hint").forEach(line -> lore.add(Text.parse(line)));
        meta.lore(lore); selector.setItemMeta(meta);
        put(view, config("stats").getInt("selector.slot", 13), selector, "board");
        List<Integer> content = contentSlots(view);
        if (content.isEmpty()) return;
        int pages = Math.max(1, (records.size() + content.size() - 1) / content.size());
        view.page = Math.max(0, Math.min(view.page, pages - 1));
        if (records.isEmpty()) placeTemplate(view, "empty", vars, null);
        for (int i = 0; i < content.size() && view.page * content.size() + i < records.size(); i++) {
            var record = records.get(view.page * content.size() + i);
            Map<String, String> entry = new HashMap<>(vars);
            entry.put("rank", String.valueOf(view.page * content.size() + i + 1));
            entry.put("player", safe(record.name()));
            entry.put("wins", String.valueOf(record.wins())); entry.put("kills", String.valueOf(record.kills()));
            entry.put("games", String.valueOf(record.games())); entry.put("deaths", String.valueOf(record.deaths()));
            entry.put("kd", String.format(Locale.ROOT, "%.2f", record.kd())); entry.put("streak", String.valueOf(record.bestStreak()));
            put(view, content.get(i), build(config("stats").getConfigurationSection("entry"), entry), null);
        }
        pagination(view, pages);
    }

    private void renderLayout(Player player, View view) {
        for (int i = 0; i < 9; i++) {
            String key = view.order.get(i);
            ItemStack stack = key.isEmpty() ? null : preview(view, key);
            view.inventory.setItem(view.editSlots.get(i), stack);
            view.actions.remove(view.editSlots.get(i));
        }
        placeTemplate(view, "ready", values(player), null);
    }

    private ItemStack preview(View view, String key) {
        ItemStack stack = plugin.loadouts().preview(key);
        stack.setAmount(1);
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(previewSession, PersistentDataType.STRING, view.token.toString());
        meta.getPersistentDataContainer().set(previewIdentity, PersistentDataType.STRING, key);
        Map<String, String> vars = Map.of("amount", String.valueOf(plugin.loadouts().startingAmount(key)));
        meta.lore(config("layout").getStringList("preview-lore").stream().map(line -> Text.parse(resolve(line, vars))).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    public void handleClick(Player player, InventoryClickEvent event) {
        View view = view(event.getView().getTopInventory());
        if (view == null || view.closed || !view.owner.equals(player.getUniqueId())) return;
        if (view.id.equals("layout") && view.editSlots.contains(event.getRawSlot())) { layoutClick(player, event, view); return; }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= view.inventory.getSize()) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        String action = view.actions.get(event.getRawSlot());
        if (action == null || Bukkit.getCurrentTick() == view.lastActionTick) return;
        view.lastActionTick = Bukkit.getCurrentTick();
        switch (action) {
            case "team", "stats", "layout", "spectate" -> defer(player, view, () -> open(player, action));
            case "back" -> defer(player, view, () -> open(player, "main"));
            case "close" -> defer(player, view, () -> {
                player.closeInventory();
                if (plugin.configs().main().getBoolean("menu.close-as-back", false)) {
                    String command = plugin.configs().main().getString("menu.back-command", "menu");
                    if (command != null && !command.isBlank()) player.performCommand(command.replaceFirst("^/", ""));
                }
            });
            case "create" -> {
                if (!editable()) { feedback(player, view.id, "round-active"); return; }
                defer(player, view, () -> { player.closeInventory(); plugin.game().beginTeamInput(player); });
            }
            case "leave" -> {
                if (!editable()) { feedback(player, view.id, "round-active"); return; }
                plugin.game().leaveTeam(player); render(player, view);
            }
            case "previous" -> { view.page--; render(player, view); }
            case "next" -> { view.page++; render(player, view); }
            case "board" -> { view.board = event.isRightClick() ? view.board.previous() : view.board.next(); view.page = 0; render(player, view); }
            default -> {
                if (action.startsWith("join:") && editable()) {
                    plugin.game().joinTeam(player, Integer.parseInt(action.substring(5))); render(player, view);
                } else if (action.startsWith("watch:")) {
                    UUID targetId = UUID.fromString(action.substring(6));
                    defer(player, view, () -> {
                        Player target = Bukkit.getPlayer(targetId);
                        if (!plugin.game().getPlayer(player).isSpectator()) { feedback(player, "spectate", "not-spectator"); return; }
                        if (target == null || plugin.game().getAlivePlayers().stream().noneMatch(gp -> gp.uuid().equals(targetId))) { feedback(player, "spectate", "target-gone"); open(player, "spectate"); return; }
                        player.closeInventory();
                        if (player.teleport(target.getLocation())) player.setSpectatorTarget(target);
                    });
                } else return;
            }
        }
        playSound(player, "click_sound", view.id);
    }

    /** Layout actions never invoke vanilla movement: only tagged preview clones move. */
    private void layoutClick(Player player, InventoryClickEvent event, View view) {
        if (!editable() || view.layoutPending || event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        if (!empty(player.getItemOnCursor()) && !belongs(player.getItemOnCursor(), view)) return;
        int index = view.editSlots.indexOf(event.getRawSlot());
        if (view.held.isEmpty() && view.order.get(index).isEmpty()) return;
        layoutTask(player, view, () -> {
            if (!empty(player.getItemOnCursor()) && !belongs(player.getItemOnCursor(), view)) return;
            String at = view.order.get(index);
            if (view.held.isEmpty()) view.returnSlot = index;
            view.order.set(index, view.held);
            view.held = at;
            view.inventory.setItem(view.editSlots.get(index), view.order.get(index).isEmpty() ? null : preview(view, view.order.get(index)));
            player.setItemOnCursor(view.held.isEmpty() ? null : preview(view, view.held));
            if (view.held.isEmpty()) { view.returnSlot = -1; saveLayout(player, view); }
        });
    }

    public void handleDrag(Player player, InventoryDragEvent event) {
        View view = view(event.getView().getTopInventory());
        if (view == null || !view.id.equals("layout") || !editable() || view.layoutPending || !view.owner.equals(player.getUniqueId()) || !belongs(event.getOldCursor(), view) || view.held.isEmpty()) return;
        if (event.getRawSlots().size() != 1) return;
        int raw = event.getRawSlots().iterator().next();
        int index = view.editSlots.indexOf(raw);
        if (index < 0 || !view.order.get(index).isEmpty()) return;
        layoutTask(player, view, () -> {
            if (view.held.isEmpty() || !belongs(player.getItemOnCursor(), view) || !view.order.get(index).isEmpty()) return;
            view.order.set(index, view.held);
            view.inventory.setItem(raw, preview(view, view.held));
            view.held = "";
            view.returnSlot = -1;
            player.setItemOnCursor(null);
            saveLayout(player, view);
        });
    }

    /** Drag cancellation restores the old cursor after its event; mutate next tick. */
    private void layoutTask(Player player, View view, Runnable action) {
        view.layoutPending = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            view.layoutPending = false;
            if (!view.closed && editable() && player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == view) action.run();
        });
    }

    private void saveLayout(Player player, View view) {
        if (!view.held.isEmpty()) return;
        if (plugin.layouts().save(player.getUniqueId(), view.order)) placeTemplate(view, "saved", values(player), null);
        else { placeTemplate(view, "save-error", values(player), null); feedback(player, "layout", "save-error"); }
    }

    public void onClose(Player player, Inventory inventory) {
        View view = view(inventory);
        if (view == null || view.closed || !view.owner.equals(player.getUniqueId())) return;
        view.closed = true;
        if (view.id.equals("layout")) {
            if (!view.held.isEmpty()) {
                int empty = view.returnSlot >= 0 && view.order.get(view.returnSlot).isEmpty() ? view.returnSlot : view.order.indexOf("");
                if (empty >= 0) view.order.set(empty, view.held);
                view.held = "";
            }
            if (belongs(player.getItemOnCursor(), view)) player.setItemOnCursor(null);
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                if (belongs(player.getInventory().getItem(slot), view)) player.getInventory().setItem(slot, null);
            }
            saveLayout(player, view);
        }
    }

    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) if (isMenu(player.getOpenInventory().getTopInventory())) player.closeInventory();
    }

    private void defer(Player player, View view, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == view) action.run(); });
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            View view = view(player.getOpenInventory().getTopInventory());
            if (view == null) continue;
            if ((view.id.equals("team") || view.id.equals("layout")) && !editable() || view.id.equals("spectate") && !plugin.game().getPlayer(player).isSpectator()) {
                player.closeInventory(); continue;
            }
            if (!view.id.equals("layout") && !view.staticPreview) render(player, view);
        }
    }

    private List<Integer> editingSlots(View view) {
        var configured = config("layout").getIntegerList("edit-slots");
        if (configured.size() == 9 && configured.stream().distinct().count() == 9 && configured.stream().allMatch(slot -> slot >= 0 && slot < view.inventory.getSize())) return List.copyOf(configured);
        int first = view.inventory.getSize() >= 36 ? 18 : 9;
        return java.util.stream.IntStream.range(first, first + 9).boxed().toList();
    }
    private String previewKey(ItemStack stack) { return empty(stack) || !stack.hasItemMeta() ? null : stack.getItemMeta().getPersistentDataContainer().get(previewIdentity, PersistentDataType.STRING); }
    private boolean belongs(ItemStack stack, View view) {
        return previewKey(stack) != null && view.token.toString().equals(stack.getItemMeta().getPersistentDataContainer().get(previewSession, PersistentDataType.STRING));
    }
    private static boolean empty(ItemStack stack) { return stack == null || stack.getType().isAir(); }
    private void placeTemplate(View view, String path, Map<String, String> vars, String action) {
        var section = config(view.id).getConfigurationSection(path);
        if (section != null) put(view, section.getInt("slot", 0), build(section, vars), action);
    }
    private void put(View view, int slot, ItemStack item, String action) {
        if (slot < 0 || slot >= view.inventory.getSize()) return;
        view.inventory.setItem(slot, item);
        if (action == null) view.actions.remove(slot); else view.actions.put(slot, action);
    }
    private List<Integer> slots(ConfigurationSection section) {
        List<Integer> result = new ArrayList<>();
        for (Object value : section.getList("slots", List.of())) {
            if (value instanceof Number number) result.add(number.intValue());
            else if (value instanceof String raw) {
                String[] parts = raw.trim().split("-", 2);
                try {
                    int start = Integer.parseInt(parts[0].trim());
                    int end = parts.length == 1 ? start : Integer.parseInt(parts[1].trim());
                    for (int slot = Math.max(0, Math.min(start, end)); slot <= Math.min(53, Math.max(start, end)); slot++) result.add(slot);
                } catch (NumberFormatException ignored) { plugin.getLogger().warning("Invalid menu slot range: " + raw); }
            }
        }
        if (section.contains("slot")) result.add(section.getInt("slot"));
        return result;
    }
    private List<Integer> contentSlots(View view) {
        return config(view.id).getIntegerList("content-slots").stream().filter(slot -> slot >= 0 && slot < view.inventory.getSize()).distinct().toList();
    }
    private ItemStack build(ConfigurationSection section, Map<String, String> vars) {
        if (section == null) return new ItemStack(Material.BARRIER);
        Material material = Material.matchMaterial(section.getString("material", "PAPER"));
        if (material == null || !material.isItem() || material.isAir()) material = Material.PAPER;
        ItemStack stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        meta.displayName(Text.parse(resolve(section.getString("display_name", section.getString("name", " ")), vars)));
        meta.lore(section.getStringList("lore").stream().map(line -> Text.parse(resolve(line, vars))).toList());
        stack.setItemMeta(meta);
        return stack;
    }
    public ItemStack lobbyItem(String action) { return build(config("main").getConfigurationSection("hotbar." + action), Map.of()); }
    public String loadoutName(String key) { return config("layout").getString("loadout-items." + key, key); }
    private String playerName(UUID uuid) { var player = Bukkit.getOfflinePlayer(uuid); return safe(player.getName() == null ? uuid.toString().substring(0, 8) : player.getName()); }
    private static String safe(String raw) { return MiniMessage.miniMessage().escapeTags(raw == null ? "" : raw); }
    private int squadSize() { return Math.max(1, Math.min(16, plugin.configs().main().getInt("squad.size", 4))); }
    private Map<String, String> values(Player player) {
        var gp = plugin.game().getPlayer(player);
        var stats = plugin.stats().get(player.getUniqueId());
        boolean soloAllowed = plugin.configs().main().getBoolean("squad.allow-solo", true);
        long eligible = plugin.game().allPlayers().stream().filter(other -> other.isAlive() && Bukkit.getPlayer(other.uuid()) != null
            && (soloAllowed || other.squad() != null)).count();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("player", safe(player.getName()));
        result.put("kills", String.valueOf(stats.kills())); result.put("deaths", String.valueOf(stats.deaths())); result.put("wins", String.valueOf(stats.wins())); result.put("games", String.valueOf(stats.games()));
        result.put("kd", String.format(Locale.ROOT, "%.2f", stats.deaths() == 0 ? stats.kills() : (double) stats.kills() / stats.deaths()));
        result.put("win_rate", String.format(Locale.ROOT, "%.1f", stats.games() == 0 ? 0 : stats.wins() * 100D / stats.games()));
        result.put("balance", plugin.economy().format(plugin.economy().balance(player)));
        result.put("currency", safe(plugin.economy().currencyName()));
        result.put("players", String.valueOf(plugin.game().totalCount())); result.put("minimum", String.valueOf(Math.max(2, plugin.configs().main().getInt("game.min-players", 2))));
        result.put("maximum", String.valueOf(Math.max(2, plugin.configs().main().getInt("game.max-players", 64))));
        result.put("eligible_players", String.valueOf(eligible));
        result.put("team_members", String.valueOf(gp.squad() == null ? 1 : gp.squad().size()));
        result.put("countdown", String.valueOf(plugin.game().countdown())); result.put("alive", String.valueOf(plugin.game().aliveCount())); result.put("alive_squads", String.valueOf(plugin.game().aliveSquads()));
        result.put("squad_size", String.valueOf(squadSize()));
        result.put("squad", gp.squad() == null ? config("main").getString(soloAllowed ? "labels.solo" : "labels.unteamed", soloAllowed ? "单人生存" : "尚未组队") : safe(gp.squad().name()));
        result.put("phase", config("main").getString("phases." + plugin.game().state().name().toLowerCase(Locale.ROOT), "等待中"));
        String countKey = plugin.game().lobbyOpen() ? "labels.players-lobby" : plugin.game().state() == GameState.BOOTING ? "labels.players-preparing" : "labels.players-round";
        result.put("players_summary", resolve(config("main").getString(countKey, "当前玩家：{players}"), result));
        result.put("solo_hint", resolve(config("main").getString(soloAllowed ? "labels.solo-allowed" : "labels.solo-disabled", soloAllowed ? "单人可直接等待开局。" : "需加入小队后才能参赛。"), result));
        result.put("leave_hint", resolve(config("team").getString(soloAllowed ? "labels.leave-solo" : "labels.leave-team-required", soloAllowed ? "离队后以单人生存身份参与" : "离队后需重新组队才能参赛"), result));
        result.put("empty_team_hint", resolve(config("team").getString(soloAllowed ? "labels.empty-solo" : "labels.empty-team-required", soloAllowed ? "你可以创建队伍，或直接单人生存。" : "请创建或加入队伍后等待开局。"), result));
        return result;
    }
    private static String resolve(String raw, Map<String, String> values) {
        if (raw == null) return "";
        Matcher matcher = PLACEHOLDER.matcher(raw); StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "—")));
        matcher.appendTail(result); return Text.placeholders(result.toString(), Bukkit.getPlayerExact(values.getOrDefault("player", "")));
    }
    private void feedback(Player player, String menu, String key) {
        String raw = config(menu).getString("feedback." + key, config("main").getString("feedback." + key, ""));
        if (raw != null && !raw.isBlank()) player.sendMessage(Text.parse(raw, player));
    }
    private void playSound(Player player, String path, String menu) {
        var section = config(menu).getConfigurationSection(path);
        if (section == null || !section.getBoolean("enabled", true)) return;
        String sound = section.getString("sound", "ui.button.click");
        player.playSound(player.getLocation(), sound, (float) section.getDouble("volume", .5), (float) section.getDouble("pitch", 1));
    }
}
