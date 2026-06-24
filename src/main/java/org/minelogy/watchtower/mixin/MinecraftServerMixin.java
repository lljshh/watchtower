package org.minelogy.watchtower.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import org.minelogy.watchtower.SecondBucket;
import org.minelogy.watchtower.TickMonitorAccess;
import org.minelogy.watchtower.TickSnapshot;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
@Implements({@Interface(iface = TickMonitorAccess.class, prefix = "tick_monitor$")})
public class MinecraftServerMixin {
    @Unique
    private final AtomicReference<TickSnapshot> snapshotRef = new AtomicReference<>(
            new TickSnapshot(20.0, 0.0, 20.0, 0.0, System.currentTimeMillis())
    );
    @Unique
    private long secondStartTime = System.nanoTime();
    @Unique
    private long secondTickCount = 0;
    @Unique
    private long secondTotalDurationNanos = 0;

    @Unique
    private final SecondBucket[] minuteBuckets = new SecondBucket[60];
    @Unique
    private int minuteBucketIndex = 0;
    @Unique
    private boolean minuteWindowFilled = false;
    @Unique
    private static final byte NOT_TICKING = 0;
    @Unique
    private static final byte INITIALIZING = 1;
    @Unique
    private static final byte TICKING = 2;
    @Unique
    byte tickingStatus = NOT_TICKING;
    @Unique
    volatile boolean ready = false;
    // Injection point: first logger.info call in tickServer signals the tick loop pause.
    // If upgrading Minecraft, verify ordinal=0 still maps to the pause log and update if needed.
    @Inject(
            method = "tickServer",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V", ordinal = 0, remap = false)
    )
    private void onTickServerPause(final BooleanSupplier haveTime, final CallbackInfo ci) {
        this.tickingStatus = NOT_TICKING;
    }
    // Injection point: second return in tickServer captures nano/tickTime locals.
    // If upgrading Minecraft, verify ordinal=1 still hits the correct return site.
    @Inject(method = "tickServer", at = @At(value = "RETURN", ordinal = 1))
    private void onTickServerReturn(final BooleanSupplier haveTime, final CallbackInfo ci, @Local(name = "nano") final long nano, @Local(name = "tickTime") final long tickTime) {
        if (this.tickingStatus == NOT_TICKING) {
            this.tickingStatus = INITIALIZING;
        } else if (this.tickingStatus == INITIALIZING) {
            this.tickingStatus = TICKING;
            this.secondStartTime = nano;
        }
        if(tickingStatus == TICKING) {
            this.secondTickCount++;
            this.secondTotalDurationNanos += tickTime;
            long now = System.nanoTime();
            long elapsed = now - this.secondStartTime;
            if (elapsed >= 1_000_000_000L) {
                SecondBucket bucket = new SecondBucket(this.secondTickCount, elapsed, this.secondTotalDurationNanos, now);
                this.minuteBuckets[this.minuteBucketIndex] = bucket;
                this.minuteBucketIndex = (this.minuteBucketIndex + 1) % 60;
                if (this.minuteBucketIndex == 0) {
                    this.minuteWindowFilled = true;
                }

                double secondTps = this.secondTickCount / (elapsed / 1_000_000_000.0);
                double secondMspt = this.secondTickCount > 0 ? ((double) this.secondTotalDurationNanos / this.secondTickCount) / 1_000_000.0 : 0.0;

                double minuteTps = 20.0;
                double minuteMspt = 0.0;
                int validCount = this.minuteWindowFilled ? 60 : this.minuteBucketIndex;
                long minuteTotalTicks = 0;
                long minuteTotalDuration = 0;
                long minuteTotalElapsed = 0;
                for (int i = 0; i < validCount; i++) {
                    SecondBucket b = this.minuteBuckets[i];
                    if (b == null) continue;
                    minuteTotalTicks += b.tickCount();
                    minuteTotalDuration += b.accumulatedTickNanos();
                    minuteTotalElapsed += b.elapsedNanos();
                }
                if (minuteTotalTicks > 0) {
                    minuteTps = minuteTotalTicks / (minuteTotalElapsed / 1_000_000_000.0);
                    minuteMspt = (double) minuteTotalDuration / minuteTotalTicks / 1_000_000.0;
                }

                TickSnapshot newSnapshot = new TickSnapshot(secondTps, secondMspt, minuteTps, minuteMspt, System.currentTimeMillis());
                this.snapshotRef.set(newSnapshot);
                if (!this.ready) this.ready = true;

                this.secondStartTime = now;
                this.secondTickCount = 0;
                this.secondTotalDurationNanos = 0;
            }
        }
    }
    @Unique
    public TickSnapshot tick_monitor$getSnapshot() {
        return this.snapshotRef.get();
    }
    @Unique
    public boolean tick_monitor$isReady() {
        return this.ready;
    }
}
