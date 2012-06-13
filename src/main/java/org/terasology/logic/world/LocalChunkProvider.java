/*
 * Copyright 2012
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.logic.world;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.terasology.components.world.LocationComponent;
import org.terasology.entitySystem.EntityRef;
import org.terasology.game.CoreRegistry;
import org.terasology.game.GameEngine;
import org.terasology.game.TerasologyEngine;
import org.terasology.logic.world.generationPhase.*;
import org.terasology.logic.world.generator.core.ChunkGeneratorManager;
import org.terasology.math.Region3i;
import org.terasology.math.Vector3i;
import org.terasology.performanceMonitor.PerformanceMonitor;

import javax.vecmath.Vector3f;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Immortius
 */
public class LocalChunkProvider implements ChunkProvider {
    private static final int CACHE_SIZE = (int) (2 * Runtime.getRuntime().maxMemory() / 1048576);

    private Logger logger = Logger.getLogger(getClass().getName());
    private ChunkStore farStore;

    private ChunkPhase fetchPhase;
    private ChunkPhase generatePhase;
    private ChunkPhase secondPassPhase;
    private ChunkPhase internalLightingPhase;
    private ChunkPhase propagateLightPhase;
    private List<ChunkPhase> phases = Lists.newArrayList();
    private Set<CacheRegion> regions = Sets.newHashSet();

    private ConcurrentMap<Vector3i, Chunk> nearCache = Maps.newConcurrentMap();

    public LocalChunkProvider(ChunkStore farStore, ChunkGeneratorManager generator) {
        this.farStore = farStore;
        Comparator<Vector3i> chunkRelevanceComparator = new ChunkRelevanceComparator();
        fetchPhase = new FetchPhase(8, chunkRelevanceComparator, farStore, nearCache);
        generatePhase = new CreateChunkPhase(8, chunkRelevanceComparator, generator, nearCache);
        secondPassPhase = new SecondPassPhase(2, chunkRelevanceComparator, generator, this);
        internalLightingPhase = new InternalLightingPhase(4, chunkRelevanceComparator, this);
        propagateLightPhase = new PropagateLightingPhase(2, chunkRelevanceComparator, generator, this);
        phases.add(generatePhase);
        phases.add(secondPassPhase);
        phases.add(internalLightingPhase);
        phases.add(propagateLightPhase);
    }

    @Override
    public void addRegionEntity(EntityRef entity, int distance) {
        CacheRegion region = new CacheRegion(entity, distance);
        regions.remove(region);
        regions.add(region);
        checkChunkStatus(region);
    }

    @Override
    public void removeRegionEntity(EntityRef entity) {
        regions.remove(new CacheRegion(entity, 0));
    }

    @Override
    public void update() {
        for (CacheRegion cacheRegion : regions) {
            cacheRegion.update();
            if (cacheRegion.isDirty()) {
                cacheRegion.setUpToDate();
                final Region3i reviewRegion = cacheRegion.getRegion().expand(new Vector3i(1, 0, 1));
                CoreRegistry.get(GameEngine.class).submitTask("Review chunk region", new Runnable() {
                    @Override
                    public void run() {
                        for (Vector3i chunkPos : reviewRegion) {
                            Chunk chunk = getChunk(chunkPos);
                            if (chunk == null) {
                                PerformanceMonitor.startActivity("Check chunk in cache");
                                if (farStore.contains(chunkPos) && !fetchPhase.processing(chunkPos)) {
                                    fetchPhase.queue(chunkPos);
                                } else if (!generatePhase.processing(chunkPos)) {
                                    generatePhase.queue(chunkPos);
                                }
                                PerformanceMonitor.endActivity();
                            } else {
                                checkState(chunk);
                            }
                        }
                    }
                });
            }
        }
        if (fetchPhase.isResultAvailable()) {
            Vector3i chunkPos = fetchPhase.poll();
            for (Vector3i pos : Region3i.createFromCenterExtents(chunkPos, new Vector3i(1, 0, 1))) {
                checkState(pos);
            }
        }
        if (generatePhase.isResultAvailable()) {
            Vector3i chunkPos = generatePhase.poll();
            logger.log(Level.FINE, "Received generated chunk " + chunkPos);
            for (Vector3i pos : Region3i.createFromCenterExtents(chunkPos, new Vector3i(1, 0, 1))) {
                checkReadyForSecondPass(pos);
            }
        }
        if (secondPassPhase.isResultAvailable()) {
            Vector3i chunkPos = secondPassPhase.poll();
            logger.log(Level.FINE, "Received second passed chunk " + chunkPos);
            for (Vector3i pos : Region3i.createFromCenterExtents(chunkPos, new Vector3i(1, 0, 1))) {
                checkReadyToDoInternalLighting(pos);
            }
        }
        if (internalLightingPhase.isResultAvailable()) {
            Vector3i chunkPos = internalLightingPhase.poll();
            logger.log(Level.FINE, "Received internally lit chunk " + chunkPos);
            for (Vector3i pos : Region3i.createFromCenterExtents(chunkPos, new Vector3i(1, 0, 1))) {
                checkReadyToPropagateLighting(pos);
            }
        }
        if (propagateLightPhase.isResultAvailable()) {
            Vector3i chunkPos = propagateLightPhase.poll();
            logger.log(Level.FINE, "Received second passed chunk " + chunkPos);
            for (Vector3i pos : Region3i.createFromCenterExtents(chunkPos, new Vector3i(1, 0, 1))) {
                checkComplete(pos);
            }
        }
        PerformanceMonitor.startActivity("Review cache size");
        if (nearCache.size() > CACHE_SIZE) {
            logger.log(Level.INFO, "Compacting cache");
            Iterator<Vector3i> iterator = nearCache.keySet().iterator();
            while (iterator.hasNext()) {
                Vector3i pos = iterator.next();
                boolean keep = false;
                for (CacheRegion region : regions) {
                    if (region.getRegion().expand(new Vector3i(4, 0, 4)).encompasses(pos)) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) {
                    for (ChunkPhase phase : phases) {
                        if (phase.processing(pos)) {
                            keep = true;
                            break;
                        }
                    }
                }
                if (!keep) {
                    // TODO: need some way to not dispose chunks being edited (or do so safely)
                    Chunk chunk = nearCache.get(pos);
                    farStore.put(chunk);
                    iterator.remove();
                    chunk.dispose();
                }

            }
        }
        PerformanceMonitor.endActivity();
    }

