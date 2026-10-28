package com.hdmapreforged;

import static net.runelite.api.gameval.VarPlayerID.*;
import static net.runelite.api.GameState.*;
import static com.hdmapreforged.MapServer.*;
import static javax.swing.SwingUtilities.*;
import static com.hdmapreforged.MapConfig.*;

import com.google.gson.*;
import com.google.inject.Provides;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;
import javax.inject.*;
import javax.swing.Timer;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.callback.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.game.*;
import net.runelite.client.input.*;
import net.runelite.client.input.KeyListener;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.worldmap.*;
import net.runelite.client.util.*;
import okhttp3.*;

@Slf4j
@PluginDescriptor(
    name = "Reimagined World Map",
    internalName = "reimagined-world-map",
    description = "A zoomable world map with routes, teleports, dungeons and a search for monsters, shops and items",
    tags = {"map", "world", "worldmap", "teleport", "dungeon", "route", "sailing", "search"}
)
public class HdMapReforgedPlugin extends Plugin {
    /** Unlocks are read at most this often while things change; quest states this often at all. */
    static final int UNLOCK_TICKS = 5, QUEST_TICKS = 100;

    @Inject
    Client client;

    @Inject
    ClientThread clientThread;

    @Inject
    ClientToolbar clientToolbar;

    @Inject
    OkHttpClient httpClient;

    @Inject
    @Named("developerMode")
    boolean developerMode;

    @Inject
    Gson gson;

    @Inject
    ItemManager itemManager;

    @Inject
    SpriteManager spriteManager;

    @Inject
    MapConfig config;

    @Inject
    KeyManager keyManager;

    @Inject
    ConfigManager configManager;

    @Inject
    PartyMap party;

    @Inject
    WorldMapPointManager worldMapPointManager;

    /** A fresh one each start: nothing of a run before carries over. */
    @Inject
    Provider<RouteFeature> routes;
    RouteFeature route;

    ExecutorService io;
    /** One task at a time so results arrive in order. */
    ExecutorService dataIo;
    final AtomicInteger dataGeneration = new AtomicInteger();
    final AtomicBoolean repaintPending = new AtomicBoolean();
    Filepath home;
    TileCache tiles;
    /** The server's lists and pictures, kept once seen. */
    ServerCache serverCache;
    /** The lists our map server publishes: where NPCs stand, the shops, drops and items on the ground. */
    volatile WorldData world = WorldData.NONE;
    /** Its data version; null before the first download. */
    volatile String worldVersion;
    /** A data version whose search.json could not be used, said once in the log. */
    volatile String worldRefused;
    /** When the server was last asked for its versions (map and data, one small file). */
    volatile long versionsAsked;
    /** Asked again at most this often while the search, a card or a route uses the world facts. */
    static final long VERSIONS_ASK_MS = 60 * 60_000L;
    volatile long versionsAskMs = VERSIONS_ASK_MS;
    /** And at a login, unless asked this shortly before (the start asks too). */
    static final long LOGIN_ASK_MS = 5 * 60_000L;
    /** Since the login screen: the next LOGGED_IN is a login, not the end of a loading screen or a world hop. */
    volatile boolean loggedOut = true;
    MapServer server;
    MapScreen screen, fullScreen;
    BigWindow fullMap;
    IconLayer[] gameIcons = new IconLayer[0];
    GraveMarker[] graves = new GraveMarker[0];
    PluginPointsLayer[] pluginPoints = new PluginPointsLayer[0];
    /** Other plugins' world map icons as of the last tick (the manager has no getter: collected each tick), and as last seen, to repaint when they change. */
    volatile List<WorldMapPoint> mapPoints = List.of();
    int mapPointsSeen;
    final HotkeyListener mapKey = new HotkeyListener(() -> config.openMapKey()) {
        public void keyPressed(KeyEvent e) {
            // A key that types goes to the chat, an input box or an interface taking keys, not to the map.
            if (!config.openMapKey().matches(e) || !typingInGame(e)) {
                super.keyPressed(e);
            }
        }

        public void hotkeyPressed() {
            toggleFullMap();
        }
    };
    final KeyListener escape = new Escape();

    /** Escape closes the map over the game while it is open. */
    final class Escape extends KeyAdapter implements KeyListener {
        public void keyPressed(KeyEvent e) {
            var open = fullMap;
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE && open != null && open.isOpen()) {
                e.consume();
                invokeLater(open::close);
            }
        }

