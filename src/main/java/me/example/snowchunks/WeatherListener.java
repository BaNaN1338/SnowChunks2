package me.example.snowchunks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class WeatherListener implements Listener {

    private final SnowChunksPlugin plugin;
    private final ChunkWeatherManager manager;

    public WeatherListener(SnowChunksPlugin plugin, ChunkWeatherManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (event.toWeatherState()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                WeatherMode rolled = manager.rollWeatherForAllChunks();
                plugin.getLogger().info("Погода изменилась! Выпавший режим для выбранных чанков: " + rolled.name());
            });
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (Player player : event.getWorld().getPlayers()) {
                    manager.updatePlayerWeather(player, player.getLocation().getChunk());
                }
            });
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (manager.isManagedChunk(chunk)) {
            manager.applyChunkBiomeForMode(chunk, manager.getChunkMode(chunk));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Chunk chunk = event.getBlock().getChunk();
        if (!manager.isManagedChunk(chunk)) {
            return;
        }

        Material newType = event.getNewState().getType();
        if (newType == Material.SNOW && !plugin.getConfig().getBoolean("accumulation.allow-snow-layers", true)) {
            event.setCancelled(true);
        } else if (newType == Material.ICE && !plugin.getConfig().getBoolean("accumulation.allow-ice-form", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() >> 4 == event.getTo().getBlockX() >> 4
                && event.getFrom().getBlockZ() >> 4 == event.getTo().getBlockZ() >> 4) {
            return;
        }
        manager.updatePlayerWeather(event.getPlayer(), event.getTo().getChunk());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        manager.updatePlayerWeather(event.getPlayer(), event.getTo().getChunk());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.updatePlayerWeather(event.getPlayer(), event.getPlayer().getLocation().getChunk());
    }

    @EventHandler
    public void onWandInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        if (!item.getItemMeta().getPersistentDataContainer().has(manager.getWandKey(), PersistentDataType.BYTE)) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission("snowchunks.admin")) return;

        event.setCancelled(true);
        Chunk targetChunk = event.getClickedBlock() != null
                ? event.getClickedBlock().getChunk()
                : player.getLocation().getChunk();

        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
            if (manager.addChunk(targetChunk)) {
                player.sendMessage(Component.text("Чанк [" + targetChunk.getX() + ", " + targetChunk.getZ() + "] добавлен в снежную зону!", NamedTextColor.AQUA));
            } else {
                player.sendMessage(Component.text("Этот чанк уже в списке!", NamedTextColor.YELLOW));
            }
        } else if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            if (manager.removeChunk(targetChunk)) {
                player.sendMessage(Component.text("Чанк [" + targetChunk.getX() + ", " + targetChunk.getZ() + "] удалён, биом восстановлен.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("Этот чанк не числится в списке снежных.", NamedTextColor.RED));
            }
        }
    }
}
