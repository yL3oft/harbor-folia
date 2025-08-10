package xyz.nkomarn.harbor.task;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.metadata.MetadataValue;
import org.jetbrains.annotations.NotNull;
import xyz.nkomarn.harbor.Harbor;
import xyz.nkomarn.harbor.api.ExclusionProvider;
import xyz.nkomarn.harbor.folia.FoliaRunnable;
import xyz.nkomarn.harbor.folia.SchedulerUtils;
import xyz.nkomarn.harbor.provider.GameModeExclusionProvider;
import xyz.nkomarn.harbor.util.Config;
import xyz.nkomarn.harbor.util.Messages;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.toList;

public class Checker extends FoliaRunnable {
    private final Set<ExclusionProvider> providers;
    private final Harbor harbor;
    private final Set<UUID> skippingWorlds;

    public Checker(@NotNull Harbor harbor) {
        this.harbor = harbor;
        this.skippingWorlds = new HashSet<>();
        this.providers = new HashSet<>();

        // GameModeExclusionProvider checks each case on its own
        providers.add(new GameModeExclusionProvider(harbor));

        // The others are simple enough that we can use lambdas
        providers.add(player -> harbor.getConfig().getBoolean("exclusions.ignored-permission", true) && player.hasPermission("harbor.ignored"));
        providers.add(player -> harbor.getConfig().getBoolean("exclusions.exclude-vanished", false) && isVanished(player));
        providers.add(player -> harbor.getConfig().getBoolean("exclusions.exclude-afk", false) && harbor.getPlayerManager().isAfk(player));

        int interval = harbor.getConfiguration().getInteger("interval");
        // Default to 1 if its invalid
        if (interval <= 0)
            interval = 1;
        SchedulerUtils.runTaskTimerAsynchronously(this, 1L, interval * 20L);
    }

    @Override
    public void run() {
        Bukkit.getWorlds().stream()
                .filter(this::validateWorld)
                .forEach(this::checkWorld);
    }

    /**
     * Checks if a given world is applicable for night skipping.
     *
     * @param world The world to check.
     *
     * @return Whether Harbor should run the night skipping check below.
     */
    private boolean validateWorld(@NotNull World world) {
        return !skippingWorlds.contains(world.getUID())
                && !isBlacklisted(world)
                && isNight(world);
    }

    /**
     * Checks if enough people are sleeping, and in the case there are, starts the night skip task.
     *
     * @param world The world to check.
     */
    private void checkWorld(@NotNull World world) {
        Config config = harbor.getConfiguration();
        Messages messages = harbor.getMessages();

        int sleeping = getSleepingPlayers(world).size();
        int needed = getNeeded(world);

        if (sleeping < 1) {
            messages.clearBar(world);
            return;
        }

        if (needed > 0) {
            double sleepingPercentage = Math.min(1, (double) sleeping / getSkipAmount(world));

            messages.sendActionBarMessage(world, config.getString("messages.actionbar.players-sleeping"));
            messages.sendBossBarMessage(world, config.getString("messages.bossbar.players-sleeping.message"),
                    config.getString("messages.bossbar.players-sleeping.color"), sleepingPercentage);
        } else if (needed == 0) {
            messages.sendActionBarMessage(world, config.getString("messages.actionbar.night-skipping"));
            messages.sendBossBarMessage(world, config.getString("messages.bossbar.night-skipping.message"),
                    config.getString("messages.bossbar.night-skipping.color"), 1);

            if (!config.getBoolean("night-skip.enabled")) {
                return;
            }

            if (config.getBoolean("night-skip.instant-skip")) {
                SchedulerUtils.runTask(null, () -> {
                    world.setTime(config.getInteger("night-skip.daytime-ticks"));
                    clearWeather(world);
                    resetStatus(world);
                });
                return;
            }

            skippingWorlds.add(world.getUID());
            new AccelerateNightTask(harbor, this, world);
        }
    }

    /**
     * Checks if the time in a given world is considered to be night.
     *
     * @param world The world to check.
     *
     * @return Whether it is currently night in the provided world.
     */
    private boolean isNight(@NotNull World world) {
        return world.getTime() > 12950 || world.getTime() < 23950;
    }

    /**
     * Checks if a current world has been blacklisted (or whitelisted) in the configuration.
     *
     * @param world The world to check.
     *
     * @return Whether a world is excluded from Harbor checks.
     */
    public boolean isBlacklisted(@NotNull World world) {
        boolean blacklisted = harbor.getConfiguration().getStringList("blacklisted-worlds").contains(world.getName());

        if (harbor.getConfiguration().getBoolean("whitelist-mode")) {
            return !blacklisted;
        }

        return blacklisted;
    }

    /**
     * Checks if a given player is in a vanished state.
     *
     * @param player The player to check.
     *
     * @return Whether the provided player is vanished.
     */
    public static boolean isVanished(@NotNull Player player) {
        return player.getMetadata("vanished").stream().anyMatch(MetadataValue::asBoolean);
    }

