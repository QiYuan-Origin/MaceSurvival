package club.mcqi.macesurvival.config;

import club.mcqi.macesurvival.service.Reloadable;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.stream.Stream;

/** Owns the plugin's editable YAML files and restores missing defaults on reload. */
public final class ConfigFiles implements Reloadable {
    private static final Set<String> ROOT_DEFAULTS = Set.of("config.yml", "messages.yml", "loot.yml");
    private static final Set<String> EXCLUDED_RESOURCES = Set.of("plugin.yml", "paper-plugin.yml");
    private static final List<String> VERSION_KEYS = List.of("config-version", "config_version");

    private final JavaPlugin plugin;
    private final Set<String> managedPaths = new LinkedHashSet<>();
    private volatile Map<String, FileConfiguration> configurations = Map.of();

    public ConfigFiles(JavaPlugin plugin) {
        this(plugin, List.of());
    }

    public ConfigFiles(JavaPlugin plugin, Collection<String> additionalResources) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        managedPaths.addAll(ROOT_DEFAULTS);
        additionalResources.stream().map(ConfigFiles::normalizePath).forEach(managedPaths::add);
    }

    /** Copies absent defaults, merges missing keys and replaces the in-memory snapshot. */
    @Override
    public synchronized void reload() {
        managedPaths.addAll(discoverYamlResources());
        Map<String, FileConfiguration> previous = configurations;
        Map<String, FileConfiguration> loaded = new LinkedHashMap<>();
        managedPaths.stream().sorted().forEach(path -> {
            Optional<FileConfiguration> refreshed = loadManaged(path);
            if (refreshed.isPresent()) {
                loaded.put(path, refreshed.orElseThrow());
            } else if (previous.containsKey(path)) {
                loaded.put(path, previous.get(path));
            }
        });
        configurations = Map.copyOf(loaded);
    }

    /** Adds another classpath YAML resource to future reloads and loads it immediately. */
    public synchronized FileConfiguration register(String resourcePath) {
        String path = normalizePath(resourcePath);
        managedPaths.add(path);
        FileConfiguration loaded = loadManaged(path)
            .orElseThrow(() -> new IllegalArgumentException("Default resource does not exist: " + path));
        Map<String, FileConfiguration> next = new LinkedHashMap<>(configurations);
        next.put(path, loaded);
        configurations = Map.copyOf(next);
        return loaded;
    }

    public FileConfiguration configuration(String resourcePath) {
        String path = normalizePath(resourcePath);
        FileConfiguration configuration = configurations.get(path);
        if (configuration == null) {
            throw new IllegalStateException("Configuration is not loaded: " + path);
        }
        return configuration;
    }

    public Optional<FileConfiguration> find(String resourcePath) {
        return Optional.ofNullable(configurations.get(normalizePath(resourcePath)));
    }

    public FileConfiguration messages() {
        return configuration("messages.yml");
    }

    public Map<String, FileConfiguration> snapshot() {
        return configurations;
    }

    /** Saves a managed configuration after callers have deliberately changed it. */
    public synchronized void save(String resourcePath) throws IOException {
        String path = normalizePath(resourcePath);
        FileConfiguration configuration = configuration(path);
        configuration.save(resolveDataPath(path).toFile());
    }

    private Optional<FileConfiguration> loadManaged(String resourcePath) {
        String path = normalizePath(resourcePath);
        try (Reader defaultsReader = defaultReader(path)) {
            if (defaultsReader == null) {
                return Optional.empty();
            }

            Path destination = resolveDataPath(path);
            if (Files.notExists(destination)) {
                Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                plugin.saveResource(path, false);
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(defaultsReader);
            YamlConfiguration current = new YamlConfiguration();
            current.load(destination.toFile());
            boolean versionChanged = hasConfigVersionChanged(current, defaults);
            boolean changed = mergeMissing(current, defaults);
            if (versionChanged) {
                changed |= refreshVersionedDefaults(path, current, defaults);
            }
            changed |= updateConfigVersion(current, defaults);
            if (changed) {
                current.save(destination.toFile());
            }
            current.setDefaults(defaults);
            return Optional.of(current);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load configuration " + path, exception);
            return Optional.empty();
        }
    }

    private Reader defaultReader(String path) {
        var stream = plugin.getResource(path);
        return stream == null ? null : new InputStreamReader(stream, StandardCharsets.UTF_8);
    }

    private static boolean mergeMissing(YamlConfiguration current, YamlConfiguration defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !current.contains(key)) {
                current.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    private static boolean hasConfigVersionChanged(YamlConfiguration current, YamlConfiguration defaults) {
        for (String key : VERSION_KEYS) {
            Object defaultVersion = defaults.get(key);
            if (defaultVersion != null) {
                return !Objects.equals(defaultVersion, current.get(key));
            }
        }
        return false;
    }

    private static boolean refreshVersionedDefaults(
        String path,
        YamlConfiguration current,
        YamlConfiguration defaults
    ) {
        if (path.equals("messages.yml") || path.startsWith("menus/")) {
            return overwriteDefaultValues(current, defaults, "");
        }
        if (path.equals("config.yml")) {
            return overwriteDefaultValues(current, defaults, "server.")
                | overwriteDefaultValues(current, defaults, "text.")
                | overwriteDefaultValues(current, defaults, "lobby.")
                | overwriteDefaultValues(current, defaults, "match.preload.")
                | overwriteDefaultValues(current, defaults, "deployment.")
                | overwriteDefaultValues(current, defaults, "border.")
                | overwriteDefaultValues(current, defaults, "loot.");
        }
        if (path.equals("loot.yml")) {
            return overwriteDefaultValues(current, defaults, "");
        }
        return false;
    }

    private static boolean overwriteDefaultValues(
        YamlConfiguration current,
        YamlConfiguration defaults,
        String keyPrefix
    ) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || !key.startsWith(keyPrefix)) {
                continue;
            }
            Object defaultValue = defaults.get(key);
            if (!Objects.equals(defaultValue, current.get(key))) {
                current.set(key, defaultValue);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean updateConfigVersion(YamlConfiguration current, YamlConfiguration defaults) {
        for (String key : VERSION_KEYS) {
            Object defaultVersion = defaults.get(key);
            if (defaultVersion != null && !Objects.equals(defaultVersion, current.get(key))) {
                current.set(key, defaultVersion);
                return true;
            }
        }
        return false;
    }

    private Set<String> discoverYamlResources() {
        Set<String> discovered = new LinkedHashSet<>();
        try {
            Path codeSource = Paths.get(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(codeSource)) {
                discoverFromDirectory(codeSource, discovered);
            } else {
                discoverFromJar(codeSource, discovered);
            }
        } catch (IOException | URISyntaxException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not enumerate default YAML resources", exception);
        }
        return discovered;
    }

    private static void discoverFromDirectory(Path root, Set<String> output) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                .map(root::relativize)
                .map(Path::toString)
                .map(value -> value.replace(File.separatorChar, '/'))
                .filter(ConfigFiles::isManagedYaml)
                .sorted(Comparator.naturalOrder())
                .forEach(output::add);
        }
    }

    private static void discoverFromJar(Path jarPath, Set<String> output) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream()
                .filter(entry -> !entry.isDirectory())
                .map(entry -> entry.getName())
                .filter(ConfigFiles::isManagedYaml)
                .sorted()
                .forEach(output::add);
        }
    }

    private static boolean isManagedYaml(String path) {
        String normalized = normalizePath(path);
        boolean yaml = normalized.toLowerCase(Locale.ROOT).endsWith(".yml");
        return yaml
            && !EXCLUDED_RESOURCES.contains(normalized)
            && (ROOT_DEFAULTS.contains(normalized) || normalized.startsWith("menus/"));
    }

    private Path resolveDataPath(String resourcePath) {
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path destination = root.resolve(resourcePath).normalize();
        if (!destination.startsWith(root)) {
            throw new IllegalArgumentException("Configuration path escapes the plugin directory: " + resourcePath);
        }
        return destination;
    }

    private static String normalizePath(String resourcePath) {
        String normalized = Objects.requireNonNull(resourcePath, "resourcePath").replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("../") || normalized.equals("..")) {
            throw new IllegalArgumentException("Invalid resource path: " + resourcePath);
        }
        return normalized;
    }
}
