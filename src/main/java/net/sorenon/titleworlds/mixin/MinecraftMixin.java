package net.sorenon.titleworlds.mixin;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.ProcessorChunkProgressListener;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.storage.*;
import net.sorenon.titleworlds.TitleWorldsMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import oshi.util.tuples.Triplet;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin extends ReentrantBlockableEventLoop<Runnable> {

    @Shadow
    @Nullable
    public ClientLevel level;

    public MinecraftMixin(String string) {
        super(string);
    }

    @Shadow
    public abstract void setScreen(@Nullable Screen guiScreen);

    @Shadow
    @Final
    private AtomicReference<StoringChunkProgressListener> progressListener;

    @Shadow
    @Final
    private Proxy proxy;

    @Shadow
    @Final
    public File gameDirectory;

    @Shadow
    private @Nullable IntegratedServer singleplayerServer;

    @Shadow
    private boolean isLocalServer;

    @Shadow
    @Final
    private Queue<Runnable> progressTasks;

    @Shadow
    protected abstract void runTick(boolean renderLevel);

    @Shadow
    private @Nullable Connection pendingConnection;

    @Shadow
    public abstract User getUser();

    @Shadow
    @Nullable
    public Screen screen;

    @Shadow
    private volatile boolean running;

    @Shadow public abstract ProfileKeyPairManager getProfileKeyPairManager();

    @Shadow
    public static Minecraft getInstance() {
        return null;
    }

    @Unique
    private boolean closingLevel;

    @Unique
    private static final Logger LOGGER = LogManager.getLogger("Title World Loader");

    /**
     * Called when joining / leaving a server
     */
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    void preClearLevel(Screen screen, CallbackInfo ci) {
        if (TitleWorldsMod.state.isTitleWorld) {
            if (activeLoadingFuture != null) {
                while (!activeLoadingFuture.isDone()) {
                    this.runAllTasks();
                    this.runTick(false);
                }
                activeLoadingFuture = null;
            }
            if (singleplayerServer != null) {
                // Ensure the server has initialized so we don't orphan it
                while (!this.singleplayerServer.isReady()) {
                    this.runAllTasks();
                    this.runTick(false);
                }
                if (this.pendingConnection != null || this.level != null) {
                    // Wait for connection to establish so it can be killed cleanly on this.level.disconnect();
                    while (this.pendingConnection != null) {
                        this.runAllTasks();
                        this.runTick(false);
                    }
                }
                this.singleplayerServer.halt(false);
            }
        } else {
            this.closingLevel = this.level != null;
        }

        if (this.level != null) {
            this.level.disconnect();
        }
    }

    /**
     * Called when joining / leaving a server
     */
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("RETURN"))
    void postClearLevel(Screen screen, CallbackInfo ci) {
        if (TitleWorldsMod.state.isTitleWorld) {
            TitleWorldsMod.LOGGER.info("Closing Title World");
            TitleWorldsMod.state.isTitleWorld = false;
            TitleWorldsMod.state.pause = false;
        } else if (this.closingLevel && this.running) {
            TitleWorldsMod.LOGGER.info("Loading Title World");
            tryLoadTitleWorld();
        }
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    void setScreen(Screen guiScreen, CallbackInfo ci) {
        if (TitleWorldsMod.state.isTitleWorld) {
            if (this.screen instanceof TitleScreen && guiScreen instanceof TitleScreen) {
                ci.cancel();
            } else if (guiScreen == null) {
                setScreen(new TitleScreen());
                ci.cancel();
            } else if (guiScreen instanceof ProgressScreen || guiScreen instanceof ReceivingLevelScreen) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    void init(GameConfig gameConfig, CallbackInfo ci) {
        tryLoadTitleWorld();
    }

    @Unique
    private static final Random random = new Random();

    @SuppressWarnings("UnusedReturnValue")
    @Unique
    public boolean tryLoadTitleWorld() {
        try {
            List<LevelSummary> list = TitleWorldsMod.levelSource.loadLevelSummaries(TitleWorldsMod.levelSource.findLevelCandidates()).get();
            if (list.isEmpty()) {
                LOGGER.info("TitleWorlds folder is empty");
                return false;
            }

            var worldResourcesFuture
                    = CompletableFuture.supplyAsync(() -> loadWorld(list));
            activeLoadingFuture = worldResourcesFuture;
            LOGGER.info("Waiting for WorldStem to load");
            while (!worldResourcesFuture.isDone()) {
                this.runAllTasks();
                this.runTick(false);
            }
            this.loadTitleWorld(list.get(random.nextInt(list.size())).getLevelId(), worldResourcesFuture.get().getA(), worldResourcesFuture.get().getB(), worldResourcesFuture.get().getC());
            return true;
        } catch (Exception e) {
            LOGGER.error("Exception when loading title world", e);
            return false;
        }
    }

    private Triplet<LevelStorageSource.LevelStorageAccess, PackRepository, WorldStem> loadWorld(List<LevelSummary> list) {
        try {
            LevelStorageSource.LevelStorageAccess access = TitleWorldsMod.levelSource.createAccess(list.get(random.nextInt(list.size())).getLevelId());
            WorldOpenFlows flows = new WorldOpenFlows(getInstance(), TitleWorldsMod.levelSource);
            var completableFuture = flows.loadWorldStem(access, false);
            PackRepository pr = createPackRepository(access);
            return new Triplet<>(access, pr, completableFuture);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static PackRepository createPackRepository(LevelStorageSource.LevelStorageAccess levelStorageAccess) {
        return new PackRepository(PackType.SERVER_DATA, new ServerPacksSource(), new FolderRepositorySource(levelStorageAccess.getLevelPath(LevelResource.DATAPACK_DIR).toFile(), PackSource.WORLD));
    }

    @Unique
    @Nullable
    private Future<?> activeLoadingFuture = null;

    @Unique
    @Nullable
    private Runnable cleanup = null;

    @Shadow @Final private YggdrasilAuthenticationService authenticationService;
    @Shadow private ProfilerFiller profiler;
    @Shadow @Nullable private Supplier<CrashReport> delayedCrash;
    @Shadow
    public static void crash(CrashReport report) {
    }
    @Shadow @Final private ProfileKeyPairManager profileKeyPairManager;

    @Shadow public abstract void clearLevel();

    @Shadow public abstract void clearLevel(Screen screen);

    @Shadow protected abstract ItemStack addCustomNbtData(ItemStack stack, BlockEntity blockEntity);

    @Unique
    private void loadTitleWorld(String string, LevelStorageSource.LevelStorageAccess levelStorageAccess, PackRepository packRepository, WorldStem worldStem) throws ExecutionException, InterruptedException {
        LOGGER.info("Starting server");
        activeLoadingFuture = CompletableFuture.runAsync(() -> {
            TitleWorldsMod.state.isTitleWorld = true;
            TitleWorldsMod.state.pause = false;

            LOGGER.info("Loading title world");

            try {
                levelStorageAccess.saveDataTag(worldStem.registryAccess(), worldStem.worldData());
                Services services = Services.create(this.authenticationService, this.gameDirectory);
                services.profileCache().setExecutor(this);
                SkullBlockEntity.setup(services, this);
                GameProfileCache.setUsesAuthentication(false);
                this.singleplayerServer = MinecraftServer.spin((thread) -> new IntegratedServer(thread, this.getInstance(), levelStorageAccess, packRepository, worldStem, services, (i) -> {
                    StoringChunkProgressListener storingChunkProgressListener = new StoringChunkProgressListener(i + 0);
                    this.progressListener.set(storingChunkProgressListener);
                    Queue var10001 = this.progressTasks;
                    Objects.requireNonNull(var10001);
                    return ProcessorChunkProgressListener.createStarted(storingChunkProgressListener, var10001::add);
                }));
                this.isLocalServer = true;
            } catch (Throwable var9) {
                CrashReport crashReport = CrashReport.forThrowable(var9, "Starting integrated server");
                CrashReportCategory crashReportCategory = crashReport.addCategory("Starting integrated server");
                crashReportCategory.setDetail("Level ID", string);
                crashReportCategory.setDetail("Level Name", () -> worldStem.worldData().getLevelName());
                throw new ReportedException(crashReport);
            }
        });

        while (singleplayerServer == null || !this.singleplayerServer.isReady()) {
            this.runAllTasks();
            this.runTick(false);
            if (!TitleWorldsMod.state.isTitleWorld) {
                return;
            }
        }

        LOGGER.info("Joining singleplayer server");
        var joinServerFuture = CompletableFuture.runAsync(() -> {
            SocketAddress socketAddress = this.singleplayerServer.getConnection().startMemoryChannel();
            Connection connection = Connection.connectToLocalServer(socketAddress);
            connection.setListener(new ClientHandshakePacketListenerImpl(connection, this.getInstance(), (Screen)null, (component) -> {
            }));
            connection.send(new ClientIntentionPacket(socketAddress.toString(), 0, ConnectionProtocol.LOGIN));
            this.pendingConnection = connection;
            connection.send(new ServerboundHelloPacket(this.getUser().getName(), Optional.of(this.profileKeyPairManager.profilePublicKey().get().data()), Optional.empty()));
        });

        activeLoadingFuture = joinServerFuture;

        while (!joinServerFuture.isDone()) {
            this.runAllTasks();
            this.runTick(false);
            if (!TitleWorldsMod.state.isTitleWorld) {
                return;
            }
        }
        activeLoadingFuture = null;

        LOGGER.info("Logging into title world");
    }
}
