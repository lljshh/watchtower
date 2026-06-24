package org.minelogy.watchtower;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Watchtower implements ModInitializer {
    static final Logger LOGGER = LogManager.getLogger();
    public static Config config;
    static volatile MinecraftServer server;
    static volatile int serverMaxPlayers;
    static volatile List<String> serverPlayers = new CopyOnWriteArrayList<>();
    public static volatile Network network;
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Watchtower.server = server;
            serverMaxPlayers = server.getMaxPlayers();
        });
        String configPath = Paths.get(FabricLoader.getInstance().getConfigDir().toString(), "lemonfate.json").toString();
        File f = new File(configPath);
        if (!f.exists()) {
            try {
                if(f.createNewFile()) {
                    LOGGER.error("Config file created but left empty: {}. Fill it and restart.", configPath);
                    throw new IllegalStateException("Config file " + configPath + " is empty. Edit it and restart the server.");
                }
                else
                    throw new RuntimeException("Could not create file " + configPath);
            } catch (IOException e) {
                LOGGER.error("Error while creating config file!", e);
                throw new RuntimeException(e);
            }
        }
        else {
            Gson gson = new Gson();
            try (FileInputStream fis = new FileInputStream(f)) {
                String json = new String(fis.readAllBytes(), StandardCharsets.UTF_8);
                config = gson.fromJson(json, Config.class);
                if (config == null || config.address == null || config.address.isEmpty() || config.port <= 0 || config.password == null || config.password.isEmpty()) {
                    throw new IllegalStateException("Invalid config: address, port, and password must be set in config file");
                }
                if (config.ssl == null) {
                    config.ssl = true;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ServerPlayerEvents.JOIN.register((player) -> {
            serverPlayers.add(player.getDisplayName().getString());
        });
        ServerPlayerEvents.LEAVE.register((player) -> {
            serverPlayers.remove(player.getDisplayName().getString());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (network != null) {
                network.stop();
                network = null;
            }
        });
        network = new Network(config.address, config.port, config.password, config.ssl);
        network.start();
    }
}
