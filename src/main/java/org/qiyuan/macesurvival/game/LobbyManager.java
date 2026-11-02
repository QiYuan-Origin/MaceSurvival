package org.qiyuan.macesurvival.game;

import org.bukkit.*;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Display;
import org.bukkit.block.data.type.Leaves;
import org.qiyuan.macesurvival.text.Text;
import org.bukkit.generator.ChunkGenerator;
import org.qiyuan.macesurvival.MaceSurvival;
import java.util.Random;

/** The dedicated waiting island never edits an administrator's existing lobby world. */
public final class LobbyManager {
    private final Location spawn;
    public LobbyManager(MaceSurvival plugin) {
        var config = plugin.configs().main(); String name = config.getString("lobby.world", "mace_lobby");
        World world = Bukkit.getWorld(name);
        boolean own = name.equals("mace_lobby");
        boolean fresh = world == null && !new java.io.File(Bukkit.getWorldContainer(), name).exists();
        if (world == null) {
            var creator = new WorldCreator(name);
            if (own) creator.generator(new ChunkGenerator() {
                @Override public boolean shouldGenerateNoise() { return false; }
                @Override public boolean shouldGenerateSurface() { return false; }
                @Override public boolean shouldGenerateCaves() { return false; }
                @Override public boolean shouldGenerateDecorations() { return false; }
                @Override public boolean shouldGenerateMobs() { return false; }
                @Override public boolean shouldGenerateStructures() { return false; }
                @Override public Location getFixedSpawnLocation(World w, Random r) { return new Location(w, 0.5, 65, 0.5); }
            });
            world = creator.createWorld();
        }
        if (world == null) throw new IllegalStateException("Lobby world could not be loaded: " + name);
        int x = config.getInt("lobby.x", 0), y = config.getInt("lobby.y", 65), z = config.getInt("lobby.z", 0);
        spawn = new Location(world, x + 0.5, y, z + 0.5, (float) config.getDouble("lobby.yaw", 0), 0);
        if (own && fresh && config.getBoolean("lobby.build-island", true)) {
            buildCourt(world, x, y, z);
            TextDisplay welcome = world.spawn(new Location(world, x + 0.5, y + 5.2, z + 19.4), TextDisplay.class);
            welcome.text(Text.parse(config.getString("lobby.welcome", "<white><shadow:#404040:1>重锤大逃杀</shadow></white>\n<gray>选择落点 · 搜集补给 · 存活至终局</gray>")));
            welcome.setBillboard(Display.Billboard.CENTER); welcome.setAlignment(TextDisplay.TextAlignment.CENTER);
            welcome.setDefaultBackground(false); welcome.setBackgroundColor(Color.fromARGB(0)); welcome.setShadowed(true);
            welcome.setLineWidth(300); welcome.setViewRange(0.7f);
            world.setTime(6000); world.setStorm(false); world.setSpawnLocation(spawn);
        }
        if (own) {
            world.setTime(6000); world.setStorm(false); world.setThundering(false);
            world.setClearWeatherDuration(Integer.MAX_VALUE);
            world.setGameRule(GameRules.ADVANCE_TIME, false); world.setGameRule(GameRules.ADVANCE_WEATHER, false); world.setGameRule(GameRules.SPAWN_MOBS, false);
        }
    }
    /** Open courtyard: clear routes cross a planted, structurally supported waiting island. */
    private static void buildCourt(World world, int ox, int oy, int oz) {
        for (int x = -28; x <= 28; x++) for (int z = -28; z <= 28; z++) {
            double r = Math.hypot(x, z), edge = 27 + Math.sin(Math.atan2(z, x) * 5) * 0.7;
            if (r > edge) continue;
            int depth = 3 + (int) ((1 - r / 29) * 10) + (int) (Math.sin(x * .35) * Math.cos(z * .3) * 2);
            for (int h = 2; h <= depth; h++) block(world, ox, oy, oz, x, -h, z, h > 5 ? Material.TUFF : Material.DEEPSLATE_BRICKS);
            boolean path = Math.abs(x) <= 3 || Math.abs(z) <= 3 || r > 23 || r < 7;
            Material floor = path ? Material.SMOOTH_SANDSTONE : Material.MOSS_BLOCK;
            if (r > 25 || r < 7 && r > 5.5 || Math.abs(x) == 3 || Math.abs(z) == 3) floor = Material.DARK_PRISMARINE;
            if (r < 2.5) floor = Material.CUT_COPPER;
            block(world, ox, oy, oz, x, -1, z, floor);
            if (r > edge - 1.1) block(world, ox, oy, oz, x, 0, z, Material.DEEPSLATE_BRICK_WALL);
        }
        for (int x : new int[]{-15, 15}) for (int z : new int[]{-12, 12}) {
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) == 4 || Math.abs(dz) == 4) block(world, ox, oy, oz, x + dx, 0, z + dz, Material.SMOOTH_SANDSTONE_SLAB);
            }
            tree(world, ox + x, oy, oz + z, z > 0 ? 7 : 6);
        }
        // The gate frames the forward view, leaving a five-block-wide walking opening.
        for (int x : new int[]{-7, 7}) {
            for (int h = 0; h <= 8; h++) for (int dx = -1; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++)
                block(world, ox, oy, oz, x + dx, h, 20 + dz, h == 0 || h == 7 ? Material.CHISELED_SANDSTONE : Material.QUARTZ_PILLAR);
            block(world, ox, oy, oz, x, 9, 20, Material.OXIDIZED_COPPER);
        }
        for (int x = -8; x <= 8; x++) for (int z = 19; z <= 22; z++) {
            block(world, ox, oy, oz, x, 8, z, Material.SMOOTH_SANDSTONE);
            block(world, ox, oy, oz, x, 9, z, Material.DARK_PRISMARINE_SLAB);
        }
        for (int x : new int[]{-5, 5}) for (int h = 6; h <= 7; h++) block(world, ox, oy, oz, x, h, 20, Material.SANDSTONE_STAIRS);
        for (int x : new int[]{-22, 22}) for (int z : new int[]{-5, 5}) {
            for (int h = 0; h < 5; h++) block(world, ox, oy, oz, x, h, z, Material.QUARTZ_PILLAR);
            block(world, ox, oy, oz, x, 5, z, Material.SEA_LANTERN);
            block(world, ox, oy, oz, x, 6, z, Material.DARK_PRISMARINE_SLAB);
        }
        for (int x : new int[]{-22, 22}) for (int z = -6; z <= 6; z++) block(world, ox, oy, oz, x, 6, z, Material.DARK_PRISMARINE_SLAB);
        for (int z : new int[]{-21, 10}) for (int x : new int[]{-6, 6}) {
            block(world, ox, oy, oz, x, 0, z, Material.CHISELED_SANDSTONE);
            block(world, ox, oy, oz, x, 1, z, Material.LANTERN);
        }
    }
    private static void tree(World world, int x, int y, int z, int height) {
        Leaves leaves = (Leaves) Material.AZALEA_LEAVES.createBlockData(); leaves.setPersistent(true);
        for (int h = 0; h < height; h++) world.getBlockAt(x, y + h, z).setType(Material.OAK_LOG, false);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -2; dy <= 1; dy++) {
            if (dx * dx + dz * dz + dy * dy * 2 > 12 || dx == 0 && dz == 0 && dy < 0) continue;
            world.getBlockAt(x + dx, y + height + dy, z + dz).setBlockData(leaves, false);
        }
        world.getBlockAt(x + 1, y, z).setType(Material.FLOWERING_AZALEA, false);
        world.getBlockAt(x - 1, y, z + 1).setType(Material.AZALEA, false);
    }
    private static void block(World world, int x, int y, int z, int dx, int dy, int dz, Material material) {
        world.getBlockAt(x + dx, y + dy, z + dz).setType(material, false);
    }
    public Location spawn() { return spawn.clone(); }
}
