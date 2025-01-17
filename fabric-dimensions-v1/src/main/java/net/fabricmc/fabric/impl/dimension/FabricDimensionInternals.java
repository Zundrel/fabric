/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.fabric.impl.dimension;

import com.google.common.base.Preconditions;
import net.fabricmc.fabric.api.dimension.v1.EntityPlacer;
import net.fabricmc.fabric.api.dimension.v1.FabricDimensionType;
import net.fabricmc.fabric.api.dimension.v1.FabricDimensions;
import net.fabricmc.fabric.mixin.EntityHooks;
import net.minecraft.block.pattern.BlockPattern;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.world.dimension.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FabricDimensionInternals {
	private FabricDimensionInternals() {
		throw new AssertionError();
	}

	public static final boolean DEBUG = System.getProperty("fabric.dimension.debug", "false").equalsIgnoreCase("true");
	public static final Logger LOGGER = LogManager.getLogger();

	/**
	 * The entity currently being transported to another dimension
	 */
	private static final ThreadLocal<Entity> PORTAL_ENTITY = new ThreadLocal<>();
	/**
	 * The custom placement logic passed from {@link FabricDimensions#teleport(Entity, DimensionType, EntityPlacer)}
	 */
	private static EntityPlacer customPlacement;

	/*
	 * The dimension change hooks consist of two steps:
	 * - First, we memorize the currently teleported entity, and set required fields
	 * - Then, we retrieve the teleported entity in the placement logic in PortalForcer#getPortal
	 *   and use it to call the entity placers
	 * This lets us use the exact same logic for any entity and prevent the vanilla getPortal (which has unwanted
	 * side effects) from running, while keeping the patches minimally invasive.
	 *
	 * Shortcomings: bugs may arise if another patch cancels the teleportation method between
	 * #prepareDimensionalTeleportation and #tryFindPlacement, AND a mod calls PortalForcer#getPortal directly
	 * right after.
	 */

	public static void prepareDimensionalTeleportation(Entity entity) {
		Preconditions.checkNotNull(entity);
		PORTAL_ENTITY.set(entity);

		// Set values used by `PortalForcer#changeDimension` to prevent a NPE crash.
		EntityHooks access = ((EntityHooks) entity);
		if (entity.getLastPortalDirectionVector() == null) {
			access.setLastPortalDirectionVector(entity.getRotationVector());
		}
		if (entity.getLastPortalDirection() == null) {
			access.setLastPortalDirection(entity.getHorizontalFacing());
		}
	}

	/* Nullable */
	public static BlockPattern.TeleportTarget tryFindPlacement(ServerWorld destination, Direction portalDir, double portalX, double portalY) {
		Preconditions.checkNotNull(destination);
		Entity teleported = PORTAL_ENTITY.get();
		PORTAL_ENTITY.set(null);

		// If the entity is null, the call does not come from a vanilla context
		if (teleported == null) {
			return null;
		}

		// Custom placement logic, falls back to default dimension placement if no placement or target found
		EntityPlacer customPlacement = FabricDimensionInternals.customPlacement;
		if (customPlacement != null) {
			BlockPattern.TeleportTarget customTarget = customPlacement.placeEntity(teleported, destination, portalDir, portalX, portalY);

			if (customTarget != null) {
				return customTarget;
			}
		}

		// Default placement logic, falls back to vanilla if not a fabric dimension
		DimensionType dimType = destination.getDimension().getType();
		if (dimType instanceof FabricDimensionType) {
			BlockPattern.TeleportTarget defaultTarget = ((FabricDimensionType) dimType).getDefaultPlacement().placeEntity(teleported, destination, portalDir, portalX, portalY);

			if (defaultTarget == null) {
				throw new IllegalStateException("Mod dimension " + DimensionType.getId(dimType) + " returned an invalid teleport target");
			}
			return defaultTarget;
		}

		// Vanilla / other implementations logic, undefined behaviour on custom dimensions
		return null;
	}

	@SuppressWarnings("unchecked")
	public static <E extends Entity> E changeDimension(E teleported, DimensionType dimension, EntityPlacer placement) {
		assert !teleported.world.isClient : "Entities can only be teleported on the server side";
		assert Thread.currentThread() == ((ServerWorld) teleported.world).getServer().getThread() : "Entities must be teleported from the main server thread";

		try {
			customPlacement = placement;
			return (E) teleported.changeDimension(dimension);
		} finally {
			customPlacement = null;
		}
	}

}
