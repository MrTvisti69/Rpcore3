package ru.rpcore.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Единая обёртка над планировщиками Folia.
 *
 * <p>Правило Folia: любое действие с миром/сущностью выполняется в потоке региона,
 * которому они принадлежат. Выбирайте планировщик по тому, с чем работаете:</p>
 * <ul>
 *   <li>{@code global*}  - глобальные задачи (время, погода, команды консоли) - НЕ для блоков и сущностей;</li>
 *   <li>{@code region*}  - блоки/чанки/локации;</li>
 *   <li>{@code entity*}  - игрок или любая сущность (следует за ней между регионами);</li>
 *   <li>{@code async*}   - БД, сеть, файлы. Мир и сущностей здесь трогать нельзя.</li>
 * </ul>
 * Задержки в тиках автоматически поднимаются до минимума 1 (Folia бросает исключение при 0).
 */
public final class RPScheduler {

    private final Plugin plugin;

    public RPScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    private static long ticks(long t) {
        return Math.max(1L, t);
    }

    // ------------------------------------------------------------ GLOBAL

    public ScheduledTask global(Runnable task) {
        return plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> task.run());
    }

    public ScheduledTask globalLater(Runnable task, long delayTicks) {
        return plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), ticks(delayTicks));
    }

    public ScheduledTask globalTimer(Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
        return plugin.getServer().getGlobalRegionScheduler()
                .runAtFixedRate(plugin, task, ticks(delayTicks), ticks(periodTicks));
    }

    // ------------------------------------------------------------ REGION

    public ScheduledTask region(Location loc, Runnable task) {
        return plugin.getServer().getRegionScheduler().run(plugin, loc, t -> task.run());
    }

    public ScheduledTask regionLater(Location loc, Runnable task, long delayTicks) {
        return plugin.getServer().getRegionScheduler().runDelayed(plugin, loc, t -> task.run(), ticks(delayTicks));
    }

    public ScheduledTask regionTimer(Location loc, Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
        return plugin.getServer().getRegionScheduler()
                .runAtFixedRate(plugin, loc, task, ticks(delayTicks), ticks(periodTicks));
    }

    // ------------------------------------------------------------ ENTITY

    /**
     * @param retired выполнится, если сущность удалена до запуска задачи (может быть null).
     * @return задача, либо null если сущность уже удалена.
     */
    public ScheduledTask entity(Entity entity, Runnable task, Runnable retired) {
        return entity.getScheduler().run(plugin, t -> task.run(), retired);
    }

    public ScheduledTask entity(Entity entity, Runnable task) {
        return entity(entity, task, null);
    }

    public ScheduledTask entityLater(Entity entity, Runnable task, Runnable retired, long delayTicks) {
        return entity.getScheduler().runDelayed(plugin, t -> task.run(), retired, ticks(delayTicks));
    }

    public ScheduledTask entityTimer(Entity entity, Consumer<ScheduledTask> task, Runnable retired,
                                     long delayTicks, long periodTicks) {
        return entity.getScheduler().runAtFixedRate(plugin, task, retired, ticks(delayTicks), ticks(periodTicks));
    }

    // ------------------------------------------------------------ ASYNC

    public ScheduledTask async(Runnable task) {
        return plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public ScheduledTask asyncLater(Runnable task, long delay, TimeUnit unit) {
        return plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> task.run(), delay, unit);
    }

    public ScheduledTask asyncTimer(Consumer<ScheduledTask> task, long delay, long period, TimeUnit unit) {
        return plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task, delay, period, unit);
    }

    // ------------------------------------------------------------ HELPERS

    /**
     * Выполнить код в нужном потоке для получателя сообщения:
     * для игрока - в потоке его региона, для консоли и прочих - в глобальном.
     * Удобно, чтобы отвечать на команды после асинхронной работы.
     */
    public void runFor(CommandSender sender, Runnable task) {
        if (sender instanceof Player player) {
            entity(player, task);
        } else {
            global(task);
        }
    }

    /** Выполнить supplier в потоке сущности и получить результат как future. */
    public <T> CompletableFuture<T> supplyOnEntity(Entity entity, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        ScheduledTask task = entity(entity, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        }, () -> future.completeExceptionally(new IllegalStateException("Entity removed")));
        if (task == null) {
            future.completeExceptionally(new IllegalStateException("Entity removed"));
        }
        return future;
    }

    /** Выполнить supplier в потоке региона локации и получить результат как future. */
    public <T> CompletableFuture<T> supplyOnRegion(Location loc, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        region(loc, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    /** true, если текущий поток владеет этим регионом (можно трогать блоки без планировщика). */
    public boolean isOwnedByCurrentRegion(Location loc) {
        return plugin.getServer().isOwnedByCurrentRegion(loc);
    }

    public boolean isOwnedByCurrentRegion(Entity entity) {
        return plugin.getServer().isOwnedByCurrentRegion(entity);
    }

    /** Отменить все задачи этого плагина (вызывается при выключении). */
    public void cancelAll() {
        plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
        plugin.getServer().getAsyncScheduler().cancelTasks(plugin);
    }
}