    @Override
    public boolean isChunkAvailable(Vector3i pos) {
        return nearCache.containsKey(pos);
    }

    @Override
    public Chunk getChunk(int x, int y, int z) {
        return getChunk(new Vector3i(x, y, z));
    }

    @Override
    public Chunk getChunk(Vector3i pos) {
        return nearCache.get(pos);
    }

    @Override
    public void dispose() {
        generatePhase.dispose();
        secondPassPhase.dispose();
        internalLightingPhase.dispose();
        propagateLightPhase.dispose();
        for (Chunk chunk : nearCache.values()) {
            farStore.put(chunk);
            chunk.dispose();
        }
        nearCache.clear();
    }

    @Override
    public float size() {
        return farStore.size();
    }

    /**
     * Checks for incomplete chunks in the region
     *
     * @param cacheRegion
     */
    private void checkChunkStatus(CacheRegion cacheRegion) {
        for (Vector3i chunkPos : cacheRegion.getRegion().expand(new Vector3i(1, 0, 1))) {
            Chunk chunk = getChunk(chunkPos);
            if (chunk == null) {
                PerformanceMonitor.startActivity("Check chunk in cache");
                if (farStore.contains(chunkPos) && !fetchPhase.processing(chunkPos)) {
                    fetchPhase.queue(chunkPos);
                } else if (!generatePhase.processing(chunkPos)) {
                    generatePhase.queue(chunkPos);
                }
                PerformanceMonitor.endActivity();
            } else {
                checkState(chunk);
            }
        }
    }

    private void checkState(Vector3i pos) {
        Chunk chunk = getChunk(pos);
        if (chunk != null) {
            checkState(chunk);
        }
    }

    private void checkState(Chunk chunk) {
        switch (chunk.getChunkState()) {
            case AdjacencyGenerationPending:
                checkReadyForSecondPass(chunk.getPos());
                break;
            case InternalLightGenerationPending:
                checkReadyToDoInternalLighting(chunk.getPos());
                break;
            case LightPropagationPending:
                checkReadyToPropagateLighting(chunk.getPos());
                break;
            case FullLightConnectivityPending:
                checkComplete(chunk.getPos());
                break;
            default:
                break;
        }
    }

    private void checkReadyForSecondPass(Vector3i pos) {
        Chunk chunk = getChunk(pos);
        if (chunk != null && chunk.getChunkState() == Chunk.State.AdjacencyGenerationPending && !secondPassPhase.processing(pos)) {
            for (Vector3i adjPos : Region3i.createFromCenterExtents(pos, new Vector3i(1, 0, 1))) {
                if (!adjPos.equals(pos)) {
                    Chunk adjChunk = getChunk(adjPos);
                    if (adjChunk == null) {
                        return;
                    }
                }
            }
            logger.log(Level.FINE, "Queueing for adjacency generation " + pos);
            secondPassPhase.queue(pos);
        }
    }