        /** The map may stay open over the login screen. Not the map key: it would open the map while typing a name. */
        public boolean isEnabledOnLoginScreen() {
            return true;
        }
    }
    MapPanel panel;
    MapWindow window;
    NavigationButton button;
    WorldPoint lastPlayer;
    boolean atHome;
    /** Null until a version's maps and places are shown. */
    volatile String currentVersion;
    /** A map version whose files are on the way or read, not asked for again meanwhile. Data thread. */
    String loadingVersion;
    /** What the requirements of the data shown read; null before it loads. */
    volatile Unlocks.Watched watched;
    /** Each skill's real level as last seen, so only a level gained marks unlocks for a new look. Client thread. */
    final int[] realLevels = new int[Skill.values().length];
    /** Before a map version's files, or the server's versions, are asked for again. */
    static final long RETRY_MS = 60_000, MAX_RETRY_MS = 30 * RETRY_MS;
    /** Map versions that failed in a row. Data thread. */
    int failedMaps;
    /** The latest such request waiting, stopped when the plugin stops. */
    volatile Timer retry;
    /** Counts starts and stops, so late callbacks of an earlier run do nothing. */
    final AtomicInteger life = new AtomicInteger();
    volatile String wantedVersion;
    Unlocks unlocks;
    boolean unlocksDirty = true;
    int ticks, lastUnlockTick = -UNLOCK_TICKS, lastQuestTick = -QUEST_TICKS;

    @Provides
    MapConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MapConfig.class);
    }

    /** When this throws, RuneLite calls shutDown, which undoes only what was made. */
    protected void startUp() throws Exception {
        int started = life.incrementAndGet();
        route = routes.get();
        currentVersion = null;
        loadingVersion = null;
        failedMaps = 0;
        // Once: party drops from 10K where the old default of 100K was kept (so little came through); a value one chose stays.
        if (configManager.getConfiguration(GROUP, "partyDrops10k") == null) {
            if (config.partyDropValue() == 100_000) {
                configManager.setConfiguration(GROUP, "partyDropValue", 10_000);
            }
            configManager.setConfiguration(GROUP, "partyDrops10k", true);
        }
        // Turned on at the login screen, the next login asks; turned on in the game, the next loading screen does not.
        loggedOut = client.getGameState() != LOGGED_IN;
        io = Executors.newFixedThreadPool(4, threads("Reimagined World Map tiles", Thread.NORM_PRIORITY));
        dataIo = Executors.newSingleThreadExecutor(threads("Reimagined World Map data", Thread.NORM_PRIORITY));
        home = getPluginDirectory();
        use(developerMode);
        // Not through RuneLite's shared disk cache: ours keeps the files, and version.json must never come stale.
        var http = httpClient.newBuilder().cache(null).build();
        server = new MapServer(http, gson);
        tiles = new TileCache(http, io, home.joinSegment("map-tiles"), this::tileLoaded);
        serverCache = new ServerCache(home.joinSegment("server-cache"), io);
        tiles.configure(config.diskCacheMb());

        var itemNames = new ItemNames(clientThread, itemManager);
        screen = new MapScreen(tiles, config, this::worldInUse, itemNames, this::togglePopOut);
        fullScreen = new MapScreen(tiles, config, this::worldInUse, itemNames, () -> fullMap.close());
        fullMap = new BigWindow(client, fullScreen, config.fullMapBounds(),
            bounds -> configManager.setConfiguration(GROUP, "fullMapBounds", bounds),
            key -> config.openMapKey().matches(key));
        keyManager.registerKeyListener(escape);
        keyManager.registerKeyListener(mapKey);
        panel = new MapPanel(() -> fullMap.open(), this::togglePopOut, this::showOnMap, party,
            fullScreen::placeName);
        button = NavigationButton.builder()
            .tooltip("Reimagined World Map")
            .icon(PoiIcons.icon(16, "NAVIGATION"))
            .priority(6)
            .panel(panel)
            .build();
        sidebar(config.sidebar());
        party.start(screen.view, fullScreen.view);
        // Where the house's picture is kept per account, and the thread that reads and writes it.
        route.yourHouse.houseFolder = home.joinSegment("house");
        route.yourHouse.houseIo = io;
        route.itemNames = itemNames;
        route.start(screen, fullScreen);
        route.setSidebar(panel::showRoute, this::showOnMap);
        panel.setTours(route.sidebarTours(panel::redoTours));
        gameIcons = new IconLayer[]{gameIconLayer(screen), gameIconLayer(fullScreen)};
        graves = new GraveMarker[]{new GraveMarker(screen.view), new GraveMarker(fullScreen.view)};
        mapPoints = PluginPointsLayer.points(worldMapPointManager);
        pluginPoints = new PluginPointsLayer[]{new PluginPointsLayer(screen.view, () -> mapPoints, config::pluginIcons),
            new PluginPointsLayer(fullScreen.view, () -> mapPoints, config::pluginIcons)};
        clientThread.invoke(this::checkGrave);
        var pictures = new NpcImages((id, done) -> {
            String version = tiles.version;
            serverCache.bytes("npcs", version, id + ".png", fetch -> server.npcPictureBytes(version, id, fetch),
                bytes -> done.accept(decode(bytes)));
        });
        for (var on : new MapScreen[]{screen, fullScreen}) {
            on.view.linesChoice = value -> configManager.setConfiguration(GROUP, "linesOff", value);
            on.icons.set = (key, value) -> configManager.setConfiguration(GROUP, key, value);
            on.view.checkForMap = () -> runData(this::checkForMap);
            on.setNpcPictures(pictures);
        }

        versionsAsked = System.currentTimeMillis();
        // In developer mode, against a map server on this computer, every 30 seconds.
        versionsAskMs = developerMode ? 30_000L : VERSIONS_ASK_MS;
        // The screens start empty until a map version's files are had and read on the data thread.
        runData(() -> resolveVersions(false));
        // The game's pictures of teleports and runecrafting altars: an item's, or a sprite's (~id).
        PoiIcons.load = (id, done) -> {
            if (id > 0) {
                var image = itemManager.getImage(id);
                image.onLoaded(() -> done.accept(image));
            } else {
                spriteManager.getSpriteAsync(~id, 0, done);
            }
        };
        PoiIcons.loaded = this::refreshLater;
        whenGameLoaded(started, () -> GameIcons.load(client), PoiIcons::clearCache);
    }

    void whenGameLoaded(int started, BooleanSupplier load, Runnable after) {
        clientThread.invokeLater(() -> {
            if (started != life.get()) {
                return true;
            }
            if (client.getGameState().getState() < LOGIN_SCREEN.getState() || !load.getAsBoolean()) {
                return false;
            }
            after.run();
            refreshLater();
            return true;
        });
    }

    IconLayer gameIconLayer(MapScreen on) {
        var layer = new IconLayer(on.view, config::iconSize);
        on.view.addOverlay(layer);
        on.view.hitLayers.add(layer);
        on.view.badgeCover = layer::covers;
        return layer;
    }

    /** Daemon threads called {@code name} at {@code priority}, for the plugin's own executors. */
    static ThreadFactory threads(String name, int priority) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        };
    }

    /** Also safe after a startUp that failed half-way: only what was made is undone. */
    protected void shutDown() {
        life.incrementAndGet();
        wantedVersion = null;
        retry(null);
        GameIcons.clear();
        PoiIcons.clearCache();
        PoiIcons.load = (id, done) -> { };
        PoiIcons.loaded = () -> { };
        party.stop();
        route.stop();
        if (button != null) {
            clientToolbar.removeNavigation(button);
        }
        dock();
        keyManager.unregisterKeyListener(escape);
        keyManager.unregisterKeyListener(mapKey);
        if (fullMap != null) {
            fullMap.dispose();
        }
        dataGeneration.incrementAndGet();
        if (screen != null) {
            screen.stop();
        }
        if (fullScreen != null) {
            fullScreen.stop();
        }
        if (io != null) {
            io.shutdownNow();
        }
        if (dataIo != null) {
            dataIo.shutdownNow();
        }
        if (tiles != null) {
            tiles.clear();
        }
        lastPlayer = null;
        unlocks = null;
        unlocksDirty = true;
        watched = null;
        Arrays.fill(realLevels, 0);
        // Nothing of the data is kept while the plugin is off: the files are on disk for the next start.
        world = WorldData.NONE;
        worldVersion = null;
        currentVersion = null;
        RouteFiles.clear();
        screen = null;
        gameIcons = new IconLayer[0];
        graves = new GraveMarker[0];
        fullScreen = null;
        fullMap = null;
        if (panel != null) {
            panel.stop();
        }
        panel = null;
        button = null;
    }

    void screens(Consumer<MapScreen> change) {
        invokeLater(() -> {
            if (screen != null) {
                change.accept(screen);
                change.accept(fullScreen);
            }
        });
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        ticks++;
        Player player = client.getLocalPlayer();
        if (player == null) {
            return;
        }
        var location = playerLocation(player);
        route.tick(location);
        var home = route.home();
        var outside = route.outside();
        // On the house's plan, or in a box at its portal while that plan is not known (a friend's house).
        boolean boxed = home != null && !HousePlan.contains(home.getX(), home.getY());
        // In a house the party sees its portal (the house's own place is a copy of the game's rooms).
        party.tick(home == null ? location : boxed ? home : outside, home != null, ticks);
        var shown = home != null ? home : location;
        if (shown != null && (!shown.equals(lastPlayer) || boxed != atHome)) {
            lastPlayer = shown;
            atHome = boxed;
            screens(s -> {
                s.view.home = boxed;
                s.view.outside = outside;
                s.view.setPlayer(shown);
            });
        }
        updateUnlocks();
        mapPoints = PluginPointsLayer.points(worldMapPointManager);
        int points = PluginPointsLayer.signature(mapPoints);
        if (points != mapPointsSeen) {
            mapPointsSeen = points;
            refreshLater();
        }
    }

    /** As the game's maps show it: in instances the copied real location, on a boat the boat's main-world place. */
    WorldPoint playerLocation(Player player) {
        var local = player.getLocalLocation();
        var view = player.getWorldView();
        if (local != null && view != null && !view.isTopLevel()) {
            var boat = client.getTopLevelWorldView().worldEntities().byIndex(view.getId());
            local = boat != null ? boat.transformToMainWorld(local) : null;
        }
        if (local == null) {
            return null;
        }
        var at = WorldPoint.fromLocalInstance(client, local);
        // An instance copying a place on the map (the Watchtower's top floor): that place's tile.
        var place = InstanceCopies.place(at.getX(), at.getY(), at.getPlane());
        return place == null ? at : new WorldPoint(place[0], place[1], place[2]);
    }

    void updateUnlocks() {
        boolean due = unlocksDirty && ticks - lastUnlockTick >= UNLOCK_TICKS || ticks - lastUnlockTick >= QUEST_TICKS;
        var reads = watched;
        if (!due || reads == null || client.getGameState() != LOGGED_IN) {
            return;
        }
        boolean quests = ticks - lastQuestTick >= QUEST_TICKS;
        var previous = unlocks;
        unlocks = Unlocks.capture(client, reads, unlocks, quests);
        unlocksDirty = false;
        lastUnlockTick = ticks;
        if (quests) {
            lastQuestTick = ticks;
        }
        if (unlocks.sameAs(previous)) {
            // Varbits change all the time; rebuilding the detail card for nothing made the map stutter.
            unlocks = previous;
            return;
        }
        var current = unlocks;
        screens(s -> s.setUnlocks(current));
    }

    static boolean isWorldMapOption(String option) {
        return option != null && Text.removeTags(option).toLowerCase(Locale.ROOT).contains("world map");
    }

    /** A RuneLite-only orb entry; the game's "World Map" stays in the menu. Every entry of every frame: cheap tests first. */
    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        int widget = event.getActionParam1();
        if (!isOrb(widget) || !config.orbOpensMap() || !isWorldMapOption(event.getOption())) {
            return;
        }
        ourEntryAdded = true;
        // The orb can list several map options; one entry of ours is enough.
        if (ownEntryIndex(client.getMenu().getMenuEntries()) >= 0) {
            return;
        }
        client.getMenu().createMenuEntry(-1)
            .setOption(MENU_OPTION)
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .setParam1(widget)
            .onClick(entry -> toggleFullMap());
    }

    /** Our orb entry is in this frame's menu: the sort that follows has something to do. Client thread. */
    boolean ourEntryAdded;

    /**
     * Whether the game takes typed keys as text now: an input box is open (an amount, a bank search, the world map's
     * search, the settings' search: its key handler comes with its varbit, whatever mode the server opens it in) or the
     * chat takes letters (with Key Remapping's "Press Enter to Chat" shown it takes none), also while the chat is
     * closed. The game's own chat key script ignores keys when a dialogue hides the chat's lines. Set on the client
     * thread each client tick; the key thread only reads it.
     */
    volatile boolean gameTakesText;
    /**
     * Whether an open interface takes keys itself (the jewellery box's letters, a menu's numbers): looked at on the two
     * client ticks after one opens or closes, and every fifth tick besides, as its scripts may set its key handler
     * later than it loads. On those two ticks the game gets typed keys, so a key pressed at once never opens the map
     * past the interface.
     */
    volatile boolean takesKeys;
    volatile int interfacesChanged = 2;
    int clientTicks;

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        interfacesChanged = 2;
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        interfacesChanged = 2;
    }

    @Subscribe
    public void onClientTick(ClientTick tick) {
        boolean text = false;
        if (client.getGameState() == LOGGED_IN) {
            boolean changed = interfacesChanged > 0;
            if (changed) {
                interfacesChanged--;
            }
            if (changed || ++clientTicks % 5 == 0) {
                takesKeys = modalTakesKeys();
            }
            var chat = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
            var input = client.getWidget(InterfaceID.Chatbox.INPUT);
            text = client.getFocusedInputFieldWidget() != null || client.getVarcIntValue(VarClientID.MESLAYERMODE) != 0
                || client.getVarcIntValue(VarClientID.WORLDMAP_SEARCHING) == 1
                || client.getVarbitValue(VarbitID.FLOATER_IS_SEARCHING) != 0
                && client.getWidget(InterfaceID.Settings.UNIVERSE) != null
                || chat != null && chat.getOnKeyListener() != null && input != null && shown(InterfaceID.Chatbox.CHATDISPLAY)
                && shown(InterfaceID.Chatbox.MES_LAYER_HIDE) && client.getWidget(InterfaceID.Reportabuse.UNIVERSE) == null
                && !String.valueOf(input.getText()).contains("Press Enter to Chat");
        }
        gameTakesText = text;
    }

    /** Whether a modal interface open now (not the chat, the side panels or the game view) has a key handler. */
    boolean modalTakesKeys() {
        for (WidgetNode node : client.getComponentTable()) {
            if (node.getModalMode() == WidgetModalMode.NON_MODAL) {
                continue;
            }
            Widget w;
            for (int child = 0; child < 2000 && (w = client.getWidget(node.getId(), child)) != null; child++) {
                if (w.getOnKeyListener() != null && !w.isHidden()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Not hidden by itself; a closed chat hides only its parents. */
    boolean shown(int component) {
        var w = client.getWidget(component);
        return w != null && !w.isSelfHidden();
    }

    /**
     * Whether the game takes this key: it types a character (no Ctrl, Alt or Meta) while the game takes text or an open
     * interface takes keys.
     */
    boolean typingInGame(KeyEvent e) {
        return e.getKeyChar() != KeyEvent.CHAR_UNDEFINED && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()
            && (gameTakesText || takesKeys || interfacesChanged > 0);
    }

    void toggleFullMap() {
        invokeLater(() -> {
            if (fullMap != null) {
                fullMap.toggle();
            }
        });
    }

    /** Keeps "Reimagined Map" as the orb's left-click option, as later entries would take the top; only reorders. */
    @Subscribe
    public void onPostMenuSort(PostMenuSort event) {
        if (!ourEntryAdded || client.isMenuOpen()) {
            return;
        }
        ourEntryAdded = false;
        var entries = client.getMenu().getMenuEntries();
        int ours = ownEntryIndex(entries);
        if (ours >= 0 && ours != entries.length - 1) {
            client.getMenu().setMenuEntries(moveToTop(entries, ours));
        }
    }

    static final String MENU_OPTION = "Reimagined Map";

    static boolean isOrb(int widget) {
        return widget == InterfaceID.Orbs.ORB_WORLDMAP || widget == InterfaceID.Orbs.WORLDMAP;
    }

    static int ownEntryIndex(MenuEntry[] entries) {
        for (int i = 0; i < entries.length; i++) {
            var entry = entries[i];
            if (entry.getType() == MenuAction.RUNELITE && MENU_OPTION.equals(entry.getOption())
                && isOrb(entry.getParam1())) {
                return i;
            }
        }
        return -1;
    }

    /** The last entry is the top of the menu, which is what a left click does. */
    static MenuEntry[] moveToTop(MenuEntry[] entries, int index) {
        var reordered = new ArrayList<>(Arrays.asList(entries));
        reordered.add(reordered.remove(index));
        return reordered.toArray(new MenuEntry[0]);
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        int skill = event.getSkill().ordinal();
        if (realLevels[skill] != event.getLevel()) {
            realLevels[skill] = event.getLevel();
            unlocksDirty = true;
        }
    }

    /** Varbits change all the time; only those (and varps) a requirement reads matter, and the teleport timers. */
    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        var reads = watched;
        if (reads != null && (reads.watches(event.getVarbitId()) || reads.watchesVarp(event.getVarpId()))
            || event.getVarpId() == AIDE_TELE_TIMER || event.getVarpId() == SLUG2_REGIONUID) {
            unlocksDirty = true;
        }
        if (event.getVarbitId() == VarbitID.GRAVESTONE_VISIBLE
            || event.getVarpId() == GRAVESTONE_CREATION_DEATH_COORD) {
            checkGrave();
        }
    }

    /** One's grave from the game: standing while GRAVESTONE_VISIBLE is not 0 (not a flag), on the tile one died on. */
    void checkGrave() {
        int coord = client.getVarpValue(GRAVESTONE_CREATION_DEATH_COORD);
        showGrave(client.getVarbitValue(VarbitID.GRAVESTONE_VISIBLE) != 0 && coord > 0 ? WorldPoint.fromCoord(coord)
            : null);
    }

    void showGrave(WorldPoint at) {
        invokeLater(() -> {
            for (var grave : graves) {
                grave.set(at);
            }
        });
    }

    /** HD World Markers drops every plugin's tiles on a profile change, as on a logout: the route's are sent again. */
    @Subscribe
    public void onProfileChanged(ProfileChanged event) {
        route.tileMarkersCleared();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState game = event.getGameState();
        if (game == LOGGED_IN) {
            unlocksDirty = true;
            lastQuestTick = ticks - QUEST_TICKS;
            if (loggedOut) {
                // A game update's new map is taken at the next login.
                loggedOut = false;
                askVersions(LOGIN_ASK_MS);
            }
        }
        if (game == LOGIN_SCREEN || game == HOPPING || game == CONNECTION_LOST) {
            route.tileMarkersCleared();
        }
        if (game == LOGIN_SCREEN) {
            loggedOut = true;
            lastPlayer = null;
            unlocks = null;
            showGrave(null);
            screens(s -> {
                s.view.setPlayer(null);
                s.setUnlocks(null);
            });
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        // The house (per account) is written as it is seen: nothing for the map's tiles.
        if (!GROUP.equals(event.getGroup())
            || Set.of("fullMapBounds", RouteFeature.HOUSE_KEY, RouteFeature.PLAN_KEY, RouteFeature.BANK_KEY)
            .contains(event.getKey())) {
            return;
        }
        tiles.configure(config.diskCacheMb());
        switch (event.getKey()) {
            case "checkMapNow":
            case "downloadMapAgain":
                // Buttons as boxes: acted on when ticked, then unticked, so no profile keeps one ticked (a profile
                // switch posts every key that differs) and an untick does nothing.
                if ("true".equals(event.getNewValue())) {
                    configManager.setConfiguration(GROUP, event.getKey(), false);
                    if (event.getKey().equals("checkMapNow")) {
                        runData(this::checkForMap);
                    } else {
                        tiles.downloadAgain();
                    }
                }
                break;
            case "followPlayer":
                screens(s -> s.view.setFollowing(config.followPlayer()));
                break;
            case "linesOff":
                screens(s -> s.view.setLinesOff(config.linesOff()));
                return;
            case "iconsOff":
                screens(s -> s.view.setIconsOff(config.iconsOff()));
                return;
            case "sidebar":
                sidebar(config.sidebar());
                return;
        }
        refreshLater();
    }

    /** The panel's button in RuneLite's sidebar (setting). */
    void sidebar(boolean shown) {
        if (shown) {
            clientToolbar.addNavigation(button);
        } else {
            clientToolbar.removeNavigation(button);
        }
    }

    void tileLoaded() {
        if (repaintPending.compareAndSet(false, true)) {
            invokeLater(() -> {
                repaintPending.set(false);
                refresh();
            });
        }
    }

    void refreshLater() {
        invokeLater(this::refresh);
    }

    void refresh() {
        if (screen != null) {
            screen.view.repaint();
            fullScreen.view.repaint();
        }
    }

    /**
     * The map server's current map version and the world facts' data version, from one small answer; at the start the
     * remembered map version shows meanwhile (nothing is shown before a version's files are had). {@code now}: while
     * running, so the remembered one is not taken again (it may be the broken one). Data thread.
     */
    void resolveVersions(boolean now) {
        var known = ServerCache.known(home.joinSegment(ServerCache.KNOWN));
        if (known != null && !now) {
            useVersion(known);
        }
        int started = life.get();
        server.versions(versions -> runData(() -> {
            loadWorld(started, versions.data);
            if (started != life.get()) {
                return;
            }
            if (versions.map == null) {
                if (wantedVersion == null) {
                    // The first start, and no answer: nothing to show until the server answers.
                    retryLater(started, RETRY_MS, () -> resolveVersions(false));
                } else if (failedMaps > 0) {
                    // A failed version's retry, and no answer: asked again, further apart.
                    retryMap(started, wantedVersion);
                } else {
                    // A start on the version remembered, and no answer: asked again (a newer version, the search's
                    // data), not only an hour on.
                    retryLater(started, RETRY_MS, () -> resolveVersions(true));
                }
                if (wantedVersion != null && currentVersion == null) {
                    screens(s -> s.view.setMapUnavailable(true));
                }
                return;
            }
            useVersion(versions.map);
        }));
    }

    /**
     * The world facts, for what shows them (the search, a card, a route): asks the server for newer versions when
     * {@link #versionsAskMs} have passed since it last did, so nothing is fetched while nothing uses them.
     */
    WorldData worldInUse() {
        askVersions(versionsAskMs);
        return world;
    }

    /** Asks the server for its versions unless it did within {@code gapMs}; a newer map or data version is taken. */
    void askVersions(long gapMs) {
        long now = System.currentTimeMillis();
        if (now - versionsAsked >= gapMs) {
            versionsAsked = now;
            runData(() -> resolveVersions(true));
        }
    }

    /**
     * The map server's search.json of {@code version} (what the search shows, worked out there), when newer than the
     * one shown; read on the data thread. Without it, the next look ({@link #worldInUse}) asks again a minute later;
     * one in another format (an older or newer map server) is not used. Either is said once in the log.
     */
    void loadWorld(int started, String version) {
        if (version == null || started != life.get() || version.equals(worldVersion)) {
            return;
        }
        String file = WorldData.FILE + ".json";
        serverCache.bytes("data", version, file, fetch -> server.data(version, WorldData.FILE, fetch),
            bytes -> runData(() -> {
                var read = bytes == null ? null : WorldData.parse(gson, bytes);
                if (started != life.get()) {
                    return;
                }
                if (read != null) {
                    world = read;
                    worldVersion = version;
                    serverCache.prune("data", version);
                    return;
                }
                if (!version.equals(worldRefused)) {
                    worldRefused = version;
                    log.warn(bytes == null ? "No {} of data version {}" : "{} of data version {} is not in format "
                        + WorldData.FORMAT, file, version);
                }
                if (bytes == null) {
                    versionsAsked = System.currentTimeMillis() - versionsAskMs + RETRY_MS;
                } else {
                    worldVersion = version;
                }
            }));
    }

    /**
     * Shows a map version once its four files are had (kept on disk once seen, else the server's): its map list with
     * its region table, its places and its route tables, read here; its tiles switch with them. Until all four are
     * usable the version shown stays, and the server is asked again ({@link #retryMap}). Data thread.
     */
    void useVersion(String version) {
        int started = life.get();
        wantedVersion = version;
        if (version.equals(currentVersion)) {
            tiles.setVersion(version);
            refreshLater();
            return;
        }
        if (version.equals(loadingVersion)) {
            // A second check while its files or its tiles' changes are on the way.
            return;
        }
        loadingVersion = version;
        var files = new ArrayList<CompletableFuture<byte[]>>();
        for (String name : MAP_FILES) {
            var file = new CompletableFuture<byte[]>();
            serverCache.bytes("maps", version, name, fetch -> server.mapFile(version, name, fetch), file::complete);
            files.add(file);
        }
        CompletableFuture.allOf(files.toArray(new CompletableFuture<?>[0])).thenRun(() -> runData(() -> {
            if (started != life.get()) {
                return;
            }
            if (!version.equals(wantedVersion) || version.equals(currentVersion)) {
                loaded(version);
                return;
            }
            // The map list, its region table, the places and the route tables.
            var got = files.stream().map(CompletableFuture::join).toArray(byte[][]::new);
            boolean all = Arrays.stream(got).allMatch(Objects::nonNull);
            var maps = got[0] == null ? null : BaseMaps.parse(gson, MapServer.text(got[0]));
            Map<String, byte[]> routeFiles = null;
            MapData data = null;
            if (all && maps != null && maps.byId(BaseMap.SURFACE) != null) {
                try {
                    var unzipped = RouteFiles.unzip(got[3]);
                    // The planner's passages: a route.zip made before them would plan with no stairs or caves.
                    Objects.requireNonNull(unzipped.get("passages.tsv"));
                    maps.regions = Objects.requireNonNull(gson.fromJson(MapServer.text(got[1]), RegionTable.class));
                    maps.version = version;
                    // Its icons are drawn where its world map moves put them: its tables for this thread's read alone,
                    // the version shown keeps its own for the planner and the screens until this one is shown.
                    RouteFiles.READING.set(unzipped);
                    try {
                        data = MapData.read(gson, MapServer.text(got[2]), maps, version);
                    } finally {
                        RouteFiles.READING.remove();
                    }
                    routeFiles = unzipped;
                } catch (IOException | RuntimeException e) {
                }
            }
            if (data == null) {
                if (all) {
                    // Complete but unusable: the kept copies are passed over once, or every retry would read them.
                    MAP_FILES.forEach(name -> serverCache.stale.add("maps/" + version + "/" + name));
                }
                if (currentVersion == null) {
                    screens(s -> s.view.setMapUnavailable(true));
                }
                loaded(version);
                retryMap(started, version);
                return;
            }
            switchVersion(version, maps, data, routeFiles, started);
        }));
    }

    /** The load of {@code version} ended: shown, unusable or passed over. Data thread. */
    void loaded(String version) {
        if (version.equals(loadingVersion)) {
            loadingVersion = null;
        }
    }

    /**
     * A player's "Check for a new map": asks the server now. After failures the backoff starts over, so a click while
     * the server is down does not push the next retry further away. Data thread.
     */
    void checkForMap() {
        if (failedMaps > 0) {
            failedMaps = 1;
        }
        resolveVersions(true);
    }

    /**
     * Asks the server for its versions after an unusable {@code version}, as it may have replaced it: at once, then
     * 1, 2, 4... minutes apart, up to {@link #MAX_RETRY_MS}. Data thread.
     */
    void retryMap(int started, String version) {
        long gap = failedMaps == 0 ? 0 : Math.min(MAX_RETRY_MS, RETRY_MS << Math.min(failedMaps - 1, 5));
        failedMaps++;
        retryLater(started, gap, () -> {
            if (version.equals(wantedVersion)) {
                resolveVersions(true);
            }
        });
    }

    /** Runs {@code task} on the data thread after {@code delayMs} unless the plugin stopped meanwhile. */
    void retryLater(int started, long delayMs, Runnable task) {
        Timer timer = new Timer((int) delayMs, e -> runData(() -> {
            if (started == life.get()) {
                task.run();
            }
        }));
        timer.setRepeats(false);
        retry(timer);
        timer.start();
    }

    /** The waiting request becomes {@code next}; the one before is stopped. */
    void retry(Timer next) {
        Timer previous = retry;
        retry = next;
        if (previous != null) {
            previous.stop();
        }
    }

    void switchVersion(String version, BaseMaps maps, MapData data, Map<String, byte[]> routeFiles, int started) {
        var cache = tiles;
        String current = cache.version;
        if (current != null && !current.equals(version)) {
            // The tiles of the version in use carry over, but for those the server says changed.
            // On the data thread: the upgrade may remove many files, too long for OkHttp's shared callback threads.
            server.changes(version, current, changes -> runData(() -> {
                // A later run has its own tiles, and another version wanted keeps the folder shown.
                if (started != life.get()) {
                    return;
                }
                if (!version.equals(wantedVersion)) {
                    loaded(version);
                    return;
                }
                if (changes != null && changes.from.equals(current)) {
                    cache.upgrade(current, version, changes.changed, changes.removed);
                }
                publish(version, maps, data, routeFiles, started);
            }));
            return;
        }
        publish(version, maps, data, routeFiles, started);
    }

    /**
     * A version's tiles, maps, places and route tables to the screens, the planner and the requirements read; only now
     * is it remembered for the next start and are the other versions' kept files removed. Data thread.
     */
    void publish(String version, BaseMaps maps, MapData data, Map<String, byte[]> routeFiles, int started) {
        loaded(version);
        // Before the needs below: its gates and shortcuts are among them.
        RouteFiles.use(routeFiles);
        tiles.setVersion(version);
        int generation = dataGeneration.incrementAndGet();
        currentVersion = version;
        failedMaps = 0;
        ServerCache.remember(home.joinSegment(ServerCache.KNOWN), version);
        serverCache.prune("maps", version);
        serverCache.prune("npcs", version);
        screens(s -> s.view.setMapUnavailable(false));
        // The places' needs and those of the bundled gates and shortcuts: a quest not watched counts as done.
        var reads = new Unlocks.Watched(Stream.concat(Poi.flatten(data.pois).stream().flatMap(poi -> Stream.concat(
            poi.members().stream().map(member -> member.needs), poi.links().stream().map(link -> link.needs))),
            RouteSource.fixedNeeds().stream()).collect(Collectors.toList()));
        // Quest start icons show their quest's state.
        data.icons.stream().filter(icon -> icon.poi.type == PoiType.QUEST_START)
            .forEach(icon -> reads.add(icon.poi.name));
        watched = reads;
        route.setIcons(data.icons);
        clientThread.invokeLater(() -> unlocksDirty = true);
        var elements = data.icons.stream().filter(icon -> icon.element >= 0).map(icon -> icon.element)
            .collect(Collectors.toCollection(HashSet::new));
        whenGameLoaded(started, () -> GameIcons.loadElements(client, elements), () -> { });
        invokeLater(() -> {
            if (screen != null && generation == dataGeneration.get()) {
                // The game's icons with the data they belong to: a found shop is selected among them, a found passage
                // shown by the game's icon for that way in.
                for (var layer : gameIcons) {
                    layer.setIcons(data.icons);
                }
                screen.setData(maps, data);
                fullScreen.setData(maps, data);
            }
        });
        refreshLater();
        route.loadPlanner();
    }

    void runData(Runnable task) {
        TileCache.execute(dataIo, task);
    }

    void togglePopOut() {
        if (window != null) {
            dock();
            return;
        }
        window = new MapWindow(screen, this::dock);
        window.setVisible(true);
    }

    void dock() {
        var closing = window;
        window = null;
        if (closing != null) {
            closing.dispose();
        }
    }

    void showOnMap(WorldPoint point) {
        if (fullMap == null) {
            return;
        }
        fullMap.open();
        fullScreen.view.focus(point);
    }
}
