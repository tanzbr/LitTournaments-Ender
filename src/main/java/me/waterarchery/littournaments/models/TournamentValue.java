package me.waterarchery.littournaments.models;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

@Getter
public class TournamentValue {

    private final UUID uuid;
    private final long value;

    public TournamentValue(UUID uuid, long value) {
        this.uuid = uuid;
        this.value = value;
    }

    public String getName() {
        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String playerName = offlinePlayer.getName();
            
            // Se o nome for null, usar o UUID como fallback
            if (playerName == null || playerName.trim().isEmpty()) {
                return uuid.toString().substring(0, 8); // Primeiros 8 chars do UUID
            }
            
            return playerName;
        } catch (Exception e) {
            // Em caso de erro, retornar UUID truncado
            return uuid.toString().substring(0, 8);
        }
    }

}