    private void checkReadyToDoInternalLighting(Vector3i pos) {
        Chunk chunk = getChunk(pos);
        if (chunk != null && chunk.getChunkState() == Chunk.State.InternalLightGenerationPending && !internalLightingPhase.processing(pos)) {
            for (Vector3i adjPos : Region3i.createFromCenterExtents(pos, new Vector3i(1, 0, 1))) {
                if (!adjPos.equals(pos)) {
                    Chunk adjChunk = getChunk(adjPos);
                    if (adjChunk == null || adjChunk.getChunkState().compareTo(Chunk.State.InternalLightGenerationPending) < 0) {
                        return;
                    }
                }
            }
            logger.log(Level.FINE, "Queueing for adjacency generation " + pos);
            internalLightingPhase.queue(pos);
        }
    }

    private void checkReadyToPropagateLighting(Vector3i pos) {
        Chunk chunk = getChunk(pos);
        if (chunk != null && chunk.getChunkState() == Chunk.State.LightPropagationPending && !propagateLightPhase.processing(pos)) {
            for (Vector3i adjPos : Region3i.createFromCenterExtents(pos, new Vector3i(1, 0, 1))) {
                if (!adjPos.equals(pos)) {
                    Chunk adjChunk = getChunk(adjPos);
                    if (adjChunk == null || adjChunk.getChunkState().compareTo(Chunk.State.LightPropagationPending) < 0) {
                        return;
                    }
                }
            }
            logger.log(Level.FINE, "Queueing for second pass " + pos);
            propagateLightPhase.queue(pos);
        }
    }

    private void checkComplete(Vector3i pos) {
        Chunk chunk = getChunk(pos);
        if (chunk != null && chunk.getChunkState() == Chunk.State.FullLightConnectivityPending) {
            for (Vector3i adjPos : Region3i.createFromCenterExtents(pos, new Vector3i(1, 0, 1))) {
                if (!adjPos.equals(pos)) {
                    Chunk adjChunk = getChunk(adjPos);
                    if (adjChunk == null || adjChunk.getChunkState().compareTo(Chunk.State.FullLightConnectivityPending) < 0) {
                        return;
                    }
                }
            }
            logger.log(Level.FINE, "Now complete " + pos);
            chunk.setChunkState(Chunk.State.Complete);
            // TODO: Send event out

        }
    }

    private static class CacheRegion {
        private EntityRef entity;
        private int distance;
        private boolean dirty;
        private Vector3i center = new Vector3i();

        public CacheRegion(EntityRef entity, int distance) {
            this.entity = entity;
            this.distance = distance;

            LocationComponent loc = entity.getComponent(LocationComponent.class);
            if (loc == null) {
                dirty = false;
            } else {
                center.set(worldToChunkPos(loc.getWorldPosition()));
                dirty = true;
            }
        }

        public boolean isValid() {
            return entity.hasComponent(LocationComponent.class);
        }

        public boolean isDirty() {
            return dirty;
        }

        public void setUpToDate() {
            dirty = false;
        }

        public void update() {
            if (!isValid()) {
                dirty = false;
            } else {
                Vector3i newCenter = getCenter();
                if (!newCenter.equals(center)) {
                    dirty = true;
                    center.set(newCenter);
                }
            }
        }

        public Region3i getRegion() {
            LocationComponent loc = entity.getComponent(LocationComponent.class);
            if (loc != null) {
                return Region3i.createFromCenterExtents(worldToChunkPos(loc.getWorldPosition()), new Vector3i(distance / 2, 0, distance / 2));
            }
            return Region3i.EMPTY;
        }

        private Vector3i getCenter() {
            LocationComponent loc = entity.getComponent(LocationComponent.class);
            if (loc != null) {
                return worldToChunkPos(loc.getWorldPosition());
            }
            return new Vector3i();
        }

        private Vector3i worldToChunkPos(Vector3f worldPos) {
            worldPos.x /= Chunk.SIZE_X;
            worldPos.y = 0;
            worldPos.z /= Chunk.SIZE_Z;
            return new Vector3i(worldPos);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (o instanceof CacheRegion) {
                CacheRegion other = (CacheRegion) o;
                return Objects.equal(other.entity, entity);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(entity);
        }
    }

    private class ChunkRelevanceComparator implements Comparator<Vector3i> {

        @Override
        public int compare(Vector3i o1, Vector3i o2) {
            return score(o1) - score(o2);
        }

        private int score(Vector3i chunk) {
            int score = Integer.MAX_VALUE;
            for (CacheRegion region : regions) {
                int dist = distFromRegion(chunk, region.center);
                if (dist < score) {
                    score = dist;
                }
            }
            return score;
        }

        private int distFromRegion(Vector3i pos, Vector3i regionCenter) {
            return pos.gridDistance(regionCenter);
        }
    }
}