    /**
     * Returns the amount of players that should be counted for Harbor's checks, ignoring excluded players.
     *
     * @param world The world for which to check player count.
     *
     * @return The amount of players in a given world, minus excluded players.
     */
    public int getPlayers(@NotNull World world) {
        return Math.max(0, world.getPlayers().size() - getExcluded(world).size());
    }

    /**
     * Returns a list of all sleeping players in a given world.
     *
     * @param world The world in which to check for sleeping players.
     *
     * @return A list of all currently sleeping players in the provided world.
     */
    @NotNull
    public List<Player> getSleepingPlayers(@NotNull World world) {
        return world.getPlayers().stream()
                .filter(player -> player.getPose() == Pose.SLEEPING)
                .collect(toList());
    }

    /**
     * Returns the amount of players that must be sleeping to skip the night in the given world.
     *
     * @param world The world for which to check skip amount.
     *
     * @return The amount of players that need to sleep to skip the night.
     */
    public int getSkipAmount(@NotNull World world) {
        return (int) Math.ceil(getPlayers(world) * (harbor.getConfiguration().getDouble("night-skip.percentage") / 100));
    }

    /**
     * Returns the amount of players that are still needed to skip the night in a given world.
     *
     * @param world The world for which to check the amount of needed players.
     *
     * @return The amount of players that still need to get into bed to start the night skipping task.
     */
    public int getNeeded(@NotNull World world) {
        double percentage = harbor.getConfiguration().getDouble("night-skip.percentage");
        return Math.max(0, (int) Math.ceil((getPlayers(world)) * (percentage / 100) - getSleepingPlayers(world).size()));
    }

    /**
     * Returns a list of players that are considered to be excluded from Harbor's player count checks.
     *
     * @param world The world for which to check for excluded players.
     *
     * @return A list of excluded players in the given world.
     */
    @NotNull
    private List<Player> getExcluded(@NotNull World world) {
        return world.getPlayers().stream()
                .filter(this::isExcluded)
                .collect(toList());
    }

    /**
     * Checks if a given player is considered excluded from Harbor's checks.
     *
     * @param player The player to check.
     *
     * @return Whether the given player is excluded.
     */
    private boolean isExcluded(@NotNull Player player) {
        return providers.stream().anyMatch(provider -> provider.isExcluded(player));
    }

    /**
     * Checks whether the night is currently being skipped in the given world.
     *
     * @param world The world to check.
     *
     * @return Whether the night is currently skipping in the provided world.
     */
    public boolean isSkipping(@NotNull World world) {
        return skippingWorlds.contains(world.getUID());
    }

    /**
     * Forces a world to begin skipping the night, skipping over the checks.
     *
     * @param world The world in which to force night skipping.
     */
    public void forceSkip(@NotNull World world) {
        skippingWorlds.add(world.getUID());
        new AccelerateNightTask(harbor, this, world);
    }

    /**
     * Resets the provided world to a non-skipping status.
     *
     * @param world The world for which to reset status.
     */
    public void resetStatus(@NotNull World world) {
        wakeUpPlayers(world);
        SchedulerUtils.runTaskLater(null, () -> {
            skippingWorlds.remove(world.getUID());
            harbor.getPlayerManager().clearCooldowns();
            harbor.getMessages().sendRandomChatMessage(world, "messages.chat.night-skipped");
        }, 20L);
    }

    /**
     * Kicks all sleeping players out of bed in the provided world.
     *
     * @param world The world for which to kick players out of bed.
     */
    public void wakeUpPlayers(@NotNull World world) {
        ensureMain(() -> world.getPlayers().stream()
                .filter(LivingEntity::isSleeping)
                .forEach(player -> player.wakeup(true)));
    }

    /**
     * Resets the weather states in the provided world.
     *
     * @param world The world for which to clear weather.
     */
    public void clearWeather(@NotNull World world) {
        ensureMain(() -> {
            Config config = harbor.getConfiguration();

            if (world.hasStorm() && config.getBoolean("night-skip.clear-rain")) {
                world.setStorm(false);
            }

            if (world.isThundering() && config.getBoolean("night-skip.clear-thunder")) {
                world.setThundering(false);
            }
        });
    }

    /**
     * Ensures the provided task is ran on the server thread.
     *
     * @param runnable The task to run on the server thread.
     */
    public void ensureMain(@NotNull Runnable runnable) {
        if (!Bukkit.isPrimaryThread()) {
            SchedulerUtils.runTask(null, runnable);
        } else {
            runnable.run();
        }
    }

    /**
     * Adds an {@link ExclusionProvider}, which will be checked as a condition. All Exclusions will be ORed together
     * on which to exclude a given player
     */
    public void addExclusionProvider(ExclusionProvider provider) {
        providers.add(provider);
    }

    /**
     * Removes an {@link ExclusionProvider}
     */
    public void removeExclusionProvider(ExclusionProvider provider) {
        providers.remove(provider);
    }
}
