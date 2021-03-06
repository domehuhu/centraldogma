/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.storage.repository;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static com.linecorp.centraldogma.internal.Util.unsafeCast;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

/**
 * Utility methods that are useful when implementing a {@link Repository} implementation.
 */
final class RepositoryUtil {

    static final Map<FindOption<?>, Object> EXISTS_FIND_OPTIONS =
            ImmutableMap.of(FindOption.FETCH_CONTENT, false, FindOption.MAX_ENTRIES, 1);
    static final Map<FindOption<?>, Object> GET_FIND_OPTIONS = ImmutableMap.of(FindOption.MAX_ENTRIES, 1);

    private static final CancellationException CANCELLATION_EXCEPTION =
            Exceptions.clearTrace(new CancellationException("parent complete"));

    static <T> CompletableFuture<Entry<T>> watch(Repository repo, Revision lastKnownRev, Query<T> query) {

        requireNonNull(repo, "repo");
        requireNonNull(lastKnownRev, "lastKnownRev");
        requireNonNull(query, "query");

        final Query<Object> castQuery = unsafeCast(query);
        final CompletableFuture<Entry<Object>> parentFuture = new CompletableFuture<>();
        repo.getOrNull(lastKnownRev, castQuery)
            .thenAccept(oldResult -> watch(repo, castQuery, lastKnownRev, oldResult, parentFuture))
            .exceptionally(voidFunction(parentFuture::completeExceptionally));

        return unsafeCast(parentFuture);
    }

    private static void watch(Repository repo, Query<Object> query,
                              Revision lastKnownRev, @Nullable Entry<Object> oldResult,
                              CompletableFuture<Entry<Object>> parentFuture) {

        final CompletableFuture<Revision> future = repo.watch(lastKnownRev, query.path());
        parentFuture.whenComplete((res, cause) -> future.completeExceptionally(CANCELLATION_EXCEPTION));

        future.thenCompose(newRev -> repo.getOrNull(newRev, query).thenAccept(newResult -> {
            if (newResult == null ||
                oldResult != null && Objects.equals(oldResult.content(), newResult.content())) {
                // Entry does not exist or did not change; watch again for more changes.
                if (!parentFuture.isDone()) {
                    // ... only when the parent future has not been cancelled.
                    watch(repo, query, newRev, oldResult, parentFuture);
                }
            } else {
                parentFuture.complete(newResult);
            }
        })).exceptionally(voidFunction(parentFuture::completeExceptionally));
    }

    private RepositoryUtil() {}
}
