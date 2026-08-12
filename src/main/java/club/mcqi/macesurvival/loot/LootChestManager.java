package club.mcqi.macesurvival.loot;

import club.mcqi.macesurvival.combat.CombatManager;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Display;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

public final class LootChestManager implements LootBridge {
    private static final Key UNIFORM_FONT = Key.key("minecraft", "uniform");

    private final JavaPlugin plugin;
    private final CombatManager combat;
    private final Predicate<Player> participantPredicate;
    private final LootListener listener;
    private final LootItemFactory itemFactory;
    private final RandomGenerator random = ThreadLocalRandom.current();
    private final Map<UUID, ManagedChest> byId = new LinkedHashMap<>();
    private final Map<BlockKey, UUID> byBlock = new HashMap<>();
    private final Map<UUID, AsyncSpawnRun> activeSpawns = new HashMap<>();
    private final NamespacedKey chestIdKey;
    private final NamespacedKey chestTierKey;

    public LootChestManager(JavaPlugin plugin, CombatManager combat) {
        this(plugin, combat, combat::isParticipant);
    }

    public LootChestManager(JavaPlugin plugin, CombatManager combat, Predicate<Player> participantPredicate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combat = Objects.requireNonNull(combat, "combat");
        this.participantPredicate = Objects.requireNonNull(participantPredicate, "participantPredicate");
        itemFactory = new LootItemFactory(plugin, combat);
        chestIdKey = new NamespacedKey(plugin, "loot_chest_id");
        chestTierKey = new NamespacedKey(plugin, "loot_chest_tier");
        listener = new LootListener(this, combat);
    }

    public LootListener listener() {
        return listener;
    }

    public void reload() {
        itemFactory.reload();
    }

    public LootChestSnapshot spawnChest(Location location, LootTier tier) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(tier, "tier");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        Location blockLocation = new Location(world, location.getBlockX(), location.getBlockY(), location.getBlockZ());
        Block block = blockLocation.getBlock();
        BlockKey key = BlockKey.of(block);
        if (byBlock.containsKey(key)) {
            throw new IllegalArgumentException("A managed loot chest already exists at " + blockLocation);
        }
        if (!block.isPassable() && !block.getType().isAir()) {
            throw new IllegalArgumentException("Loot chest location is occupied by " + block.getType());
        }

        block.setType(Material.CHEST, false);
        if (!(block.getState() instanceof Chest chest)) {
            throw new IllegalStateException("Failed to create chest at " + blockLocation);
        }
        UUID id = UUID.randomUUID();
        chest.getPersistentDataContainer().set(chestIdKey, PersistentDataType.STRING, id.toString());
        chest.getPersistentDataContainer().set(chestTierKey, PersistentDataType.INTEGER, tier.stars());
        chest.update(true, false);

