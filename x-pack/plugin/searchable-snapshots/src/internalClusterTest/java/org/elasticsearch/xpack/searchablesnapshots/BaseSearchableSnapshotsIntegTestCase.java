/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.xpack.searchablesnapshots;

import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.fs.FsRepository;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.xpack.core.searchablesnapshots.MountSearchableSnapshotAction;
import org.elasticsearch.xpack.core.searchablesnapshots.MountSearchableSnapshotRequest;
import org.elasticsearch.xpack.searchablesnapshots.cache.CacheService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider.CLUSTER_ROUTING_REBALANCE_ENABLE_SETTING;
import static org.elasticsearch.license.LicenseService.SELF_GENERATED_LICENSE_TYPE;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public abstract class BaseSearchableSnapshotsIntegTestCase extends ESIntegTestCase {
    @Override
    protected boolean addMockInternalEngine() {
        return false;
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(LocalStateSearchableSnapshots.class);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        final Settings.Builder builder = Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put(SELF_GENERATED_LICENSE_TYPE.getKey(), "trial")
            .put(CLUSTER_ROUTING_REBALANCE_ENABLE_SETTING.getKey(), "none");
        if (randomBoolean()) {
            builder.put(
                CacheService.SNAPSHOT_CACHE_SIZE_SETTING.getKey(),
                rarely()
                    ? randomBoolean()
                        ? new ByteSizeValue(randomIntBetween(0, 10), ByteSizeUnit.KB)
                        : new ByteSizeValue(randomIntBetween(0, 1000), ByteSizeUnit.BYTES)
                    : new ByteSizeValue(randomIntBetween(1, 10), ByteSizeUnit.MB)
            );
        }
        if (randomBoolean()) {
            builder.put(
                CacheService.SNAPSHOT_CACHE_RANGE_SIZE_SETTING.getKey(),
                rarely()
                    ? new ByteSizeValue(randomIntBetween(4, 1024), ByteSizeUnit.KB)
                    : new ByteSizeValue(randomIntBetween(1, 10), ByteSizeUnit.MB)
            );
        }
        return builder.build();
    }

    protected void createFsRepository(String repositoryName, Path location) {
        createRepository(
            repositoryName,
            FsRepository.TYPE,
            Settings.builder().put(FsRepository.LOCATION_SETTING.getKey(), location).build(),
            true
        );
    }

    protected void createRepository(String repositoryName, String repositoryType, Settings repositorySettings, boolean verify) {
        assertAcked(
            client().admin()
                .cluster()
                .preparePutRepository(repositoryName)
                .setType(repositoryType)
                .setSettings(repositorySettings)
                .setVerify(verify)
        );
    }

    protected SnapshotId createSnapshot(String repositoryName, List<String> indices) {
        final CreateSnapshotResponse response = client().admin()
            .cluster()
            .prepareCreateSnapshot(repositoryName, randomAlphaOfLength(10).toLowerCase(Locale.ROOT))
            .setIndices(indices.toArray(Strings.EMPTY_ARRAY))
            .setWaitForCompletion(true)
            .get();

        final SnapshotInfo snapshotInfo = response.getSnapshotInfo();
        assertThat(snapshotInfo.successfulShards(), greaterThan(0));
        assertThat(snapshotInfo.failedShards(), equalTo(0));
        return snapshotInfo.snapshotId();
    }

    protected String mountSnapshot(String repositoryName, String snapshotName, String indexName, Settings indexSettings) throws Exception {
        final String restoredIndexName = randomBoolean() ? indexName : randomAlphaOfLength(10).toLowerCase(Locale.ROOT);
        final MountSearchableSnapshotRequest mountRequest = new MountSearchableSnapshotRequest(
            restoredIndexName,
            repositoryName,
            snapshotName,
            indexName,
            Settings.builder().put(IndexSettings.INDEX_CHECK_ON_STARTUP.getKey(), Boolean.FALSE.toString()).put(indexSettings).build(),
            Strings.EMPTY_ARRAY,
            true
        );

        final RestoreSnapshotResponse restoreResponse = client().execute(MountSearchableSnapshotAction.INSTANCE, mountRequest).get();
        assertThat(restoreResponse.getRestoreInfo().successfulShards(), equalTo(getNumShards(restoredIndexName).numPrimaries));
        assertThat(restoreResponse.getRestoreInfo().failedShards(), equalTo(0));
        return restoredIndexName;
    }

    protected void createRepo(String fsRepoName) {
        final Path repo = randomRepoPath();
        assertAcked(
            client().admin().cluster().preparePutRepository(fsRepoName).setType("fs").setSettings(Settings.builder().put("location", repo))
        );
    }

    protected void createAndPopulateIndex(String indexName, Settings.Builder settings) throws InterruptedException {
        assertAcked(prepareCreate(indexName, settings));
        ensureGreen(indexName);
        populateIndex(indexName, 100);
    }

    protected void populateIndex(String indexName, int maxIndexRequests) throws InterruptedException {
        final List<IndexRequestBuilder> indexRequestBuilders = new ArrayList<>();
        for (int i = between(10, maxIndexRequests); i >= 0; i--) {
            indexRequestBuilders.add(client().prepareIndex(indexName).setSource("foo", randomBoolean() ? "bar" : "baz"));
        }
        indexRandom(true, true, indexRequestBuilders);
        refresh(indexName);
        assertThat(
            client().admin().indices().prepareForceMerge(indexName).setOnlyExpungeDeletes(true).setFlush(true).get().getFailedShards(),
            equalTo(0)
        );
    }
}
