package me.example.snowchunks;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SnowChunksPlugin extends JavaPlugin {

    private ChunkWeatherManager weatherManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.weatherManager = new ChunkWeatherManager(this);
        this.weatherManager.loadChunksFromConfig();

        getServer().getPluginManager().registerEvents(new WeatherListener(this, weatherManager), this);

        SnowCommand commandExecutor = new SnowCommand(this, weatherManager);
        PluginCommand cmd = getCommand("snowchunks");
        if (cmd != null) {
            cmd.setExecutor(commandExecutor);
            cmd.setTabCompleter(commandExecutor);
        }

        getLogger().info("SnowChunks успешно запущен для Paper 26.3!");
    }

    @Override
    public void onDisable() {
        if (weatherManager != null) {
            weatherManager.resetAllPlayerWeather();
        }
    }

    public ChunkWeatherManager getWeatherManager() {
        return weatherManager;
    }
}
