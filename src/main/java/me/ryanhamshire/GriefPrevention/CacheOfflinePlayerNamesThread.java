package me.ryanhamshire.GriefPrevention;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.OfflinePlayer;

//thread to build the player name cache
public class CacheOfflinePlayerNamesThread extends Thread
{
    private final OfflinePlayer[] offlinePlayers;
    private final ConcurrentHashMap<String, UUID> playerNameToIDMap;

    CacheOfflinePlayerNamesThread(OfflinePlayer[] offlinePlayers, ConcurrentHashMap<String, UUID> playerNameToIDMap)
    {
        this.offlinePlayers = offlinePlayers;
        this.playerNameToIDMap = playerNameToIDMap;
    }

    @Override
    public void run()
    {
        for(OfflinePlayer player : this.offlinePlayers)
        {
            this.playerNameToIDMap.put(player.getName(), player.getUniqueId());
        }
    }
}