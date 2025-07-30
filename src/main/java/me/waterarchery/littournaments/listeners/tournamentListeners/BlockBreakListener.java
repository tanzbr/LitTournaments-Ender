package me.waterarchery.littournaments.listeners.tournamentListeners;

import me.waterarchery.littournaments.LitTournaments;
import me.waterarchery.littournaments.handlers.PointHandler;
import me.waterarchery.littournaments.handlers.TournamentHandler;
import me.waterarchery.littournaments.models.Tournament;
import me.waterarchery.littournaments.models.tournaments.BlockBreakTournament;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayList;
import java.util.List;

public class BlockBreakListener implements Listener {

    private final List<Location> placedBlocksCache = new ArrayList<>();

    public BlockBreakListener() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(LitTournaments.getInstance(), () -> {
            placedBlocksCache.clear();
        }, 20*60, 20*60);
    }

    @EventHandler (priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        PointHandler pointHandler = PointHandler.getInstance();
        TournamentHandler tournamentHandler = TournamentHandler.getInstance();
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (placedBlocksCache.contains(block.getLocation())) return;
        World world = block.getWorld();

        List<Tournament> tournaments = tournamentHandler.getTournaments(BlockBreakTournament.class);
        for (Tournament tournament : tournaments) {
            pointHandler.addPoint(player.getUniqueId(), tournament, world.getName(), block.getType().name(), 1);
        }
    }

    @EventHandler (priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        placedBlocksCache.add(event.getBlock().getLocation());
    }

}
