package savage.commoneconomy.shop;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Soft integration with "Get Off My Lawn ReServed" (mod id: goml).
 * <p>
 * Used to restrict shop creation so that regular players may only create shops
 * inside claims they own. GOML is an optional dependency accessed purely via
 * reflection, so this mod compiles and runs fine without GOML on the classpath.
 * If GOML is absent, all checks pass (no restriction), preserving the original
 * behaviour.
 */
public final class ClaimGuard {
    private static final boolean AVAILABLE;
    private static final Method GET_CLAIMS_AT; // ClaimUtils.getClaimsAt(LevelReader, BlockPos) -> Selection
    private static final Method ANY_MATCH;     // Selection.anyMatch(Predicate) -> boolean
    private static final Method ENTRY_GET_VALUE; // Entry.getValue() -> Claim
    private static final Method CLAIM_IS_OWNER;  // Claim.isOwner(UUID) -> boolean

    static {
        boolean available = false;
        Method getClaimsAt = null, anyMatch = null, entryGetValue = null, claimIsOwner = null;
        if (FabricLoader.getInstance().isModLoaded("goml")) {
            try {
                Class<?> claimUtils = Class.forName("draylar.goml.api.ClaimUtils");
                getClaimsAt = claimUtils.getMethod("getClaimsAt", LevelReader.class, BlockPos.class);

                Class<?> selection = Class.forName("com.jamieswhiteshirt.rtree3i.Selection");
                anyMatch = selection.getMethod("anyMatch", Predicate.class);

                Class<?> entry = Class.forName("com.jamieswhiteshirt.rtree3i.Entry");
                entryGetValue = entry.getMethod("getValue");

                Class<?> claim = Class.forName("draylar.goml.api.Claim");
                claimIsOwner = claim.getMethod("isOwner", UUID.class);

                available = true;
            } catch (Throwable t) {
                // GOML present but API changed/unavailable; fail open (no restriction).
                available = false;
            }
        }
        AVAILABLE = available;
        GET_CLAIMS_AT = getClaimsAt;
        ANY_MATCH = anyMatch;
        ENTRY_GET_VALUE = entryGetValue;
        CLAIM_IS_OWNER = claimIsOwner;
    }

    private ClaimGuard() {}

    /** True if GOML is installed and its claim API was resolved. */
    public static boolean isClaimSystemPresent() {
        return AVAILABLE;
    }

    /**
     * Whether the given player is allowed to create a shop at {@code pos}.
     * <p>
     * If GOML is not installed (or its API is unavailable), always returns true.
     * If GOML is installed, returns true only when {@code pos} lies within a
     * claim owned by {@code playerId}.
     */
    public static boolean canCreateShopAt(Level world, BlockPos pos, UUID playerId) {
        if (!AVAILABLE) {
            return true;
        }
        try {
            Object selection = GET_CLAIMS_AT.invoke(null, world, pos);
            Predicate<Object> ownedByPlayer = entry -> {
                try {
                    Object claim = ENTRY_GET_VALUE.invoke(entry);
                    return (boolean) CLAIM_IS_OWNER.invoke(claim, playerId);
                } catch (Throwable t) {
                    return false;
                }
            };
            return (boolean) ANY_MATCH.invoke(selection, ownedByPlayer);
        } catch (Throwable t) {
            // On any reflection failure, fail open so shops are never wrongly blocked.
            return true;
        }
    }
}