        TextDisplay display = spawnStarDisplay(blockLocation, tier);
        ManagedChest managed = new ManagedChest(id, blockLocation, tier, display);
        byId.put(id, managed);
        byBlock.put(key, id);
        fillChest(chest.getBlockInventory(), tier, id);
        return managed.snapshot();
    }

    public List<LootChestSnapshot> spawnRandomSurfaceChests(
            World world,
            double centerX,
            double centerZ,
            double radius,
            int count,
            double matchProgress
    ) {
        Objects.requireNonNull(world, "world");
        if (radius <= 0.0 || count < 0) {
            throw new IllegalArgumentException("Radius must be positive and count cannot be negative");
        }
        List<LootChestSnapshot> spawned = new ArrayList<>();
        int attempts = Math.max(64, count * 40);
        int spacing = Math.max(1, plugin.getConfig().getInt("loot.minimum-chest-spacing", 4));
        double spacingSquared = spacing * (double) spacing;
        while (spawned.size() < count && attempts-- > 0) {
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double distance = Math.sqrt(random.nextDouble()) * radius;
            int x = (int) Math.floor(centerX + Math.cos(angle) * distance);
            int z = (int) Math.floor(centerZ + Math.sin(angle) * distance);
            int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block base = world.getBlockAt(x, surfaceY, z);
            Block destination = world.getBlockAt(x, surfaceY + 1, z);
            boolean tooClose = byId.values().stream()
                .filter(chest -> chest.location.getWorld().equals(world))
                .anyMatch(chest -> {
                    double dx = chest.location.getX() - destination.getX();
                    double dz = chest.location.getZ() - destination.getZ();
                    return dx * dx + dz * dz < spacingSquared;
                });
            if (!base.getType().isSolid() || (!destination.isPassable() && !destination.getType().isAir())
                || byBlock.containsKey(BlockKey.of(destination)) || tooClose) {
                continue;
            }
            try {
                spawned.add(spawnChest(destination.getLocation(), rollTier(matchProgress)));
            } catch (IllegalArgumentException ignored) {
                // Another placement may have claimed this block during the same generation pass.
            }
        }
        return List.copyOf(spawned);
    }

    public CompletableFuture<List<LootChestSnapshot>> spawnRandomSurfaceChestsAsync(
            World world,
            double centerX,
            double centerZ,
            double radius,
            int count,
            double matchProgress,
            int parallelChunkLoads
    ) {
        Objects.requireNonNull(world, "world");
        if (radius <= 0.0 || count < 0 || parallelChunkLoads < 1) {
            throw new IllegalArgumentException("Radius and parallelism must be positive; count cannot be negative");
        }
        AsyncSpawnRun run = new AsyncSpawnRun(
            world,
            centerX,
            centerZ,
            radius,
            count,
            matchProgress,
            Math.min(16, parallelChunkLoads),
            Math.max(64, count * 40)
        );
        Runnable start = () -> {
            AsyncSpawnRun previous = activeSpawns.put(world.getUID(), run);
            if (previous != null) {
                previous.cancel();
            }
            pumpAsyncSpawns(run);
        };
        if (Bukkit.isPrimaryThread()) {
            start.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, start);
        }
        return run.future;
    }

    public Optional<LootChestSnapshot> chest(UUID id) {
        ManagedChest managed = byId.get(id);
        return managed == null ? Optional.empty() : Optional.of(managed.snapshot());
    }

    public List<LootChestSnapshot> chests() {
        return byId.values().stream().map(ManagedChest::snapshot).toList();
    }

    public int size() {
        return byId.size();
    }

    public boolean removeChest(UUID id, boolean dropContents) {
        ManagedChest managed = byId.get(id);
        if (managed == null) {
            return false;
        }
        remove(managed, dropContents, true);
        return true;
    }

    public int destroyOutsideBoundary(World world, double centerX, double centerZ, double radius) {
        double radiusSquared = radius * radius;
        List<ManagedChest> outside = byId.values().stream()
            .filter(chest -> chest.location.getWorld().equals(world))
            .filter(chest -> {
                double dx = chest.location.getX() + 0.5 - centerX;
                double dz = chest.location.getZ() + 0.5 - centerZ;
                return dx * dx + dz * dz > radiusSquared;
            })
            .toList();
        outside.forEach(chest -> remove(chest, false, true));
        return outside.size();
    }

    public void clearAll(boolean dropContents) {
        activeSpawns.values().forEach(AsyncSpawnRun::cancel);
        activeSpawns.clear();
        List.copyOf(byId.values()).forEach(chest -> remove(chest, dropContents, false));
    }

    boolean isParticipant(Player player) {
        return participantPredicate.test(player);
    }

    ManagedChest find(Inventory inventory) {
        if (!(inventory.getHolder() instanceof Chest chest)) {
            return null;
        }
        UUID id = byBlock.get(BlockKey.of(chest.getBlock()));
        return id == null ? null : byId.get(id);
    }

    boolean isManaged(Block block) {
        return byBlock.containsKey(BlockKey.of(block));
    }

    void opened(ManagedChest managed, Player player) {
        if (managed.removing) {
            return;
        }
        managed.opened = true;
        managed.viewers.add(player.getUniqueId());
        if (managed.disappearTask != null) {
            managed.disappearTask.cancel();
            managed.disappearTask = null;
        }
    }

    void closed(ManagedChest managed, Player player) {
        managed.viewers.remove(player.getUniqueId());
        if (managed.removing || !managed.opened || !managed.viewers.isEmpty()) {
            return;
        }
        if (managed.disappearTask != null) {
            managed.disappearTask.cancel();
        }
        long delay = Math.max(1, plugin.getConfig().getLong("loot.disappear-delay-seconds", 3)) * 20L;
        managed.disappearTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            managed.disappearTask = null;
            if (!managed.removing && managed.viewers.isEmpty() && byId.containsKey(managed.id)) {
                remove(managed, true, true);
            }
        }, delay);
    }

    private void fillChest(Inventory inventory, LootTier tier, UUID chestId) {
        inventory.clear();
        int minimum = Math.max(1, Math.min(inventory.getSize(),
            plugin.getConfig().getInt("loot.items-min", 4)));
        int maximum = Math.max(minimum, Math.min(inventory.getSize(),
            plugin.getConfig().getInt("loot.items-max", 8)));
        int itemCount = random.nextInt(minimum, maximum + 1);
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            slots.add(slot);
        }
        Collections.shuffle(slots, new java.util.Random(random.nextLong()));
        for (int index = 0; index < itemCount; index++) {
            inventory.setItem(slots.get(index), itemFactory.create(tier, chestId));
        }
    }

    private TextDisplay spawnStarDisplay(Location blockLocation, LootTier tier) {
        Location displayLocation = blockLocation.clone().add(0.5, 1.35, 0.5);
        return blockLocation.getWorld().spawn(displayLocation, TextDisplay.class, display -> {
            display.text(Component.text("★".repeat(tier.stars()), tier.color())
                .font(UNIFORM_FONT)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(true);
            display.setSeeThrough(false);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setLineWidth(200);
            float viewRange = (float) Math.max(8.0D, Math.min(128.0D,
                plugin.getConfig().getDouble("loot.star-display-view-range", 48.0D)));
            display.setViewRange(viewRange);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setGlowing(true);
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
        });
    }

    @Override
    public void spawnInitial(World world, int alivePlayers) {
        refresh(world, alivePlayers);
    }

    @Override
    public void refresh(World world, int alivePlayers) {
        int target = Math.max(0, alivePlayers) * Math.max(0,
            plugin.getConfig().getInt("loot.chests-per-alive-player", 8));
        int existing = (int) byId.values().stream()
            .filter(chest -> chest.location.getWorld().equals(world))
            .count();
        int missing = Math.max(0, target - existing);
        if (missing == 0) {
            return;
        }
        org.bukkit.WorldBorder border = world.getWorldBorder();
        double radius = border.getSize() / 2.0;
        double initialRadius = plugin.getConfig().getIntegerList("border.radii").stream()
            .findFirst().orElse(3000);
        double progress = 1.0 - Math.min(1.0, radius / Math.max(1.0, initialRadius));
        int parallelism = Math.max(1, Math.min(16,
            plugin.getConfig().getInt("loot.parallel-chunk-loads", 8)));
        spawnRandomSurfaceChestsAsync(
            world,
            border.getCenter().getX(),
            border.getCenter().getZ(),
            Math.max(8.0, radius - 4.0),
            missing,
            progress,
            parallelism
        );
    }

    @Override
    public void removeOutside(org.bukkit.WorldBorder border) {
        World world = border.getWorld();
        if (world == null) {
            return;
        }
        destroyOutsideBoundary(
            world,
            border.getCenter().getX(),
            border.getCenter().getZ(),
            border.getSize() / 2.0
        );
    }

    @Override
    public void clear() {
        clearAll(false);
    }

    private void remove(ManagedChest managed, boolean dropContents, boolean effects) {
        if (managed.removing) {
            return;
        }
        managed.removing = true;
        if (managed.disappearTask != null) {
            managed.disappearTask.cancel();
            managed.disappearTask = null;
        }
        byId.remove(managed.id);
        byBlock.remove(BlockKey.of(managed.location.getBlock()));

        Block block = managed.location.getBlock();
        if (block.getState() instanceof Chest chest) {
            Inventory inventory = chest.getBlockInventory();
            for (HumanEntity viewer : List.copyOf(inventory.getViewers())) {
                viewer.closeInventory();
            }
            if (dropContents) {
                Location dropLocation = managed.location.clone().add(0.5, 0.6, 0.5);
                for (ItemStack item : inventory.getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        managed.location.getWorld().dropItemNaturally(dropLocation, item.clone());
                    }
                }
            }
            inventory.clear();
        }
        block.setType(Material.AIR, false);
        if (managed.display.isValid()) {
            managed.display.remove();
        }
        managed.viewers.clear();
        if (effects) {
            playDisappearance(managed.location);
        }
    }

    private void playDisappearance(Location blockLocation) {
        World world = blockLocation.getWorld();
        Location center = blockLocation.clone().add(0.5, 0.55, 0.5);
        world.playSound(center, Sound.BLOCK_VAULT_DEACTIVATE, SoundCategory.BLOCKS, 1.4f, 1.0f);
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> world.playSound(center, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE,
                SoundCategory.BLOCKS, 1.1f, 1.0f), 3L);
        Particle.DustOptions redDust = new Particle.DustOptions(Color.fromRGB(210, 20, 28), 1.25f);
        for (int step = 0; step < 5; step++) {
            int frame = step;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                double radius = 0.75 - frame * 0.14;
                world.spawnParticle(Particle.DUST, center, 18,
                    radius, Math.max(0.08, radius * 0.65), radius, 0.0, redDust);
            }, step * 2L);
        }
    }

    private void pumpAsyncSpawns(AsyncSpawnRun run) {
        if (run.cancelled) {
            return;
        }
        while (run.inFlight < run.parallelism
            && run.spawned.size() + run.inFlight < run.targetCount
            && run.attemptsRemaining > 0) {
            SpawnCoordinate coordinate = randomCoordinate(run.centerX, run.centerZ, run.radius);
            run.attemptsRemaining--;
            run.inFlight++;
            run.world.getChunkAtAsync(coordinate.x >> 4, coordinate.z >> 4, true)
                .whenComplete((chunk, error) -> {
                    if (run.cancelled || !plugin.isEnabled()) {
                        run.cancel();
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(plugin,
                        () -> finishAsyncAttempt(run, coordinate, error));
                });
        }
        completeAsyncRunIfDone(run);
    }

    private void finishAsyncAttempt(AsyncSpawnRun run, SpawnCoordinate coordinate, Throwable error) {
        if (run.cancelled) {
            return;
        }
        run.inFlight--;
        if (error == null) {
            trySpawnSurfaceChest(run.world, coordinate.x, coordinate.z, run.matchProgress)
                .ifPresent(run.spawned::add);
        }
        pumpAsyncSpawns(run);
    }

    private void completeAsyncRunIfDone(AsyncSpawnRun run) {
        boolean complete = run.spawned.size() >= run.targetCount
            || (run.attemptsRemaining <= 0 && run.inFlight == 0);
        if (!complete || run.cancelled) {
            return;
        }
        activeSpawns.remove(run.world.getUID(), run);
        run.future.complete(List.copyOf(run.spawned));
    }

    private Optional<LootChestSnapshot> trySpawnSurfaceChest(
            World world,
            int x,
            int z,
            double matchProgress
    ) {
        int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block base = world.getBlockAt(x, surfaceY, z);
        Block destination = world.getBlockAt(x, surfaceY + 1, z);
        if (!base.getType().isSolid() || (!destination.isPassable() && !destination.getType().isAir())
            || byBlock.containsKey(BlockKey.of(destination)) || isTooCloseToExisting(world, x, z)) {
            return Optional.empty();
        }
        try {
            return Optional.of(spawnChest(destination.getLocation(), rollTier(matchProgress)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private boolean isTooCloseToExisting(World world, int x, int z) {
        int spacing = Math.max(1, plugin.getConfig().getInt("loot.minimum-chest-spacing", 4));
        double spacingSquared = spacing * (double) spacing;
        return byId.values().stream()
            .filter(chest -> chest.location.getWorld().equals(world))
            .anyMatch(chest -> {
                double dx = chest.location.getBlockX() - x;
                double dz = chest.location.getBlockZ() - z;
                return dx * dx + dz * dz < spacingSquared;
            });
    }

    private LootTier rollTier(double matchProgress) {
        List<Integer> configured = plugin.getConfig().getIntegerList("loot.chest-tier-weights");
        List<Integer> weights = configured.size() >= 3 ? configured : List.of(55, 30, 15);
        double oneBase = Math.max(1.0, weights.get(0));
        double twoBase = Math.max(1.0, weights.get(1));
        double threeBase = Math.max(1.0, weights.get(2));
        double total = oneBase + twoBase + threeBase;
        double progress = Math.max(0.0, Math.min(1.0, matchProgress));
        double one = oneBase * (1.0 - 0.64 * progress);
        double two = twoBase + total * 0.10 * progress;
        double three = Math.max(1.0, total - one - two);
        double roll = random.nextDouble(one + two + three);
        if (roll < one) {
            return LootTier.ONE;
        }
        if (roll < one + two) {
            return LootTier.TWO;
        }
        return LootTier.THREE;
    }

    private SpawnCoordinate randomCoordinate(double centerX, double centerZ, double radius) {
        double angle = random.nextDouble(0.0, Math.PI * 2.0);
        double distance = Math.sqrt(random.nextDouble()) * radius;
        return new SpawnCoordinate(
            (int) Math.floor(centerX + Math.cos(angle) * distance),
            (int) Math.floor(centerZ + Math.sin(angle) * distance)
        );
    }

    static final class ManagedChest {
        private final UUID id;
        private final Location location;
        private final LootTier tier;
        private final TextDisplay display;
        private final Set<UUID> viewers = new HashSet<>();
        private boolean opened;
        private boolean removing;
        private BukkitTask disappearTask;

        private ManagedChest(UUID id, Location location, LootTier tier, TextDisplay display) {
            this.id = id;
            this.location = location.clone();
            this.tier = tier;
            this.display = display;
        }

        LootChestSnapshot snapshot() {
            return new LootChestSnapshot(id, location, tier, opened, viewers.size());
        }

        UUID id() {
            return id;
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record SpawnCoordinate(int x, int z) { }

    private static final class AsyncSpawnRun {
        private final World world;
        private final double centerX;
        private final double centerZ;
        private final double radius;
        private final int targetCount;
        private final double matchProgress;
        private final int parallelism;
        private final List<LootChestSnapshot> spawned = new ArrayList<>();
        private final CompletableFuture<List<LootChestSnapshot>> future = new CompletableFuture<>();
        private int attemptsRemaining;
        private int inFlight;
        private boolean cancelled;

        private AsyncSpawnRun(
                World world,
                double centerX,
                double centerZ,
                double radius,
                int targetCount,
                double matchProgress,
                int parallelism,
                int attemptsRemaining
        ) {
            this.world = world;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.targetCount = targetCount;
            this.matchProgress = matchProgress;
            this.parallelism = parallelism;
            this.attemptsRemaining = attemptsRemaining;
        }

        private void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            future.completeExceptionally(new CancellationException("Loot chest generation was replaced"));
        }
    }
}
