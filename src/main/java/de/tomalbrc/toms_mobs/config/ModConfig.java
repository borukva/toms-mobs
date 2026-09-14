package de.tomalbrc.toms_mobs.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import de.tomalbrc.bil.json.SimpleCodecDeserializer;
import de.tomalbrc.toms_mobs.TomsMobs;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public class ModConfig {
    public static final int DEFAULT_BUTTERFLY_DENSITY_LIMIT = 8;
    public static final int DEFAULT_BUTTERFLY_DENSITY_RADIUS = 64;

    private static final Path CONFIG_FILE_PATH = FabricLoader.getInstance().getConfigDir().resolve(TomsMobs.MODID + ".json");
    private static ModConfig instance;

    private static final Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(Identifier.class, new SimpleCodecDeserializer<>(Identifier.CODEC))
            .setPrettyPrinting()
            .create();

    // entries

    @SerializedName("spawns")
    public JsonElement spawnsJson;

    public List<Identifier> disabledMobs = new ObjectArrayList<>();
    public boolean noAdditionalRaidMobs = true;
    public int butterflyDensityLimit = DEFAULT_BUTTERFLY_DENSITY_LIMIT;
    public int butterflyDensityRadius = DEFAULT_BUTTERFLY_DENSITY_RADIUS;

    public static ModConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!CONFIG_FILE_PATH.toFile().exists()) {
            instance = new ModConfig();
            var resource = TomsMobs.class.getResourceAsStream("/default-spawns.json");
            if (resource != null)
                ModConfig.instance.spawnsJson = JsonParser.parseReader(new InputStreamReader(resource));

            save();

            return;
        }

        try (FileReader reader = new FileReader(ModConfig.CONFIG_FILE_PATH.toFile())) {
            JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
            boolean changed = false;

            if (!config.has("butterflyDensityLimit") || config.get("butterflyDensityLimit").isJsonNull()) {
                config.addProperty("butterflyDensityLimit", DEFAULT_BUTTERFLY_DENSITY_LIMIT);
                changed = true;
            }
            if (!config.has("butterflyDensityRadius") || config.get("butterflyDensityRadius").isJsonNull()) {
                config.addProperty("butterflyDensityRadius", DEFAULT_BUTTERFLY_DENSITY_RADIUS);
                changed = true;
            }
            if (!config.has("spawns") || config.get("spawns").isJsonNull()) {
                var resource = TomsMobs.class.getResourceAsStream("/default-spawns.json");
                if (resource != null) {
                    config.add("spawns", JsonParser.parseReader(new InputStreamReader(resource)));
                    changed = true;
                }
            }

            ModConfig.instance = gson.fromJson(config, ModConfig.class);
            if (changed) save(config);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void save() {
        save(gson.toJsonTree(instance));
    }

    private static void save(JsonElement config) {
        try (FileOutputStream stream = new FileOutputStream(CONFIG_FILE_PATH.toFile())) {
            stream.write(gson.toJson(config).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
