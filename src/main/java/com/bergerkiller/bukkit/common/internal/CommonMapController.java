package com.bergerkiller.bukkit.common.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.Task;
import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.collections.ImplicitlySharedSet;
import com.bergerkiller.bukkit.common.conversion.type.HandleConversion;
import com.bergerkiller.bukkit.common.events.ChunkLoadEntitiesEvent;
import com.bergerkiller.bukkit.common.events.EntityAddEvent;
import com.bergerkiller.bukkit.common.events.EntityRemoveEvent;
import com.bergerkiller.bukkit.common.events.PacketReceiveEvent;
import com.bergerkiller.bukkit.common.events.PacketSendEvent;
import com.bergerkiller.bukkit.common.events.map.MapAction;
import com.bergerkiller.bukkit.common.events.map.MapClickEvent;
import com.bergerkiller.bukkit.common.events.map.MapShowEvent;
import com.bergerkiller.bukkit.common.map.MapDisplay;
import com.bergerkiller.bukkit.common.map.MapDisplayProperties;
import com.bergerkiller.bukkit.common.map.MapDisplayTile;
import com.bergerkiller.bukkit.common.map.MapSession;
import com.bergerkiller.bukkit.common.map.binding.ItemFrameInfo;
import com.bergerkiller.bukkit.common.map.binding.MapDisplayInfo;
import com.bergerkiller.bukkit.common.map.util.MapLookPosition;
import com.bergerkiller.bukkit.common.map.util.MapUUID;
import com.bergerkiller.bukkit.common.nbt.CommonTagCompound;
import com.bergerkiller.bukkit.common.map.MapPlayerInput;
import com.bergerkiller.bukkit.common.protocol.PacketListener;
import com.bergerkiller.bukkit.common.protocol.PacketType;
import com.bergerkiller.bukkit.common.utils.ChunkUtil;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.FaceUtil;
import com.bergerkiller.bukkit.common.utils.ItemUtil;
import com.bergerkiller.bukkit.common.utils.LogicUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.PlayerUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.DataWatcher;
import com.bergerkiller.bukkit.common.wrappers.HumanHand;
import com.bergerkiller.bukkit.common.wrappers.IntHashMap;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayInSteerVehicleHandle;
import com.bergerkiller.generated.net.minecraft.server.level.WorldServerHandle;
import com.bergerkiller.generated.net.minecraft.world.entity.EntityHandle;
import com.bergerkiller.generated.net.minecraft.world.entity.decoration.EntityItemFrameHandle;
import com.bergerkiller.mountiplex.reflection.declarations.TypeDeclaration;
import com.bergerkiller.mountiplex.reflection.util.OutputTypeMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

