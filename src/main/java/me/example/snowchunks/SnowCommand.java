package me.example.snowchunks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SnowCommand implements CommandExecutor, TabCompleter {

    private final SnowChunksPlugin plugin;
    private final ChunkWeatherManager manager;

    public SnowCommand(SnowChunksPlugin plugin, ChunkWeatherManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "add" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Команда только для игроков.", NamedTextColor.RED));
                    return true;
                }
                Chunk chunk = player.getLocation().getChunk();
                if (manager.addChunk(chunk)) {
                    player.sendMessage(Component.text("Чанк [" + chunk.getX() + ", " + chunk.getZ() + "] добавлен!", NamedTextColor.AQUA));
                } else {
                    player.sendMessage(Component.text("Этот чанк уже добавлен.", NamedTextColor.YELLOW));
                }
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Команда только для игроков.", NamedTextColor.RED));
                    return true;
                }
                Chunk chunk = player.getLocation().getChunk();
                if (manager.removeChunk(chunk)) {
                    player.sendMessage(Component.text("Чанк [" + chunk.getX() + ", " + chunk.getZ() + "] удалён, оригинальный биом возвращён.", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Этот чанк не найден в списке.", NamedTextColor.RED));
                }
            }
            case "addradius" -> {
                if (!(sender instanceof Player player)) return true;
                if (args.length < 2) {
                    player.sendMessage(Component.text("Использование: /sc addradius <радиус_в_чанках>", NamedTextColor.RED));
                    return true;
                }
                int radius = parseRadius(args[1], player);
                if (radius < 0) return true;

                World world = player.getWorld();
                int centerX = player.getLocation().getChunk().getX();
                int centerZ = player.getLocation().getChunk().getZ();
                int added = 0;

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (manager.addChunk(world.getChunkAt(centerX + dx, centerZ + dz))) {
                            added++;
                        }
                    }
                }
                player.sendMessage(Component.text("Добавлено чанков: " + added, NamedTextColor.AQUA));
            }
            case "removeradius" -> {
                if (!(sender instanceof Player player)) return true;
                if (args.length < 2) {
                    player.sendMessage(Component.text("Использование: /sc removeradius <радиус_в_чанках>", NamedTextColor.RED));
                    return true;
                }
                int radius = parseRadius(args[1], player);
                if (radius < 0) return true;

                World world = player.getWorld();
                int centerX = player.getLocation().getChunk().getX();
                int centerZ = player.getLocation().getChunk().getZ();
                int removed = 0;

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (manager.removeChunk(world.getChunkAt(centerX + dx, centerZ + dz))) {
                            removed++;
                        }
                    }
                }
                player.sendMessage(Component.text("Удалено и восстановлено чанков: " + removed, NamedTextColor.GREEN));
            }
            case "wand" -> {
                if (!(sender instanceof Player player)) return true;
                ItemStack wand = new ItemStack(Material.BLAZE_ROD);
                ItemMeta meta = wand.getItemMeta();
                meta.displayName(Component.text("Жезл снежных чанков", NamedTextColor.AQUA));
                meta.lore(List.of(
                        Component.text("ПКМ — добавить чанк", NamedTextColor.GRAY),
                        Component.text("ЛКМ — удалить чанк", NamedTextColor.GRAY)
                ));
                meta.getPersistentDataContainer().set(manager.getWandKey(), PersistentDataType.BYTE, (byte) 1);
                wand.setItemMeta(meta);
                player.getInventory().addItem(wand);
                player.sendMessage(Component.text("Выдан жезл выделения чанков!", NamedTextColor.AQUA));
            }
            case "roll" -> {
                WeatherMode mode = manager.rollWeatherForAllChunks();
                sender.sendMessage(Component.text("Вероятности переброшены! Текущий режим: " + mode.name(), NamedTextColor.AQUA));
            }
            case "force" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Использование: /sc force <snow|rain|thunder|clear>", NamedTextColor.RED));
                    return true;
                }
                try {
                    WeatherMode mode = WeatherMode.valueOf(args[1].toUpperCase());
                    manager.forceWeatherMode(mode);
                    sender.sendMessage(Component.text("Принудительно установлен режим осадков: " + mode.name(), NamedTextColor.GREEN));
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(Component.text("Доступные режимы: snow, rain, thunder, clear", NamedTextColor.RED));
                }
            }
            case "setchance" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Использование: /sc setchance <snow|rain|thunder|clear> <вес>", NamedTextColor.RED));
                    return true;
                }
                String type = args[1].toLowerCase();
                if (!List.of("snow", "rain", "thunder", "clear").contains(type)) {
                    sender.sendMessage(Component.text("Неверный тип! Доступны: snow, rain, thunder, clear", NamedTextColor.RED));
                    return true;
                }
                try {
                    double val = Double.parseDouble(args[2]);
                    plugin.getConfig().set("probabilities." + type, Math.max(0, val));
                    plugin.saveConfig();
                    sender.sendMessage(Component.text("Вес вероятности для " + type + " установлен на " + val, NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Введите корректное число.", NamedTextColor.RED));
                }
            }
            case "list" -> {
                sender.sendMessage(Component.text("Выбранные чанки (" + manager.getSelectedChunks().size() + "): "
                        + String.join(", ", manager.getSelectedChunks()), NamedTextColor.AQUA));
            }
            case "reload" -> {
                plugin.reloadConfig();
                manager.loadChunksFromConfig();
                sender.sendMessage(Component.text("Конфигурация SnowChunks перезагружена!", NamedTextColor.GREEN));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private int parseRadius(String arg, Player player) {
        try {
            int r = Integer.parseInt(arg);
            if (r < 0 || r > 10) {
                player.sendMessage(Component.text("Радиус должен быть от 0 до 10.", NamedTextColor.RED));
                return -1;
            }
            return r;
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Введите корректное целое число.", NamedTextColor.RED));
            return -1;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("--- Команды SnowChunks ---", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/sc add / remove — добавить/удалить текущий чанк", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/sc addradius <R> / removeradius <R> — добавить/удалить чанки в радиусе", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/sc wand — получить жезл быстрого выбора чанков", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/sc setchance <snow|rain|thunder|clear> <вес> — настроить шанс осадков", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/sc roll — перебросить случайный тип осадков по шансам", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/sc force <snow|rain|thunder|clear> — принудительно включить осадки", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/sc list / reload — список чанков / перезагрузка конфига", NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.asList("add", "remove", "addradius", "removeradius", "wand", "roll", "force", "setchance", "list", "reload");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("force") || args[0].equalsIgnoreCase("setchance"))) {
            return Arrays.asList("snow", "rain", "thunder", "clear");
        }
        return Collections.emptyList();
    }
}
