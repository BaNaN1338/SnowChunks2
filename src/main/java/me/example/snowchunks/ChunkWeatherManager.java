package me.example.snowchunks;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class ChunkWeatherManager {

    private final SnowChunksPlugin plugin;
    private final NamespacedKey origBiomesKey;
    private final NamespacedKey wandKey;

    private final Set<String> selectedChunks = new HashSet<>();
    private final Map<String, WeatherMode> chunkModes = new HashMap<>();
    private WeatherMode globalRolledMode = WeatherMode.SNOW;

    public ChunkWeatherManager(SnowChunksPlugin plugin) {
        this.plugin = plugin;
        this.origBiomesKey = new NamespacedKey(plugin, "original_surface_biomes");
        this.wandKey = new NamespacedKey(plugin, "chunk_selector_wand");
    }

    public NamespacedKey getWandKey() {
        return wandKey;
    }

    public String toKey(World world, int chunkX, int chunkZ) {
        return world.getName() + ":" + chunkX + ":" + chunkZ;
    }

    public String toKey(Chunk chunk) {
        return toKey(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    public void loadChunksFromConfig() {
        selectedChunks.clear();
        chunkModes.clear();
        List<String> list = plugin.getConfig().getStringList("chunks");
        selectedChunks.addAll(list);
        rollWeatherForAllChunks();
    }

    public void saveChunksToConfig() {
        plugin.getConfig().set("chunks", new ArrayList<>(selectedChunks));
        plugin.saveConfig();
    }

    public boolean isManagedChunk(Chunk chunk) {
        return selectedChunks.contains(toKey(chunk));
    }

    public Set<String> getSelectedChunks() {
        return Collections.unmodifiableSet(selectedChunks);
    }

    public WeatherMode getChunkMode(Chunk chunk) {
        String key = toKey(chunk);
        if (plugin.getConfig().getBoolean("per-chunk-roll", false)) {
            return chunkModes.computeIfAbsent(key, k -> rollSingleProbability());
        }
        return globalRolledMode;
    }

    public boolean addChunk(Chunk chunk) {
        String key = toKey(chunk);
        if (!selectedChunks.add(key)) {
            return false;
        }
        backupOriginalBiomes(chunk);
        WeatherMode mode = getChunkMode(chunk);
        applyChunkBiomeForMode(chunk, mode);
        updatePlayersInChunk(chunk);
        saveChunksToConfig();
        return true;
    }

    public boolean removeChunk(Chunk chunk) {
        String key = toKey(chunk);
        if (!selectedChunks.remove(key)) {
            return false;
        }
        chunkModes.remove(key);
        restoreOriginalBiomes(chunk);
        for (Player player : chunk.getWorld().getPlayers()) {
            if (player.getLocation().getChunk().equals(chunk)) {
                player.resetPlayerWeather();
            }
        }
        saveChunksToConfig();
        return true;
    }

    public WeatherMode rollSingleProbability() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("probabilities");
        double snow = sec != null ? Math.max(0, sec.getDouble("snow", 65)) : 65;
        double rain = sec != null ? Math.max(0, sec.getDouble("rain", 20)) : 20;
        double thunder = sec != null ? Math.max(0, sec.getDouble("thunder", 10)) : 10;
        double clear = sec != null ? Math.max(0, sec.getDouble("clear", 5)) : 5;

        double total = snow + rain + thunder + clear;
        if (total <= 0) {
            return WeatherMode.SNOW;
        }

        double roll = ThreadLocalRandom.current().nextDouble(total);
        if (roll < snow) return WeatherMode.SNOW;
        roll -= snow;
        if (roll < rain) return WeatherMode.RAIN;
        roll -= rain;
        if (roll < thunder) return WeatherMode.THUNDER;
        return WeatherMode.CLEAR;
    }

    public WeatherMode rollWeatherForAllChunks() {
        globalRolledMode = rollSingleProbability();
        chunkModes.clear();

        for (String key : selectedChunks) {
            WeatherMode mode = plugin.getConfig().getBoolean("per-chunk-roll", false)
                    ? rollSingleProbability()
                    : globalRolledMode;
            chunkModes.put(key, mode);
            applyToLoadedChunkKey(key, mode);
        }
        return globalRolledMode;
    }

    public void forceWeatherMode(WeatherMode mode) {
        this.globalRolledMode = mode;
        for (String key : selectedChunks) {
            chunkModes.put(key, mode);
            applyToLoadedChunkKey(key, mode);
        }
    }

    private void applyToLoadedChunkKey(String key, WeatherMode mode) {
        String[] parts = key.split(":");
        if (parts.length != 3) return;

        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return;

        try {
            int cx = Integer.parseInt(parts[1]);
            int cz = Integer.parseInt(parts[2]);
            if (world.isChunkLoaded(cx, cz)) {
                Chunk chunk = world.getChunkAt(cx, cz);
                applyChunkBiomeForMode(chunk, mode);
                updatePlayersInChunk(chunk);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    public void backupOriginalBiomes(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (pdc.has(origBiomesKey, PersistentDataType.STRING)) {
            return;
        }

        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        StringBuilder sb = new StringBuilder();
        for (int lx = 0; lx < 16; lx += 4) {
            for (int lz = 0; lz < 16; lz += 4) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int surfaceY = world.getHighestBlockYAt(wx, wz);
                Biome biome = world.getBiome(wx, surfaceY, wz);
                if (!sb.isEmpty()) sb.append(",");
                sb.append(biome.getKey().asString());
            }
        }
        pdc.set(origBiomesKey, PersistentDataType.STRING, sb.toString());
    }

    public void applyChunkBiomeForMode(Chunk chunk, WeatherMode mode) {
        backupOriginalBiomes(chunk);
        World world = chunk.getWorld();

        if (mode == WeatherMode.SNOW || mode == WeatherMode.THUNDER) {
            Biome snowBiome = resolveBiome(plugin.getConfig().getString("snow-biome", "minecraft:snowy_plains"), Biome.SNOWY_PLAINS);
            setSurfaceToSkyBiome(chunk, snowBiome);
            if (mode == WeatherMode.THUNDER && world.hasStorm() && !world.isThundering()) {
                world.setThundering(true);
            }
        } else if (mode == WeatherMode.RAIN) {
            restoreOrFallbackRainBiome(chunk);
        } else if (mode == WeatherMode.CLEAR) {
            restoreOriginalBiomesWithoutRemove(chunk);
        }
    }

    private void setSurfaceToSkyBiome(Chunk chunk, Biome targetBiome) {
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int maxY = world.getMaxHeight();
        int minY = world.getMinHeight();

        for (int lx = 0; lx < 16; lx += 4) {
            for (int lz = 0; lz < 16; lz += 4) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int startY = Math.max(minY, world.getHighestBlockYAt(wx, wz) - 8);
                for (int y = startY; y < maxY; y += 4) {
                    world.setBiome(wx, y, wz, targetBiome);
                }
            }
        }
        world.refreshChunk(chunk.getX(), chunk.getZ());
    }

    private void restoreOrFallbackRainBiome(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        String saved = pdc.get(origBiomesKey, PersistentDataType.STRING);
        Biome fallbackRain = resolveBiome(plugin.getConfig().getString("fallback-rain-biome", "minecraft:plains"), Biome.PLAINS);

        if (saved == null || saved.isEmpty()) {
            setSurfaceToSkyBiome(chunk, fallbackRain);
            return;
        }

        String[] keys = saved.split(",");
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int maxY = world.getMaxHeight();
        int minY = world.getMinHeight();

        int idx = 0;
        for (int lx = 0; lx < 16; lx += 4) {
            for (int lz = 0; lz < 16; lz += 4) {
                Biome orig = idx < keys.length ? resolveBiome(keys[idx], fallbackRain) : fallbackRain;
                idx++;
                if (orig.getKey().getKey().contains("snow") || orig.getKey().getKey().contains("frozen") || orig.getKey().getKey().contains("ice")) {
                    orig = fallbackRain;
                }
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int startY = Math.max(minY, world.getHighestBlockYAt(wx, wz) - 8);
                for (int y = startY; y < maxY; y += 4) {
                    world.setBiome(wx, y, wz, orig);
                }
            }
        }
        world.refreshChunk(chunk.getX(), chunk.getZ());
    }

    private void restoreOriginalBiomesWithoutRemove(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        String saved = pdc.get(origBiomesKey, PersistentDataType.STRING);
        if (saved == null || saved.isEmpty()) return;

        String[] keys = saved.split(",");
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int maxY = world.getMaxHeight();
        int minY = world.getMinHeight();

        int idx = 0;
        for (int lx = 0; lx < 16; lx += 4) {
            for (int lz = 0; lz < 16; lz += 4) {
                if (idx >= keys.length) break;
                Biome orig = resolveBiome(keys[idx++], Biome.PLAINS);
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int startY = Math.max(minY, world.getHighestBlockYAt(wx, wz) - 8);
                for (int y = startY; y < maxY; y += 4) {
                    world.setBiome(wx, y, wz, orig);
                }
            }
        }
        world.refreshChunk(chunk.getX(), chunk.getZ());
    }

    public void restoreOriginalBiomes(Chunk chunk) {
        restoreOriginalBiomesWithoutRemove(chunk);
        chunk.getPersistentDataContainer().remove(origBiomesKey);
    }

    public void updatePlayersInChunk(Chunk chunk) {
        for (Player player : chunk.getWorld().getPlayers()) {
            if (player.getLocation().getChunk().equals(chunk)) {
                updatePlayerWeather(player, chunk);
            }
        }
    }

    public void updatePlayerWeather(Player player, Chunk chunk) {
        if (!isManagedChunk(chunk)) {
            if (player.getPlayerWeather() != null) {
                player.resetPlayerWeather();
            }
            return;
        }

        WeatherMode mode = getChunkMode(chunk);
        if (mode == WeatherMode.CLEAR && chunk.getWorld().hasStorm()) {
            player.setPlayerWeather(WeatherType.CLEAR);
        } else {
            if (player.getPlayerWeather() != null) {
                player.resetPlayerWeather();
            }
        }
    }

    public void resetAllPlayerWeather() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getPlayerWeather() != null) {
                player.resetPlayerWeather();
            }
        }
    }

    private Biome resolveBiome(String keyString, Biome defaultBiome) {
        if (keyString == null) return defaultBiome;
        NamespacedKey key = NamespacedKey.fromString(keyString.toLowerCase());
        if (key == null) return defaultBiome;
        Biome biome = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(key);
        return biome != null ? biome : defaultBiome;
    }
}