public class CommonMapController implements PacketListener, Listener {
    // Whether this controller has been enabled
    private boolean isEnabled = false;
    // Whether tiling is supported. Disables findNeighbours() if false.
    private boolean isFrameTilingSupported = true;
    // Stores cached thread-safe lists of item frames by cluster key
    private final Map<ItemFrameClusterKey, Set<EntityItemFrameHandle> > itemFrameEntities = new HashMap<>();
    // Bi-directional mapping between map UUID and Map (durability) Id
    private final IntHashMap<MapUUID> mapUUIDById = new IntHashMap<MapUUID>();
    private final HashMap<MapUUID, Integer> mapIdByUUID = new HashMap<MapUUID, Integer>();
    // Stores Map Displays, mapped by Map UUID
    private final HashMap<UUID, MapDisplayInfo> maps = new HashMap<UUID, MapDisplayInfo>();
    private final ImplicitlySharedSet<MapDisplayInfo> mapsValues = new ImplicitlySharedSet<MapDisplayInfo>();
    // Stores map items for a short time while a player is moving it around in creative mode
    private final HashMap<UUID, CachedMapItem> cachedMapItems = new HashMap<UUID, CachedMapItem>();
    // How long a cached item is kept around and tracked when in the creative player's control
    private static final int CACHED_ITEM_MAX_LIFE = 20*60*10; // 10 minutes
    private static final int CACHED_ITEM_CLEAN_INTERVAL = 60; //60 ticks
    // Stores Map Displays by their Type information
    private final OutputTypeMap<MapDisplay> displays = new OutputTypeMap<MapDisplay>();
    // Stores player map input (through Vehicle Steer packets)
    private final HashMap<Player, MapPlayerInput> playerInputs = new HashMap<Player, MapPlayerInput>();
    // Tracks all item frames loaded on the server
    // Note: we are not using an IntHashMap because we need to iterate over the values, which is too slow with IntHashMap
    private final Map<Integer, ItemFrameInfo> itemFrames = new HashMap<>();
    // Tracks entity id's for which item metadata was sent before itemFrameInfo was available
    private final Set<Integer> itemFrameMetaMisses = new HashSet<>();
    // Tracks chunks neighbouring item frame clusters that need to be loaded before clusters load in
    private final HashMap<World, Map<IntVector2, Set<IntVector2>>> itemFrameClusterDependencies = new HashMap<>();
    // Tracks all maps that need to have their Map Ids re-synchronized (item slot / itemframe metadata updates)
    private SetMultimap<UUID, MapUUID> dirtyMapUUIDSet = HashMultimap.create(5, 100);
    private SetMultimap<UUID, MapUUID> dirtyMapUUIDSetTmp = HashMultimap.create(5, 100);
    // Caches used while executing findNeighbours()
    private FindNeighboursCache findNeighboursCache = null;
    // Neighbours of item frames to check for either x-aligned or z-aligned
    private static final BlockFace[] NEIGHBOUR_AXIS_ALONG_X = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH};
    private static final BlockFace[] NEIGHBOUR_AXIS_ALONG_Y = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final BlockFace[] NEIGHBOUR_AXIS_ALONG_Z = {BlockFace.UP, BlockFace.DOWN, BlockFace.WEST, BlockFace.EAST};
    // Item frame clusters previously computed, is short-lived
    private final Map<World, Map<IntVector3, ItemFrameCluster>> itemFrameClustersByWorld = new IdentityHashMap<>();
    // Whether the short-lived cache is used (only used during the update cycle)
    private boolean itemFrameClustersByWorldEnabled = false;
    // This counter is incremented every time a new map Id is added to the mapping
    // Every 1000 map ids we do a cleanup to free up slots for maps that no longer exist on the server
    // This is required, otherwise we can run out of the 32K map Ids we have available given enough uptime
    private static final int GENERATION_COUNTER_CLEANUP_INTERVAL = 1000;
    private int idGenerationCounter = 0;

    /**
     * These packet types are listened to handle the virtualized Map Display API
     */
    public static final PacketType[] PACKET_TYPES = {
            PacketType.OUT_MAP, PacketType.IN_STEER_VEHICLE, 
            PacketType.OUT_WINDOW_ITEMS, PacketType.OUT_WINDOW_SET_SLOT,
            PacketType.OUT_ENTITY_METADATA, PacketType.IN_SET_CREATIVE_SLOT
    };

    /**
     * Gets all registered Map Displays of a particular type
     * 
     * @param type
     * @return collection of map displays
     */
    @SuppressWarnings("unchecked")
    public <T extends MapDisplay> Collection<T> getDisplays(Class<T> type) {
        return (Collection<T>) displays.getAll(TypeDeclaration.fromClass(type));
    }

    /**
     * Gets a map of all displays
     * 
     * @return displays
     */
    public OutputTypeMap<MapDisplay> getDisplays() {
        return displays;
    }

    /**
     * Gets all maps available on the server that may store map displays
     * 
     * @return collection of map display info
     */
    public Collection<MapDisplayInfo> getMaps() {
        return this.maps.values();
    }

    /**
     * Gets all item frames that are tracked
     * 
     * @return item frames
     */
    public Collection<ItemFrameInfo> getItemFrames() {
        return itemFrames.values();
    }

    /**
     * Gets the item frame with the given entity id
     *
     * @param entityId
     * @return item frame
     */
    public ItemFrameInfo getItemFrame(int entityId) {
        return itemFrames.get(Integer.valueOf(entityId));
    }

    /**
     * Gets the Player Input controller for a certain player
     * 
     * @param player
     * @return player input
     */
    public synchronized MapPlayerInput getPlayerInput(Player player) {
        MapPlayerInput input;
        input = playerInputs.get(player);
        if (input == null) {
            input = new MapPlayerInput(player);
            playerInputs.put(player, input);
        }
        return input;
    }

    /**
     * Invalidates all map display data that is visible for a player,
     * causing it to be sent again to the player as soon as possible.
     * 
     * @param player
     */
    public synchronized void resendMapData(Player player) {
        UUID playerUUID = player.getUniqueId();
        for (MapDisplayInfo display : mapsValues.cloneAsIterable()) {
            if (display.getViewStackByPlayerUUID(playerUUID) != null) {
                for (MapSession session : display.getSessions()) {
                    for (MapSession.Owner owner : session.onlineOwners) {
                        if (owner.player == player) {
                            owner.clip.markEverythingDirty();
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets the Map display information for a map item displayed in an item frame.
     * All frames showing the same map will return the same {@link #MapDisplayInfo}.
     * If the item frame does not show a map, null is returned.
     * 
     * @param itemFrame to get the map information for
     * @return map display info
     */
    public synchronized MapDisplayInfo getInfo(ItemFrame itemFrame) {
        ItemFrameInfo frameInfo = itemFrames.get(itemFrame.getEntityId());
        if (frameInfo != null) {
            if (frameInfo.lastMapUUID == null) {
                return null;
            }
            MapDisplayInfo info = maps.get(frameInfo.lastMapUUID.getUUID());
            if (info == null) {
                info = new MapDisplayInfo(frameInfo.lastMapUUID.getUUID());
                maps.put(frameInfo.lastMapUUID.getUUID(), info);
                mapsValues.add(info);
            }
            return info;
        }
        return getInfo(getItemFrameItem(itemFrame));
    }

    /**
     * Gets the Map display information for a certain map item.
     * All items showing the same map will return the same {@link #MapDisplayInfo}.
     * If the item does not represent a map, null is returned.
     * 
     * @param mapItem to get the map information for
     * @return map display info
     */
    public synchronized MapDisplayInfo getInfo(ItemStack mapItem) {
        UUID uuid = CommonMapUUIDStore.getMapUUID(mapItem);
        return (uuid == null) ? null : getInfo(uuid);
    }

    /**
     * Gets the Map display information for a certain map item UUID.
     * Creates a new instance if none exists yet. Returns null if
     * the input UUID is null.
     * 
     * @param mapUUID The Unique ID of the map
     * @return display info for this UUID, or null if mapUUID is null
     */
    public synchronized MapDisplayInfo getInfo(UUID mapUUID) {
        if (mapUUID == null) {
            return null;
        } else {
            return maps.computeIfAbsent(mapUUID, uuid -> {
                MapDisplayInfo info = new MapDisplayInfo(uuid);
                mapsValues.add(info);
                return info;
            });
        }
    }

    /**
     * Gets the Map display information for a certain map item UUID.
     * Returns null if none exists by this UUID.
     *
     * @param mapUUID The Unique ID of the map
     * @return display info for this UUID, or null if none exists
     */
    public synchronized MapDisplayInfo getInfoIfExists(UUID mapUUID) {
        return maps.get(mapUUID);
    }

    /**
     * Updates the information of a map item, refreshing all item frames
     * and player inventories storing the item. Map displays are also
     * updated.
     * 
     * @param oldItem that was changed
     * @param newItem the old item was changed into
     */
    public synchronized void updateMapItem(ItemStack oldItem, ItemStack newItem) {
        boolean unchanged = isItemUnchanged(oldItem, newItem);
        UUID oldMapUUID = CommonMapUUIDStore.getMapUUID(oldItem);
        if (oldMapUUID != null) {
            // Change in the inventories of all player owners
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerInventory inv = player.getInventory();
                for (int i = 0; i < inv.getSize(); i++) {
                    UUID mapUUID = CommonMapUUIDStore.getMapUUID(inv.getItem(i));
                    if (oldMapUUID.equals(mapUUID)) {
                        if (unchanged) {
                            PlayerUtil.setItemSilently(player, i, newItem);
                        } else {
                            inv.setItem(i, newItem);
                        }
                    }
                }
            }

            // All item frames that show this same map
            for (ItemFrameInfo itemFrameInfo : CommonPlugin.getInstance().getMapController().getItemFrames()) {
                if (itemFrameInfo.lastMapUUID != null && oldMapUUID.equals(itemFrameInfo.lastMapUUID.getUUID())) {
                    if (unchanged) {
                        // When unchanged set the item in the metadata without causing a refresh
                        DataWatcher data = EntityHandle.fromBukkit(itemFrameInfo.itemFrame).getDataWatcher();
                        DataWatcher.Item<ItemStack> dataItem = data.getItem(EntityItemFrameHandle.DATA_ITEM);
                        dataItem.setValue(newItem, dataItem.isChanged());
                    } else {
                        // When changed, set it normally so the item is refreshed
                        itemFrameInfo.itemFrameHandle.setItem(newItem);
                    }
                }
            }

            // All map displays showing this item
            MapDisplayInfo info = maps.get(oldMapUUID);
            if (info != null) {
                for (MapSession session : info.getSessions()) {
                    session.display.setMapItemSilently(newItem);
                }
            }
        }

    }

    private boolean isItemUnchanged(ItemStack item1, ItemStack item2) {
        ItemStack trimmed_old_item = CommonMapController.trimExtraData(item1);
        ItemStack trimmed_new_item = CommonMapController.trimExtraData(item2);
        return LogicUtil.bothNullOrEqual(trimmed_old_item, trimmed_new_item);
    }

    /**
     * Starts all continuous background update tasks for maps
     * 
     * @param plugin
     * @param startedTasks
     */
    public void onEnable(CommonPlugin plugin, List<Task> startedTasks) {
        startedTasks.add(new HeldMapUpdater(plugin).start(1, 1));
        startedTasks.add(new FramedMapUpdater(plugin).start(1, 1));
        startedTasks.add(new ItemMapIdUpdater(plugin).start(1, 1));
        startedTasks.add(new MapInputUpdater(plugin).start(1, 1));
        startedTasks.add(new CachedMapItemCleaner(plugin).start(100, CACHED_ITEM_CLEAN_INTERVAL));
        startedTasks.add(new ByWorldItemFrameSetRefresher(plugin).start(1200, 1200)); // every minute

        this.isFrameTilingSupported = plugin.isFrameTilingSupported();

        // Discover all item frames that exist at plugin load, in already loaded worlds and chunks
        // This is only relevant during /reload, since at server start no world is loaded yet
        // No actual initialization is done yet, this happens next tick cycle!
        for (World world : Bukkit.getWorlds()) {
            for (EntityItemFrameHandle itemFrame : initItemFrameSetOfWorld(world)) {
                onAddItemFrame(itemFrame);
            }
        }

        // For all item frames we know right now, assume players have seen them already (if reloading)
        if (CommonUtil.getServerTicks() > 0) {
            this.itemFrames.values().forEach(info -> info.sentToPlayers = true);
        }

        // If this is a reload, that means players have already been watching maps potentially
        // To minimize glitches and problems, restore the map id data from last run
        CommonMapReloadFile.load(plugin, reloadFile -> {

            // Static reserved ids (other plugins have been using it)
            for (Integer staticId : reloadFile.staticReservedIds) {
                storeStaticMapId(staticId.intValue());
            }

            // Dynamic ids we have generated and assigned before
            // To avoid 'popping', make sure to pre-cache the same ones
            for (CommonMapReloadFile.DynamicMappedId dynamicMapId : reloadFile.dynamicMappedIds) {
                if (mapUUIDById.contains(dynamicMapId.id)) {
                    continue; // Already assigned, skip
                }
                if (mapIdByUUID.containsKey(dynamicMapId.uuid)) {
                    continue; // Already assigned, skip
                }

                // Store
                mapIdByUUID.put(dynamicMapId.uuid, dynamicMapId.id);
                mapUUIDById.put(dynamicMapId.id, dynamicMapId.uuid);
            }

            // Give a hint about Map UUID to avoid 'popping' when the item is refreshed
            for (CommonMapReloadFile.ItemFrameDisplayUUID displayUUID : reloadFile.itemFrameDisplayUUIDs) {
                ItemFrameInfo itemFrame = itemFrames.get(displayUUID.entityId);
                if (itemFrame != null) {
                    itemFrame.preReloadMapUUID = displayUUID.uuid;
                }
            }
        });

        // Done!
        this.isEnabled = true;
    }

    /**
     * Cleans up all running map displays and de-initializes all map display logic
     */
    public void onDisable(CommonPlugin plugin) {
        if (this.isEnabled) {
            this.isEnabled = false;

            // If reloading, save current map id state to avoid glitches
            CommonMapReloadFile.save(plugin, reloadFile -> {
                // Add static reserved / dynamic map ids
                for (Map.Entry<MapUUID, Integer> entry : mapIdByUUID.entrySet()) {
                    MapUUID mapUUID = entry.getKey();
                    if (mapUUID.isStaticUUID()) {
                        reloadFile.staticReservedIds.add(entry.getValue());
                    } else {
                        reloadFile.addDynamicMapId(mapUUID, entry.getValue());
                    }
                }

                // Add information about all item frames and what display they displayed last
                for (Map.Entry<Integer, ItemFrameInfo> entry : itemFrames.entrySet()) {
                    ItemFrameInfo info = entry.getValue();
                    if (info.lastMapUUID != null) {
                        reloadFile.addItemFrameDisplayUUID(entry.getKey().intValue(), info.lastMapUUID);
                    }
                }
            });

            for (MapDisplayInfo map : this.mapsValues.cloneAsIterable()) {
                for (MapSession session : new ArrayList<MapSession>(map.getSessions())) {
                    session.display.setRunning(false);
                }
            }
        }
    }

    /**
     * Activates or de-activates all map items for a particular plugin
     * 
     * @param plugin
     * @param pluginName
     * @param enabled
     */
    public void updateDependency(Plugin plugin, String pluginName, boolean enabled) {
        if (enabled) {
            //TODO: Go through all items on the server, and if lacking a display,
            // and set to use this plugin for it, re-create the display
            // Not enabled right now because it is kind of slow.
        } else {
            // End all map display sessions for this plugin
            MapDisplay.stopDisplaysForPlugin(plugin);
        }
    }

    /**
     * Adjusts the internal remapping from UUID to Map Id taking into account the new item
     * being synchronized to the player. If the item is that of a virtual map, the map Id
     * of the item is updated. NBT data that should not be synchronized is dropped.
     * 
     * @param item
     * @param tileX the X-coordinate of the tile in which the item is displayed
     * @param tileY the Y-coordinate of the tile in which the item is displayed
     * @return True if the item was changed and needs to be updated in the packet
     */
    public ItemStack handleItemSync(ItemStack item, int tileX, int tileY) {
        if (!CommonMapUUIDStore.isMap(item)) {
            return null;
        }

        // When a map UUID is specified, use that to dynamically allocate a map Id to use
        CommonTagCompound tag = ItemUtil.getMetaTag(item, false);
        if (tag != null) {
            UUID mapUUID = tag.getUUID("mapDisplay");
            if (mapUUID != null) {
                item = trimExtraData(item);
                int id = getMapId(new MapUUID(mapUUID, tileX, tileY));
                CommonMapUUIDStore.setItemMapId(item, id);
                return item;
            }
        }

        // Static map Id MUST be enforced
        storeStaticMapId(CommonMapUUIDStore.getItemMapId(item));
        return null;
    }

    /**
     * Obtains the Map Id used for displaying a particular map UUID
     * 
     * @param mapUUID to be displayed
     * @return map Id
     */
    public synchronized int getMapId(MapUUID mapUUID) {
        // Obtain from cache
        Integer storedMapId = mapIdByUUID.get(mapUUID);
        if (storedMapId != null) {
            return storedMapId.intValue();
        }

        // If the UUID is that of a static UUID, we must make sure to store it as such
        // We may have to remap the old Map Id to free up the Id slot we need
        int mapId = CommonMapUUIDStore.getStaticMapId(mapUUID.getUUID());
        if (mapId != -1) {
            storeStaticMapId(mapId);
            return mapId;
        }

        // Store a new map
        return storeDynamicMapId(mapUUID);
    }

    /**
     * Forces a particular map Id to stay static (unchanging) and stores it
     * as such in the mappings. No tiling is possible with static map Ids.
     * 
     * @param mapId
     */
    private synchronized void storeStaticMapId(int mapId) {
        if (storeDynamicMapId(mapUUIDById.get(mapId)) != mapId) {
            MapUUID mapUUID = new MapUUID(CommonMapUUIDStore.getStaticMapUUID(mapId), 0, 0);
            mapUUIDById.put(mapId, mapUUID);
            mapIdByUUID.put(mapUUID, mapId);
        }
    }

    /**
     * Figures out a new Map Id and prepares the display of a map with this new Id.
     * This method is only suitable for dynamically generated map Ids.
     * 
     * @param mapUUID to store
     * @return map Id that was assigned
     */
    private synchronized int storeDynamicMapId(MapUUID mapUUID) {
        // Null safety check
        if (mapUUID == null) {
            return -1;
        }

        // If the UUID is static, do not store anything and return the static Id instead
        int staticMapid = CommonMapUUIDStore.getStaticMapId(mapUUID.getUUID());
        if (staticMapid != -1) {
            return staticMapid;
        }

        // Increment this counter. The Map Id updater task will clean up unused maps every 1000 cycles.
        idGenerationCounter++;

        // Figure out a free Map Id we can use
        final int MAX_IDS = CommonCapabilities.MAP_ID_IN_NBT ? Integer.MAX_VALUE : Short.MAX_VALUE;
        for (int mapidValue = 0; mapidValue < MAX_IDS; mapidValue++) {
            if (!mapUUIDById.contains(mapidValue)) {
                // Check if the Map Id was changed compared to before
                boolean idChanged = mapIdByUUID.containsKey(mapUUID);

                // Store in mapping
                mapUUIDById.put(mapidValue, mapUUID);
                mapIdByUUID.put(mapUUID, Integer.valueOf(mapidValue));

                // If it had changed, update map items showing this map
                // uuid, and also re-send map packets for this id.
                // This is all done periodically on the main thread.
                if (idChanged) {
                    dirtyMapUUIDSet.get(mapUUID.getUUID()).add(mapUUID);
                }

                return mapidValue;
            }
        }
        return -1;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public synchronized void onPacketSend(PacketSendEvent event) {
        // Check if any virtual single maps are attached to this map
        if (event.getType() == PacketType.OUT_MAP) {    
            int itemid = event.getPacket().read(PacketType.OUT_MAP.mapId);
            this.storeStaticMapId(itemid);

            // This used to be used to just cancel interfering plugins
            // However, sometimes map data is sent before any item frame
            // set-item or similar packets. So, we'll have to act sooner.
            //
            // Note that we send packets with an ignoreListeners flag, causing
            // such packets to not go through this listener.
            /*
            MapUUID mapUUID = mapUUIDById.get(itemid);
            if (mapUUID == null) {
                this.storeStaticMapId(itemid);
            } else if (CommonMapUUIDStore.getStaticMapId(mapUUID.getUUID()) == -1) {
                event.setCancelled(true);
            }
            */
        }

        // Correct Map ItemStacks as they are sent to the clients (virtual)
        // This is always tile 0,0 (held map)
        if (event.getType() == PacketType.OUT_WINDOW_ITEMS) {
            List<ItemStack> items = event.getPacket().read(PacketType.OUT_WINDOW_ITEMS.items);
            ListIterator<ItemStack> iter = items.listIterator();
            while (iter.hasNext()) {
                ItemStack newItem = this.handleItemSync(iter.next(), 0, 0);
                if (newItem != null) {
                    iter.set(newItem);
                }
            }
        }
        if (event.getType() == PacketType.OUT_WINDOW_SET_SLOT) {
            ItemStack oldItem = event.getPacket().read(PacketType.OUT_WINDOW_SET_SLOT.item);
            ItemStack newItem = this.handleItemSync(oldItem, 0, 0);
            if (newItem != null) {
                event.getPacket().write(PacketType.OUT_WINDOW_SET_SLOT.item, newItem);
            }
        }
 
        // Correct the ItemStack displayed in Item Frames
        if (event.getType() == PacketType.OUT_ENTITY_METADATA) {
            int entityId = event.getPacket().read(PacketType.OUT_ENTITY_METADATA.entityId);
            ItemFrameInfo frameInfo = this.itemFrames.get(entityId);
            if (frameInfo == null) {
                // Verify the Item Frame DATA_ITEM key is inside the metadata of this packet
                // If this is the case, then this is metadata for an item frame and not a different entity
                // When that happens then a metadata packet was sent before the entity add event for it fired
                // To prevent glitches, track that in the itemFrameMetaMisses set
                List<DataWatcher.Item<Object>> items = event.getPacket().read(PacketType.OUT_ENTITY_METADATA.watchedObjects);
                if (items != null) {
                    for (DataWatcher.Item<Object> item : items) {
                        if (EntityItemFrameHandle.DATA_ITEM.equals(item.getKey())) {
                            itemFrameMetaMisses.add(entityId);
                            break;
                        }
                    }
                }
                return; // no information available or not an item frame
            }

            // Sometimes the metadata packet is handled before we do routine updates
            // If so, we can't do anything with it yet until the item frame is
            // loaded on the main thread.
            if (frameInfo.lastFrameItemUpdateNeeded || frameInfo.requiresFurtherLoading) {
                frameInfo.sentToPlayers = true;
                return; // not yet loaded, once it loads, resend the item details
            }
            if (frameInfo.lastMapUUID == null) {
                return; // not a map
            }
            frameInfo.sentToPlayers = true;
            int staticMapId = CommonMapUUIDStore.getStaticMapId(frameInfo.lastMapUUID.getUUID());
            if (staticMapId != -1) {
                this.storeStaticMapId(staticMapId);
                return; // static Id, not dynamic, no re-assignment
            }

            // Map Id is dynamically assigned, adjust metadata items to use this new Id
            // Avoid using any Bukkit or Wrapper types here for performance reasons
            int newMapId = this.getMapId(frameInfo.lastMapUUID);
            List<DataWatcher.Item<Object>> items = event.getPacket().read(PacketType.OUT_ENTITY_METADATA.watchedObjects);
            if (items != null) {
                ListIterator<DataWatcher.Item<Object>> itemsIter = items.listIterator();
                while (itemsIter.hasNext()) {
                    DataWatcher.Item<ItemStack> item = itemsIter.next().translate(EntityItemFrameHandle.DATA_ITEM);
                    if (item == null) {
                        continue;
                    }

                    ItemStack metaItem = item.getValue();
                    if (metaItem == null || CommonMapUUIDStore.getItemMapId(metaItem) == newMapId) {
                        continue;
                    }

                    ItemStack newMapItem = ItemUtil.cloneItem(metaItem);
                    CommonMapUUIDStore.setItemMapId(newMapItem, newMapId);

                    item = item.clone();
                    item.setValue(newMapItem, item.isChanged());
                    itemsIter.set((DataWatcher.Item<Object>) (DataWatcher.Item) item);
                }
            }
        }
    }

    @Override
    public synchronized void onPacketReceive(PacketReceiveEvent event) {
        // Handle input coming from the player for the map
        if (event.getType() == PacketType.IN_STEER_VEHICLE) {
            Player p = event.getPlayer();
            MapPlayerInput input = playerInputs.get(p);
            if (input != null) {
                PacketPlayInSteerVehicleHandle packet = PacketPlayInSteerVehicleHandle.createHandle(event.getPacket().getHandle());
                int dx = (int) -Math.signum(packet.getSideways());
                int dy = (int) -Math.signum(packet.getForwards());
                int dz = 0;
                if (packet.isUnmount()) {
                    dz -= 1;
                }
                if (packet.isJump()) {
                    dz += 1;
                }

                // Receive input. If it will be handled, it will cancel further handling of this packet
                event.setCancelled(input.receiveInput(dx, dy, dz));
            }
        }

        // When in creative mode, players may accidentally set the 'virtual' map Id as the actual Id in their inventory
        // We have to prevent that in here
        if (event.getType() == PacketType.IN_SET_CREATIVE_SLOT) {
            ItemStack item = event.getPacket().read(PacketType.IN_SET_CREATIVE_SLOT.item);
            UUID mapUUID = CommonMapUUIDStore.getMapUUID(item);
            if (mapUUID != null && CommonMapUUIDStore.getStaticMapId(mapUUID) == -1) {
                // Dynamic Id map. Since we do not refresh NBT data over the network, this packet contains incorrect data
                // Find the original item the player took (by UUID). If it exists, merge its NBT data with this item.
                // For this we also have the map item cache, which is filled with data the moment a player picks up an item
                // This data is kept around for 10 minutes (unlikely a player will hold onto it for that long...)
                ItemStack originalMapItem = null;
                CachedMapItem cachedItem = this.cachedMapItems.get(mapUUID);
                if (cachedItem != null) {
                    cachedItem.life = CACHED_ITEM_MAX_LIFE;
                    originalMapItem = cachedItem.item;
                } else {
                    for (ItemStack oldItem : event.getPlayer().getInventory()) {
                        if (mapUUID.equals(CommonMapUUIDStore.getMapUUID(oldItem))) {
                            originalMapItem = oldItem.clone();
                            break;
                        }
                    }
                }
                if (originalMapItem != null) {
                    // Original item was found. Restore all properties of that item.
                    // Keep metadata the player can control, replace everything else
                    ItemUtil.setMetaTag(item, ItemUtil.getMetaTag(originalMapItem));
                    event.getPacket().write(PacketType.IN_SET_CREATIVE_SLOT.item, item);
                } else {
                    // Dynamic Id. Force a map id value of 0 to prevent creation of new World Map instances
                    item = ItemUtil.cloneItem(item);
                    CommonMapUUIDStore.setItemMapId(item, 0);
                    event.getPacket().write(PacketType.IN_SET_CREATIVE_SLOT.item, item);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    protected synchronized void onPlayerJoin(PlayerJoinEvent event) {
        // Let everyone know we got a player over here!
        Player player = event.getPlayer();
        for (MapDisplayInfo map : this.mapsValues.cloneAsIterable()) {
            for (MapSession session : map.getSessions()) {
                session.updatePlayerOnline(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    protected synchronized void onPlayerQuit(PlayerQuitEvent event) {
        MapPlayerInput input = this.playerInputs.remove(event.getPlayer());
        if (input != null) {
            input.onDisconnected();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    protected void onPlayerRespawn(PlayerRespawnEvent event) {
        this.resendMapData(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    protected void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        this.resendMapData(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    protected synchronized void onEntityAdded(EntityAddEvent event) {
        if (event.getEntity() instanceof ItemFrame) {
            EntityItemFrameHandle frameHandle = EntityItemFrameHandle.createHandle(HandleConversion.toEntityHandle(event.getEntity()));
            getItemFrameEntities(new ItemFrameClusterKey(frameHandle)).add(frameHandle);
            onAddItemFrame(frameHandle);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    protected synchronized void onEntityRemoved(EntityRemoveEvent event) {
        if (event.getEntity() instanceof ItemFrame) {
            ItemFrame frame = (ItemFrame) event.getEntity();
            EntityItemFrameHandle frameHandle = EntityItemFrameHandle.fromBukkit(frame);
            getItemFrameEntities(new ItemFrameClusterKey(frameHandle)).remove(frameHandle);
            ItemFrameInfo info = itemFrames.get(frame.getEntityId());
            if (info != null) {
                info.removed = true;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    protected synchronized void onChunkEntitiesLoaded(ChunkLoadEntitiesEvent event) {
        onChunkEntitiesLoaded(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    protected synchronized void onWorldLoad(WorldLoadEvent event) {
        for (EntityItemFrameHandle frame : initItemFrameSetOfWorld(event.getWorld())) {
            onAddItemFrame(frame);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    protected void onWorldUnload(WorldUnloadEvent event) {
        this.deinitItemFrameSetOfWorld(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    protected synchronized void onInventoryCreativeSlot(InventoryCreativeEvent event) {
        // When taking items from the inventory in creative mode, store metadata of what is taken
        // We apply this metadata again when receiving the item
        if (event.getResult() != Result.DENY) {
            UUID mapUUID = CommonMapUUIDStore.getMapUUID(event.getCurrentItem());
            if (mapUUID != null) {
                this.cachedMapItems.put(mapUUID, new CachedMapItem(event.getCurrentItem().clone()));
            }
        }
    }

    private void onAddItemFrame(EntityItemFrameHandle frame) {
        int entityId = frame.getId();
        if (itemFrames.containsKey(entityId)) {
            return;
        }

        // Add Item Frame Info
        ItemFrameInfo frameInfo = new ItemFrameInfo(this, frame);
        itemFrames.put(entityId, frameInfo);
        if (itemFrameMetaMisses.remove(entityId)) {
            frameInfo.needsItemRefresh = true;
            frameInfo.sentToPlayers = true;
        }
    }

    private void onChunkEntitiesLoaded(Chunk chunk) {
        World world = chunk.getWorld();

        Set<IntVector2> dependingChunks;
        {
            Map<IntVector2, Set<IntVector2>> dependencies = this.itemFrameClusterDependencies.get(world);
            if (dependencies == null || (dependingChunks = dependencies.remove(new IntVector2(chunk))) == null) {
                return;
            }
        }

        boolean wasClustersByWorldCacheEnabled = this.itemFrameClustersByWorldEnabled;
        try {
            this.itemFrameClustersByWorldEnabled = true;
            for (IntVector2 depending : dependingChunks) {
                // Check this depending chunk is still loaded with all entities inside
                // If not, then when it loads the cluster will be revived then
                if (!WorldUtil.isChunkEntitiesLoaded(world, depending.x, depending.z)) {
                    continue;
                }

                // Go by all entities in this depending chunk to find the item frames
                // Quicker than iterating all item frames on the world
                for (Entity entity : ChunkUtil.getEntities(WorldUtil.getChunk(world, depending.x, depending.z))) {
                    if (!(entity instanceof ItemFrame)) {
                        continue;
                    }

                    // Recalculate UUID, this will re-discover the cluster
                    // May also revive other item frames that were part of the same cluster
                    // Note that if this chunk being loaded contained item frames part of the cluster,
                    // the cluster is already revived. Entity add handling occurs prior.
                    ItemFrameInfo frameInfo = this.itemFrames.get(entity.getEntityId());
                    if (frameInfo != null) {
                        frameInfo.onChunkDependencyLoaded();
                    }
                }
            }
        } finally {
            this.itemFrameClustersByWorldEnabled = wasClustersByWorldCacheEnabled;
            if (!wasClustersByWorldCacheEnabled) {
                itemFrameClustersByWorld.clear();
            }
        }
    }

    /**
     * Checks whether an item frame cluster's chunk dependency has all item frames currently loaded.
     * If not, returns false, and tracks this chunk for when it loads in the future.
     *
     * @param world World
     * @param dependency Dependency
     * @return True if the chunk dependency is loaded, False if it is not
     */
    public synchronized boolean checkClusterChunkDependency(World world, ItemFrameCluster.ChunkDependency dependency) {
        if (!this.isFrameTilingSupported) {
            return true; // No need to even check
        } else if (WorldUtil.isChunkEntitiesLoaded(world, dependency.neighbour.x, dependency.neighbour.z)) {
            return true;
        } else {
            Map<IntVector2, Set<IntVector2>> dependencies = this.itemFrameClusterDependencies.computeIfAbsent(world, unused -> new HashMap<>());
            Set<IntVector2> dependingChunks = dependencies.computeIfAbsent(dependency.neighbour, unused -> new HashSet<>());
            dependingChunks.add(dependency.self);
            return false;
        }
    }

    // Used for findLookingAt
    private static class LookAtSearchResult {
        public final MapDisplay display;
        public final MapLookPosition lookPosition;

        public LookAtSearchResult(MapDisplay display, MapLookPosition lookPosition) {
            this.display = display;
            this.lookPosition = lookPosition;
        }

        /**
         * Handles the click event for this looking-at information
         *
         * @param player
         * @param action
         * @return Event result, can check {@link MapClickEvent#isCancelled()}
         */
        public MapClickEvent click(Player player, MapAction action) {
            // Fire event
            MapClickEvent event = new MapClickEvent(player, this.lookPosition, this.display, action);
            CommonUtil.callEvent(event);
            if (!event.isCancelled()) {
                if (action == MapAction.LEFT_CLICK) {
                    event.getDisplay().onLeftClick(event);
                    event.getDisplay().getRootWidget().onLeftClick(event);
                } else {
                    event.getDisplay().onRightClick(event);
                    event.getDisplay().getRootWidget().onRightClick(event);
                }
            }
            return event;
        }
    }

    private LookAtSearchResult findLookingAt(Player player, ItemFrame itemFrame) {
        Location eye = player.getEyeLocation();
        return findLookingAt(player, itemFrame, eye.toVector(), eye.getDirection());
    }

    private LookAtSearchResult findLookingAt(Player player, ItemFrame itemFrame, Vector startPosition, Vector lookDirection) {
        MapDisplayInfo info = getInfo(itemFrame);
        if (info == null) {
            return null; // no map here
        }

        // Find the Display this player is sees on this map
        MapDisplayInfo.ViewStack stack = info.getViewStackByPlayerUUID(player.getUniqueId());
        if (stack == null || stack.stack.isEmpty()) {
            return null; // no visible display for this player
        }

        // Find the item frame metadata information
        ItemFrameInfo frameInfo = this.itemFrames.get(itemFrame.getEntityId());
        if (frameInfo == null) {
            return null; // not tracked
        }

        // Ask item frame to compute look-at information
        MapLookPosition position = frameInfo.findLookPosition(startPosition, lookDirection);
        if (position == null) {
            return null; // doesn't really happen (withinBounds = false), but just in case
        }

        // Keep position within bounds of the display
        // If very much out of bounds (>16 pixels) fail the looking-at check
        // This loose-ness allows for smooth clicking between frames without failures
        MapDisplay display = stack.stack.getLast();
        double new_x = position.getDoubleX();
        double new_y = position.getDoubleY();
        final double limit = 16.0;
        if (new_x < -limit || new_y < -limit || new_x > (display.getWidth() + limit) || new_y >= (display.getHeight() + limit)) {
            return null;
        } else if (new_x < 0.0 || new_y < 0.0 || new_x >= display.getWidth() || new_y >= display.getHeight()) {
            new_x = MathUtil.clamp(new_x, 0.0, (double) display.getWidth() - 1e-10);
            new_y = MathUtil.clamp(new_y, 0.0, (double) display.getHeight() - 1e-10);
            position = new MapLookPosition(position.getItemFrameInfo(), new_x, new_y, position.getDistance(), position.isWithinBounds());
        }

        return new LookAtSearchResult(display, position);
    }

    // Returns true if base click was cancelled
    private boolean dispatchClickAction(Player player, ItemFrame itemFrame, Vector startPosition, Vector lookDirection, MapAction action) {
        LookAtSearchResult lookAt = findLookingAt(player, itemFrame, startPosition, lookDirection);
        return lookAt != null && lookAt.click(player, action).isCancelled();
    }

    // Returns true if base click was cancelled
    private boolean dispatchClickActionApprox(Player player, ItemFrame itemFrame, MapAction action) {
        Location eye = player.getEyeLocation();
        return dispatchClickAction(player, itemFrame, eye.toVector(), eye.getDirection(), action);
    }

    // Returns true if base click was cancelled
    private boolean dispatchClickActionFromBlock(Player player, Block clickedBlock, BlockFace clickedFace, MapAction action) {
        Vector look = player.getEyeLocation().getDirection();
        final double eps = 0.001;

        double x1 = clickedBlock.getX() + 0.5 + (double) clickedFace.getModX() * 0.5;
        double y1 = clickedBlock.getY() + 0.5 + (double) clickedFace.getModY() * 0.5;
        double z1 = clickedBlock.getZ() + 0.5 + (double) clickedFace.getModZ() * 0.5;

        // Based on look direction, expand the search radius to check for corner item frames
        double x2 = x1, y2 = y1, z2 = z1;
        if (look.getX() < 0.0) {
            x2 += 1.0 + eps;
            x1 -= eps;
        } else {
            x2 -= 1.0 + eps;
            x1 += eps;
        }
        if (look.getY() < 0.0) {
            y2 += 1.0 + eps;
            y1 -= eps;
        } else {
            y2 -= 1.0 + eps;
            y1 += eps;
        }
        if (look.getZ() < 0.0) {
            z2 += 1.0 + eps;
            z1 -= eps;
        } else {
            z2 -= 1.0 + eps;
            z1 += eps;
        }

        LookAtSearchResult bestApprox = null;
        for (Entity e : WorldUtil.getEntities(clickedBlock.getWorld(), null, 
                x1, y1, z1, x2, y2, z2))
        {
            if (e instanceof ItemFrame) {
                LookAtSearchResult result = this.findLookingAt(player, (ItemFrame) e);
                if (result != null) {
                    // If within bounds, pick it right away!
                    if (result.lookPosition.isWithinBounds()) {
                        return result.click(player, action).isCancelled();
                    }

                    // Select the lowest distance result
                    if (bestApprox == null || bestApprox.lookPosition.getDistance() > result.lookPosition.getDistance()) {
                        bestApprox = result;
                    }
                }
            }
        }
        return bestApprox != null && bestApprox.click(player, action).isCancelled();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    protected void onEntityLeftClick(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame) || !(event.getDamager() instanceof Player)) {
            return;
        }
        event.setCancelled(dispatchClickActionApprox(
                (Player) event.getDamager(),
                (ItemFrame) event.getEntity(),
                MapAction.LEFT_CLICK));
    }

    private Vector lastClickOffset = null;

    @EventHandler(priority = EventPriority.MONITOR)
    protected void onEntityRightClickAt(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame) {
            lastClickOffset = event.getClickedPosition();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    protected void onEntityRightClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) {
            return;
        }
        ItemFrame itemFrame = (ItemFrame) event.getRightClicked();
        if (lastClickOffset != null) {
            Location eye = event.getPlayer().getEyeLocation();
            Location pos = itemFrame.getLocation().add(lastClickOffset);
            Vector dir = eye.getDirection();
            lastClickOffset = null;

            // Move the position back a distance so the computed distance later matches up
            // A bit of a hack, but easier than injecting distance after the fact
            double distance = eye.distance(pos);
            pos.subtract(dir.clone().multiply(distance));

            event.setCancelled(dispatchClickAction(event.getPlayer(), itemFrame, pos.toVector(), dir, MapAction.RIGHT_CLICK));
        } else {
            event.setCancelled(dispatchClickActionApprox(event.getPlayer(), itemFrame, MapAction.RIGHT_CLICK));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    protected void onBlockInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        MapAction action;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            action = MapAction.LEFT_CLICK;
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            action = MapAction.RIGHT_CLICK;
        } else {
            return;
        }
        if (dispatchClickActionFromBlock(event.getPlayer(), event.getClickedBlock(), event.getBlockFace(), action)) {
            event.setUseInteractedBlock(Result.DENY);
            event.setCancelled(true);
            event.setUseItemInHand(Result.DENY);
        }
    }

    private synchronized void cleanupUnusedUUIDs(Set<MapUUID> existingMapUUIDs) {
        HashSet<MapUUID> idsToRemove = new HashSet<MapUUID>(mapIdByUUID.keySet());
        idsToRemove.removeAll(existingMapUUIDs);
        for (MapUUID toRemove : idsToRemove) {
            // Clean up the map display information first
            MapDisplayInfo displayInfo = maps.get(toRemove.getUUID());
            if (displayInfo != null) {
                if (displayInfo.getSessions().isEmpty()) {
                    MapDisplayInfo removed = maps.remove(toRemove.getUUID());
                    if (removed != null) {
                        mapsValues.remove(removed);
                    }
                } else {
                    continue; // still has an active session; cannot remove
                }
            }

            // Clean up from bi-directional mapping
            Integer mapId = mapIdByUUID.remove(toRemove);
            if (mapId != null) {
                mapUUIDById.remove(mapId.intValue());
            }

            // Clean up from 'dirty' set (probably never needed)
            dirtyMapUUIDSet.removeAll(toRemove.getUUID());
        }
    }

    private synchronized void handleMapShowEvent(MapShowEvent event) {
        // Check if there are other map displays that should be shown to the player automatically
        // This uses the 'isGlobal()' property of the display
        MapDisplayInfo info = CommonMapController.this.getInfo(event.getMapUUID());
        boolean hasDisplay = false;
        if (info != null) {
            for (MapSession session : info.getSessions()) {
                if (session.display.isGlobal()) {
                    session.display.addOwner(event.getPlayer());
                    hasDisplay = true;
                    break;
                }
            }
        }

        // When defined in the NBT of the item, construct the Map Display automatically
        // Do not do this when one was already assigned (global, or during event handling)
        MapDisplayProperties properties = MapDisplayProperties.of(event.getMapItem());
        if (!hasDisplay && !event.hasDisplay() && properties != null) {
            Class<? extends MapDisplay> displayClass = properties.getMapDisplayClass();
            if (displayClass != null) {
                Plugin plugin = properties.getPlugin();
                if (plugin != null) {
                    try {
                        MapDisplay display = displayClass.newInstance();
                        event.setDisplay((JavaPlugin) plugin, display);;
                    } catch (InstantiationException | IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        CommonUtil.callEvent(event);
    }

    /**
     * Gets the item frame map UUID, also handling the tile information of the item frame
     * 
     * @param itemFrame to get the map UUID from
     */
    private MapUUID getItemFrameMapUUID(EntityItemFrameHandle itemFrame) {
        if (itemFrame == null) {
            return null;
        } else {
            ItemFrameInfo info = this.itemFrames.get(itemFrame.getId());
            if (info == null) {
                return null;
            } else {
                info.updateItem();
                return info.lastMapUUID;
            }
        }
    }

    public class ItemMapIdUpdater extends Task {

        public ItemMapIdUpdater(JavaPlugin plugin) {
            super(plugin);
        }

        @Override
        public void run() {
            synchronized (CommonMapController.this) {
                updateMapIds();
            }
        }

        public void updateMapIds() {
            // Remove non-existing maps from the internal mapping
            if (idGenerationCounter > GENERATION_COUNTER_CLEANUP_INTERVAL) {
                idGenerationCounter = 0;

                // Find all map UUIDs that exist on the server
                HashSet<MapUUID> validUUIDs = new HashSet<MapUUID>();
                for (Set<EntityItemFrameHandle> itemFrameSet : itemFrameEntities.values()) {
                    for (EntityItemFrameHandle itemFrame : itemFrameSet) {
                        MapUUID mapUUID = getItemFrameMapUUID(itemFrame);
                        if (mapUUID != null) {
                            validUUIDs.add(mapUUID);
                        }
                    }
                }

                for (Player player : Bukkit.getOnlinePlayers()) {
                    PlayerInventory inv = player.getInventory();
                    for (int i = 0; i < inv.getSize(); i++) {
                        ItemStack item = inv.getItem(i);
                        UUID mapUUID = CommonMapUUIDStore.getMapUUID(item);
                        if (mapUUID != null) {
                            validUUIDs.add(new MapUUID(mapUUID));
                        }
                    }
                }

                // Perform the cleanup (synchronized access required!)
                cleanupUnusedUUIDs(validUUIDs);
            }

            // Refresh items known to clients when Map Ids are re-assigned
            // Swap around the tmp and main set every tick
            final SetMultimap<UUID, MapUUID> dirtyMaps;
            dirtyMaps = dirtyMapUUIDSet;
            dirtyMapUUIDSet = dirtyMapUUIDSetTmp;
            dirtyMapUUIDSetTmp = dirtyMaps;
            if (!dirtyMaps.isEmpty()) {
                // Refresh all player inventories that contain this map
                // This will result in new SetItemSlot packets being sent, refreshing the map Id
                for (Player player : Bukkit.getOnlinePlayers()) {
                    PlayerInventory inv = player.getInventory();
                    for (int i = 0; i < inv.getSize(); i++) {
                        ItemStack item = inv.getItem(i);
                        UUID uuid = CommonMapUUIDStore.getMapUUID(item);
                        if (dirtyMaps.containsKey(uuid)) {
                            inv.setItem(i, item.clone());
                        }
                    }
                }

                // Refresh all item frames that display this map
                // This will result in a new EntityMetadata packets being sent, refreshing the map Id
                // After updating all item frames, resend the maps
                dirtyMaps.keySet().stream()
                    .map(maps::get)
                    .filter(Objects::nonNull)
                    .forEach(info -> {
                        // Refresh item of all affected item frames
                        // This re-sends metadata packets
                        final Set<MapUUID> mapUUIDs = dirtyMaps.get(info.getUniqueId());
                        for (ItemFrameInfo itemFrameInfo : info.getItemFrames()) {
                            if (mapUUIDs.contains(itemFrameInfo.lastMapUUID)) {
                                itemFrameInfo.itemFrameHandle.refreshItem();
                            }
                        }

                        // Resend map data for all affected tiles
                        for (MapSession session : info.getSessions()) {
                            for (MapDisplayTile tile : session.tiles) {
                                if (mapUUIDs.contains(tile.getMapTileUUID())) {
                                    session.onlineOwners.forEach(o -> o.sendDirtyTile(tile));
                                }
                            }
                        }
                    });

                // Done processing, wipe
                dirtyMaps.clear();
            }
        }
    }

    /**
     * Refreshes the input state of maps every tick, when input is intercepted
     */
    public class MapInputUpdater extends Task {

        public MapInputUpdater(JavaPlugin plugin) {
            super(plugin);
        }

        @Override
        public void run() {
            Iterator<Map.Entry<Player, MapPlayerInput>> iter = playerInputs.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<Player, MapPlayerInput> entry = iter.next();
                if (entry.getKey().isOnline()) {
                    entry.getValue().onTick();
                } else {
                    entry.getValue().onDisconnected();
                    iter.remove();
                }
            }
        }
    }

    /**
     * Updates the players viewing item frames and fires events for them
     */
    public class FramedMapUpdater extends Task {

        private ItemFrameInfo info = null;
        private final LogicUtil.ItemSynchronizer<Player, Player> synchronizer = new LogicUtil.ItemSynchronizer<Player, Player>() {
            @Override
            public boolean isItem(Player item, Player value) {
                return item == value;
            }

            @Override
            public Player onAdded(Player player) {
                handleMapShowEvent(new MapShowEvent(player, info.itemFrame));
                return player;
            }

            @Override
            public void onRemoved(Player player) {
                //TODO!!!
                //CommonUtil.callEvent(new HideFramedMapEvent(player, info.itemFrame));
            }
        };

        public FramedMapUpdater(JavaPlugin plugin) {
            super(plugin);
        }

        @Override
        public void run() {
            // Enable the item frame cluster cache
            itemFrameClustersByWorldEnabled = true;

            // Iterate all tracked item frames and update them
            synchronized (CommonMapController.this) {
                Iterator<ItemFrameInfo> itemFrames_iter = itemFrames.values().iterator();
                while (itemFrames_iter.hasNext()) {
                    info = itemFrames_iter.next();
                    if (info.handleRemoved()) {
                        itemFrames_iter.remove();
                        continue;
                    }

                    info.updateItemAndViewers(synchronizer);

                    // May find out it's removed during the update
                    if (info.handleRemoved()) {
                        itemFrames_iter.remove();
                        continue;
                    }
                }
            }

            // Update the player viewers of all map displays
            for (MapDisplayInfo map : mapsValues.cloneAsIterable()) {
                map.updateViewersAndResolution();
            }

            for (ItemFrameInfo info : itemFrames.values()) {
                // Resend Item Frame item (metadata) when the UUID changes
                // UUID can change when the relative tile displayed changes
                // This happens when a new item frame is placed left/above a display
                if (info.needsItemRefresh) {
                    info.needsItemRefresh = false;
                    info.itemFrameHandle.refreshItem();
                }
            }

            // Disable cache again and wipe
            itemFrameClustersByWorldEnabled = false;
            itemFrameClustersByWorld.clear();
        }
    }

    /**
     * Continuously checks if a map item is being held by a player
     */
    public class HeldMapUpdater extends Task implements LogicUtil.ItemSynchronizer<Player, HeldMapUpdater.MapViewEntry> {
        private final List<MapViewEntry> entries = new LinkedList<MapViewEntry>();

        public HeldMapUpdater(JavaPlugin plugin) {
            super(plugin);
        }

        @Override
        public void run() {
            LogicUtil.synchronizeList(entries, CommonUtil.getOnlinePlayers(), this);
            for (MapViewEntry entry : entries) {
                entry.update();
            }
        }

        @Override
        public boolean isItem(MapViewEntry entry, Player player) {
            return entry.player == player;
        }

        @Override
        public MapViewEntry onAdded(Player player) {
            return new MapViewEntry(player);
        }

        @Override
        public void onRemoved(MapViewEntry entry) {
        }

        private class MapViewEntry {
            public final Player player;
            public ItemStack lastLeftHand = null;
            public ItemStack lastRightHand = null;

            public MapViewEntry(Player player) {
                this.player = player;
            }

            public void update() {
                ItemStack currLeftHand = PlayerUtil.getItemInHand(this.player, HumanHand.LEFT);
                ItemStack currRightHand = PlayerUtil.getItemInHand(this.player, HumanHand.RIGHT);

                if (CommonMapUUIDStore.isMap(currLeftHand) 
                        && !mapEquals(currLeftHand, lastLeftHand) 
                        && !mapEquals(currLeftHand, lastRightHand)) {
                    // Left hand now has a map! We did not swap hands, either.
                    handleMapShowEvent(new MapShowEvent(player, HumanHand.LEFT, currLeftHand));
                }
                if (CommonMapUUIDStore.isMap(currRightHand) 
                        && !mapEquals(currRightHand, lastRightHand) 
                        && !mapEquals(currRightHand, lastLeftHand)) {
                    // Right hand now has a map! We did not swap hands, either.
                    handleMapShowEvent(new MapShowEvent(player, HumanHand.RIGHT, currRightHand));
                }

                lastLeftHand = currLeftHand;
                lastRightHand = currRightHand;
            }

            private final boolean mapEquals(ItemStack item1, ItemStack item2) {
                UUID mapUUID1 = CommonMapUUIDStore.getMapUUID(item1);
                UUID mapUUID2 = CommonMapUUIDStore.getMapUUID(item2);
                return mapUUID1 != null && mapUUID2 != null && mapUUID1.equals(mapUUID2);
            }
        }
    }

    /**
     * Removes map items from the cache when they have been in there for too long
     */
    public class CachedMapItemCleaner extends Task {

        public CachedMapItemCleaner(JavaPlugin plugin) {
            super(plugin);
        }

        @Override
        public void run() {
            synchronized (CommonMapController.this) {
                if (!CommonMapController.this.cachedMapItems.isEmpty()) {
                    Iterator<CachedMapItem> iter = CommonMapController.this.cachedMapItems.values().iterator();
                    while (iter.hasNext()) {
                        if ((iter.next().life -= CACHED_ITEM_CLEAN_INTERVAL) <= 0) {
                            iter.remove();
                        }
                    }
                }
            }
        }
    }

    // Runs every now and then to reset and refresh the by-world item frame sets
    // This makes sure bugs or glitches don't cause item frames to stay in there forever
    public class ByWorldItemFrameSetRefresher extends Task {

        public ByWorldItemFrameSetRefresher(JavaPlugin plugin) {
            super(plugin);
        }

        @Override
        public void run() {
            Collection<World> worlds = Bukkit.getWorlds();
            synchronized (CommonMapController.this) {
                deinitItemFrameListForWorldsNotIn(worlds);
                for (World world : worlds) {
                    initItemFrameSetOfWorld(world);
                }
            }
        }
    }

    /**
     * An item that sits around in memory while players in creative mode are moving the item around.
     * 
     */
    private static class CachedMapItem {
        public int life;
        public final ItemStack item;

        public CachedMapItem(ItemStack item) {
            this.item = item;
            this.life = CACHED_ITEM_MAX_LIFE;
        }
    }

    /**
     * Finds a cluster of all connected item frames that an item frame is part of
     * 
     * @param itemFrame
     * @return cluster
     */
    public final synchronized ItemFrameCluster findCluster(EntityItemFrameHandle itemFrame, IntVector3 itemFramePosition) {
        UUID itemFrameMapUUID;
        if (!this.isFrameTilingSupported || (itemFrameMapUUID = itemFrame.getItemMapDisplayUUID()) == null) {
            return new ItemFrameCluster(itemFrame.getFacing(),
                    Collections.singleton(itemFramePosition), 0); // no neighbours or tiling disabled
        }

        // Look up in cache first
        World world = itemFrame.getBukkitWorld();
        Map<IntVector3, ItemFrameCluster> cachedClusters;
        if (itemFrameClustersByWorldEnabled) {
            cachedClusters = itemFrameClustersByWorld.get(world);
            if (cachedClusters == null) {
                cachedClusters = new HashMap<>();
                itemFrameClustersByWorld.put(world, cachedClusters);
            }
            ItemFrameCluster fromCache = cachedClusters.get(itemFramePosition);
            if (fromCache != null) {
                return fromCache;
            }
        } else {
            cachedClusters = null;
        }

        // Take cache entry
        FindNeighboursCache cache = this.findNeighboursCache;
        if (cache != null) {
            cache.reset();
            this.findNeighboursCache = null;
        } else {
            cache = new FindNeighboursCache();
        }
        try {
            // Find all item frames that:
            // - Are on the same world as this item frame
            // - Facing the same way
            // - Along the same x/y/z (facing)
            // - Same ItemStack map UUID

            ItemFrameClusterKey key = new ItemFrameClusterKey(world, itemFrame.getFacing(), itemFramePosition);
            for (EntityItemFrameHandle otherFrame : getItemFrameEntities(key)) {
                if (otherFrame.getId() == itemFrame.getId()) {
                    continue;
                }
                UUID otherFrameMapUUID = otherFrame.getItemMapDisplayUUID();
                if (itemFrameMapUUID.equals(otherFrameMapUUID)) {
                    cache.put(otherFrame);
                }
            }

            BlockFace[] neighbourAxis;
            if (FaceUtil.isAlongY(key.facing)) {
                neighbourAxis = NEIGHBOUR_AXIS_ALONG_Y;
            } else if (FaceUtil.isAlongX(key.facing)) {
                neighbourAxis = NEIGHBOUR_AXIS_ALONG_X;
            } else {
                neighbourAxis = NEIGHBOUR_AXIS_ALONG_Z;
            }

            // Find the most common item frame rotation in use
            // Only 4 possible rotations can be used for maps, so this is easy
            int[] rotation_counts = new int[4];
            rotation_counts[(new FindNeighboursCache.Frame(itemFrame)).rotation]++;

            // Make sure the neighbours result are a single contiguous blob
            // Islands (can not reach the input item frame) are removed
            Set<IntVector3> result = new HashSet<IntVector3>(cache.cache.size());
            result.add(itemFramePosition);
            cache.pendingList.add(itemFramePosition);
            do {
                IntVector3 pending = cache.pendingList.poll();
                for (BlockFace side : neighbourAxis) {
                    IntVector3 sidePoint = pending.add(side);
                    FindNeighboursCache.Frame frame = cache.cache.remove(sidePoint);
                    if (frame != null) {
                        rotation_counts[frame.rotation]++;
                        cache.pendingList.add(sidePoint);
                        result.add(sidePoint);
                    }
                }
            } while (!cache.pendingList.isEmpty());

            // Find maximum rotation index
            int rotation_idx = 0;
            for (int i = 1; i < rotation_counts.length; i++) {
                if (rotation_counts[i] > rotation_counts[rotation_idx]) {
                    rotation_idx = i;
                }
            }

            // The final combined result
            ItemFrameCluster cluster = new ItemFrameCluster(key.facing, result, rotation_idx * 90);
            if (cachedClusters != null) {
                for (IntVector3 position : cluster.coordinates) {
                    cachedClusters.put(position, cluster);
                }
            }

            return cluster;
        } finally {
            // Return to cache
            this.findNeighboursCache = cache;
        }
    }

    private static final class FindNeighboursCache {
        // Stores potential multi-ItemFrame neighbours during findNeighbours() temporarily
        public final HashMap<IntVector3, Frame> cache = new HashMap<IntVector3, Frame>();
        // Stores the coordinates of the item frames whose neighbours still need to be checked during findNeighbours()
        public final Queue<IntVector3> pendingList = new ArrayDeque<IntVector3>();

        // Called before use
        public void reset() {
            cache.clear();
            pendingList.clear();
        }

        // Helper
        public void put(EntityItemFrameHandle itemFrame) {
            cache.put(itemFrame.getBlockPosition(), new Frame(itemFrame));
        }

        // Single entry
        public static final class Frame {
            public final int rotation;

            public Frame(EntityItemFrameHandle itemFrame) {
                this.rotation = itemFrame.getRotationOrdinal() & 0x3;
            }
        }
    }

    private final void deinitItemFrameListForWorldsNotIn(Collection<World> worlds) {
        Iterator<ItemFrameClusterKey> iter = this.itemFrameEntities.keySet().iterator();
        while (iter.hasNext()) {
            if (!worlds.contains(iter.next().world)) {
                iter.remove();
            }
        }
    }

    private final synchronized void deinitItemFrameSetOfWorld(World world) {
        Iterator<ItemFrameClusterKey> iter = this.itemFrameEntities.keySet().iterator();
        while (iter.hasNext()) {
            if (iter.next().world == world) {
                iter.remove();
            }
        }
    }

    private final List<EntityItemFrameHandle> initItemFrameSetOfWorld(World world) {
        List<EntityItemFrameHandle> itemFrames = new ArrayList<EntityItemFrameHandle>();
        for (Object entityHandle : (Iterable<?>) WorldServerHandle.T.getEntities.raw.invoke(HandleConversion.toWorldHandle(world))) {
            if (EntityItemFrameHandle.T.isAssignableFrom(entityHandle)) {
                EntityItemFrameHandle itemFrame = EntityItemFrameHandle.createHandle(entityHandle);
                getItemFrameEntities(new ItemFrameClusterKey(itemFrame)).add(itemFrame);
                itemFrames.add(itemFrame);
            }
        }
        return itemFrames;
    }

    private final Set<EntityItemFrameHandle> getItemFrameEntities(ItemFrameClusterKey key) {
        Set<EntityItemFrameHandle> set = this.itemFrameEntities.get(key);
        if (set == null) {
            set = new HashSet<EntityItemFrameHandle>();
            this.itemFrameEntities.put(key, set);
        }
        return set;
    }

    /**
     * Gets the Item displayed in an ItemFrame. Bukkit discards NBT Metadata, which is pretty annoying.
     * Always use this method instead.
     * 
     * @param itemFrame to get the item from
     * @return item displayed, null if empty
     */
    public static ItemStack getItemFrameItem(ItemFrame itemFrame) {
        return EntityItemFrameHandle.fromBukkit(itemFrame).getItem();
    }

    /**
     * Sets the item displayed in an ItemFrame. Bukkit discards NBT Metadata, which is pretty annoying.
     * Always use this method instead.
     * 
     * @param itemFrame to set the item for
     * @param item to set
     */
    public static void setItemFrameItem(ItemFrame itemFrame, ItemStack item) {
        EntityItemFrameHandle.fromBukkit(itemFrame).setItem(item);
    }

    /**
     * Removes all NBT data for map items that is unimportant for clients to know
     * 
     * @param item
     * @return new item copy with metadata trimmed
     */
    public static ItemStack trimExtraData(ItemStack item) {
        // If null, return null. Simples.
        if (item == null) {
            return null;
        }

        // Get rid of all custom metadata from the item
        // Only Minecraft items are interesting (because its the Minecraft client)
        CommonTagCompound oldTag = ItemUtil.getMetaTag(item, false);
        CommonTagCompound newTag = new CommonTagCompound();
        final String[] nbt_filter = {
                "ench", "display", "RepairCost",
                "AttributeModifiers", "CanDestroy",
                "CanPlaceOn", "Unbreakable",

                // Also keep Map Display specific tags alive
                // This is important to prevent potential corruption
                "mapDisplayUUIDMost", "mapDisplayUUIDLeast",
                "mapDisplayPlugin", "mapDisplayClass"
        };
        for (String filter : nbt_filter) {
            if (oldTag.containsKey(filter)) {
                newTag.put(filter, oldTag.get(filter));
            }
        }

        item = ItemUtil.cloneItem(item);
        ItemUtil.setMetaTag(item, newTag);
        return item;
    }

    /**
     * Group of item frames that are connected together and face the same way
     */
    public static class ItemFrameCluster {
        // Facing of the display
        public final BlockFace facing;
        // Set of coordinates where item frames are stored
        public final Set<IntVector3> coordinates;
        // Most common ItemFrame rotation used for the display
        public final int rotation;
        // Minimum/maximum coordinates and size of the item frame coordinates in this cluster
        public final IntVector3 min_coord, max_coord;
        // Chunks that must be loaded in addition to this cluster, to make this cluster validly loaded
        public final ChunkDependency[] chunk_dependencies;
        // Resolution in rotation/facing relative space (unused)
        // public final IntVector3 size;
        // public final IntVector2 resolution;

        // A temporary builder which we use to track what chunks need to be loaded to load a cluster
        private static final ChunkDependencyBuilder BUILDER = new ChunkDependencyBuilder();

        public ItemFrameCluster(BlockFace facing, Set<IntVector3> coordinates, int rotation) {
            this.facing = facing;
            this.coordinates = coordinates;
            this.rotation = rotation;

            if (hasMultipleTiles()) {
                // Compute minimum/maximum x and z coordinates
                Iterator<IntVector3> iter = coordinates.iterator();
                IntVector3 coord = iter.next();
                int min_x, max_x, min_y, max_y, min_z, max_z;
                min_x = max_x = coord.x; min_y = max_y = coord.y; min_z = max_z = coord.z;
                while (iter.hasNext()) {
                    coord = iter.next();
                    if (coord.x < min_x) min_x = coord.x;
                    if (coord.y < min_y) min_y = coord.y;
                    if (coord.z < min_z) min_z = coord.z;
                    if (coord.x > max_x) max_x = coord.x;
                    if (coord.y > max_y) max_y = coord.y;
                    if (coord.z > max_z) max_z = coord.z;
                }
                min_coord = new IntVector3(min_x, min_y, min_z);
                max_coord = new IntVector3(max_x, max_y, max_z);
            } else {
                min_coord = max_coord = coordinates.iterator().next();
            }

            synchronized (BUILDER) {
                try {
                    this.chunk_dependencies = BUILDER.process(facing, coordinates);
                } finally {
                    BUILDER.reset();
                }
            }

            // Compute resolution (unused)
            /*
            if (hasMultipleTiles()) {
                size = max_coord.subtract(min_coord);
                if (facing.getModY() > 0) {
                    // Vertical pointing up
                    // We use rotation of the item frame to decide which side is up
                    switch (rotation) {
                    case 90:
                    case 270:
                        resolution = new IntVector2(size.z+1, size.x+1);
                        break;
                    case 180:
                    default:
                        resolution = new IntVector2(size.x+1, size.z+1);
                        break;
                    }
                } else if (facing.getModY() < 0) {
                    // Vertical pointing down
                    // We use rotation of the item frame to decide which side is up
                    switch (rotation) {
                    case 90:
                    case 270:
                        resolution = new IntVector2(size.z+1, size.x+1);
                        break;
                    case 180:
                    default:
                        resolution = new IntVector2(size.x+1, size.z+1);
                        break;
                    }
                } else {
                    // On the wall
                    switch (facing) {
                    case NORTH:
                    case SOUTH:
                        resolution = new IntVector2(size.x+1, size.y+1);
                        break;
                    case EAST:
                    case WEST:
                        resolution = new IntVector2(size.z+1, size.y+1);
                        break;
                    default:
                        resolution = new IntVector2(1, 1);
                        break;
                    }
                }
            } else {
                resolution = new IntVector2(1, 1);
                size = IntVector3.ZERO;
            }
            */
        }

        public boolean hasMultipleTiles() {
            return coordinates.size() > 1;
        }

        private static final class ChunkDependencyBuilder {
            private final LongHashSet covered = new LongHashSet();
            private final List<ChunkDependency> dependencies = new ArrayList<ChunkDependency>();

            public ChunkDependency[] process(BlockFace facing, Collection<IntVector3> coordinates) {
                // Add all chunks definitely covered by item frames, and therefore loaded
                // These are excluded as dependencies
                for (IntVector3 coordinate : coordinates) {
                    covered.add(coordinate.getChunkX(), coordinate.getChunkZ());
                }

                // Go by all coordinates and if they sit at a chunk border, check that chunk is loaded
                // If it is not, add it as a dependency
                if (!FaceUtil.isAlongX(facing)) {
                    for (IntVector3 coordinate : coordinates) {
                        if ((coordinate.x & 0xF) == 0x0) {
                            probe(coordinate.getChunkX(), coordinate.getChunkZ(), -1, 0);
                        } else if ((coordinate.x & 0xF) == 0xF) {
                            probe(coordinate.getChunkX(), coordinate.getChunkZ(), 1, 0);
                        }
                    }
                }
                if (!FaceUtil.isAlongZ(facing)) {
                    for (IntVector3 coordinate : coordinates) {
                        if ((coordinate.z & 0xF) == 0x0) {
                            probe(coordinate.getChunkX(), coordinate.getChunkZ(), 0, -1);
                        } else if ((coordinate.z & 0xF) == 0xF) {
                            probe(coordinate.getChunkX(), coordinate.getChunkZ(), 0, 1);
                        }
                    }
                }

                // To array
                return dependencies.toArray(new ChunkDependency[dependencies.size()]);
            }

            public void probe(int cx, int cz, int dx, int dz) {
                int n_cx = cx + dx;
                int n_cz = cz + dz;
                if (covered.add(n_cx, n_cz)) {
                    dependencies.add(new ChunkDependency(cx, cz, n_cx, n_cz));
                }
            }

            public void reset() {
                covered.clear();
                dependencies.clear();
            }
        }

        /**
         * A chunk neighbouring this cluster that must be loaded before
         * this cluster of item frames becomes active.
         * Tracks the chunk that needs to be loaded, as well as the
         * chunk the item frame is in that needs this neighbour.
         */
        public static final class ChunkDependency {
            public static final ChunkDependency NONE = new ChunkDependency();
            public final IntVector2 self;
            public final IntVector2 neighbour;

            private ChunkDependency() {
                this.self = null;
                this.neighbour = null;
            }

            public ChunkDependency(int cx, int cz, int n_cx, int n_cz) {
                this.self = new IntVector2(cx, cz);
                this.neighbour = new IntVector2(n_cx, n_cz);
            }
        }
    }

    /**
     * When clustering item frames (finding neighbours), this key is used
     * to store a mapping of what item frames exist on the server
     */
    private static class ItemFrameClusterKey {
        public final World world;
        public final BlockFace facing;
        public final int coordinate;

        public ItemFrameClusterKey(EntityItemFrameHandle itemFrame) {
            this(itemFrame.getBukkitWorld(), itemFrame.getFacing(), itemFrame.getBlockPosition());
        }

        public ItemFrameClusterKey(World world, BlockFace facing, IntVector3 coordinates) {
            this.world = world;
            this.facing = facing;
            this.coordinate = facing.getModX()*coordinates.x +
                              facing.getModY()*coordinates.y +
                              facing.getModZ()*coordinates.z;
        }

        @Override
        public int hashCode() {
            return this.coordinate + (facing.ordinal()<<6);
        }

        @Override
        public boolean equals(Object o) {
            ItemFrameClusterKey other = (ItemFrameClusterKey) o;
            return other.coordinate == this.coordinate && (other.world == this.world || other.world.equals(this.world)) && other.facing == this.facing;
        }
    }
}
